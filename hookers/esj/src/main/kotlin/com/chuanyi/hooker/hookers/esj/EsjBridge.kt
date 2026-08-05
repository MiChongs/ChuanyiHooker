package com.chuanyi.hooker.hookers.esj

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通往 Cocos JS 引擎的双向通道。
 *
 * 两个方向都走引擎自带的官方接口，不碰任何私有实现：
 *
 * | 方向 | 走谁 |
 * |---|---|
 * | Java 往 JS 送代码 | `CocosJavascriptJavaBridge.evalString(String)` |
 * | JS 往 Java 回消息 | JS 侧 `jsb.bridge.sendToNative` → `JsbBridgeWrapper` 按事件名分发 |
 *
 * `JsbBridgeWrapper` 是按事件名维护监听器**列表**的，游戏自己注册的
 * `SDK_onLoginSuccess` 之类和我们的 `esj.hooker.*` 各走各的，互不影响 ——
 * 这也是不去动更底层的 `JsbBridge.setCallback` 的原因：那个是单例回调，
 * 抢过来就把游戏的收信端掐了。
 *
 * ## 时机
 *
 * `runOnGameThread` 只是往 `sTaskQOnGameThread` 里加一项，真正执行是在 GL 线程
 * 每帧的 flush 里。而第一帧必然发生在引擎初始化完成（含 JS 引擎和 jsb 绑定）之后，
 * 所以在 `CocosActivity.onCreate` 里就可以把任务排进去 —— 排的时候引擎没就绪没关系，
 * 它会等。等到它执行时，`WebSocket` 全局类已经存在，而游戏还没走完登录、
 * 更没连上游戏服务器，改写钩子来得及在第一个包之前挂上。
 */
internal class EsjBridge(private val scope: HookScope) {

    private val helperClass = scope.classOrNull(Esj.COCOS_HELPER)
    private val evalClass = scope.classOrNull(Esj.JS_BRIDGE)
    private val wrapperClass = scope.classOrNull(Esj.JSB_BRIDGE_WRAPPER)

    private val runOnGameThread: Method? = runCatching {
        helperClass?.getDeclaredMethod("runOnGameThread", Runnable::class.java)
    }.getOrNull()

    private val evalString: Method? = runCatching {
        evalClass?.getDeclaredMethod("evalString", String::class.java)
    }.getOrNull()

    val isUsable: Boolean get() = runOnGameThread != null && evalString != null

    /** 拿不到通道时给出到底缺哪一半，日志里一眼能看出是引擎改版还是类名变了。 */
    val unavailableReason: String
        get() = buildString {
            if (evalClass == null) append("找不到 ${Esj.JS_BRIDGE}；")
            else if (evalString == null) append("${Esj.JS_BRIDGE} 上没有 evalString；")
            if (helperClass == null) append("找不到 ${Esj.COCOS_HELPER}；")
            else if (runOnGameThread == null) append("${Esj.COCOS_HELPER} 上没有 runOnGameThread；")
            if (isEmpty()) append("通道正常")
        }

    /**
     * 把一段 JS 排进 GL 线程执行。
     *
     * 不看 `evalString` 的返回值：它只反映「有没有抛异常」，脚本内部的成败要靠
     * 脚本自己用 [listen] 那条路回传。
     */
    fun eval(js: String): Boolean {
        val run = runOnGameThread ?: return false
        val eval = evalString ?: return false
        return runCatching {
            run.invoke(null, Runnable {
                runCatching { eval.invoke(null, js) }
                    .onFailure { scope.log.w("引擎执行注入脚本时报错：${it.cause?.message ?: it.message}") }
            })
            true
        }.getOrElse {
            scope.log.w("向 GL 线程投递脚本失败：${it.message}")
            false
        }
    }

    /**
     * 订阅 JS 回传的某个事件。
     *
     * `OnScriptEventListener` 是目标进程里的接口，模块编译期没有它，只能用动态代理
     * 实现。代理的 classloader 必须用**目标的**，否则 `addScriptEventListener`
     * 收参时会因为接口不同源而 `IllegalArgumentException`。
     */
    fun listen(event: String, onMessage: (String?) -> Unit): Boolean {
        val wrapper = wrapperClass ?: return false
        val listenerClass = scope.classOrNull("${Esj.JSB_BRIDGE_WRAPPER}\$OnScriptEventListener")
            ?: return false
        return runCatching {
            val instance = wrapper.getDeclaredMethod("getInstance").invoke(null)
            val proxy = Proxy.newProxyInstance(
                scope.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "onScriptEvent" -> {
                        runCatching { onMessage(args?.getOrNull(0) as? String) }
                        null
                    }
                    // 代理同时要应付 Object 上的三个方法，漏了会在 HashMap 里炸。
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args?.getOrNull(0) === this
                    "toString" -> "EsjListener($event)"
                    else -> null
                }
            }
            wrapper.getDeclaredMethod(
                "addScriptEventListener",
                String::class.java,
                listenerClass,
            ).invoke(instance, event, proxy)
            true
        }.getOrElse {
            scope.log.w("订阅 JS 事件 $event 失败：${it.message}")
            false
        }
    }

    /**
     * 反复投递直到脚本自己报「就绪」。
     *
     * 正常情况第一次就成了。留重试是因为引擎初始化的时长跟机型、包体、
     * 热更资源量都有关系，而 `runOnGameThread` 的队列在极端情况下可能被
     * 引擎自己的初始化任务挤在后面 —— 与其猜一个延迟，不如让脚本自己确认。
     */
    fun injectUntilReady(js: String, attempts: Int, intervalMs: Long, onGiveUp: () -> Unit) {
        val done = AtomicBoolean(false)
        readyFlag = done

        Thread({
            var left = attempts
            while (left-- > 0 && !done.get()) {
                if (!eval(js)) {
                    scope.log.w("注入通道不可用，停止重试")
                    return@Thread
                }
                Thread.sleep(intervalMs)
            }
            if (!done.get()) onGiveUp()
        }, "esj-inject").apply { isDaemon = true }.start()
    }

    /** [injectUntilReady] 起的线程靠它停下来。 */
    private var readyFlag: AtomicBoolean? = null

    fun markReady() {
        readyFlag?.set(true)
    }
}
