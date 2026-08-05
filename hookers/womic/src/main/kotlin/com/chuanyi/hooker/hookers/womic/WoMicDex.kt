package com.chuanyi.hooker.hookers.womic

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * 定位「权益写入点」。
 *
 * 这是整条链上唯一必须按混淆名找的东西。它的形状：
 *
 * ```java
 * // 混淆后叫 Z2.f.a，下个版本一定不叫这个
 * public void a(int state, String productId) {
 *     if (state == settings.purchaseState) return;
 *     settings.purchaseState = state;
 *     settings.prefs.edit().putInt("purchaseState", state).apply();   // ← 锚点
 *     settings.productId = productId;
 *     settings.prefs.edit().putString("productId", productId).apply();// ← 锚点
 *     viewModel.entitlement.setValue(state == 1);                     // ← 真正重要的一句
 * }
 * ```
 *
 * 最后那句是 [WoMicHooker] 一定要走到的地方：广告的显示与音量条的可用性都挂在那个
 * LiveData 上，只改 SharedPreferences 影响不到**当前这次**运行。
 *
 * ## 锚点为什么选这两个串
 *
 * `"purchaseState"` 和 `"productId"` 是 SharedPreferences 的**持久化键**。厂商改了它们
 * 就等于把所有老用户的订阅状态缓存丢掉（下次启动读不到、界面回到未订阅），这个代价
 * 足够大，所以它们比类名、方法名、甚至字段顺序都稳。
 *
 * 全包里同时引用这两个串的只有两处：设置单例的构造器（参数是 `Context`）和这个写入点
 * （参数是 `(int, String)`）。加上签名就唯一了，不用担心撞车。
 *
 * ## 四条路
 *
 * 1. `sink_class` / `sink_method` 设置项 —— 应急，不重新出包就能纠正
 * 2. 按 versionCode 缓存的上次扫描结果（扫一次几百毫秒，且在启动路径上）
 * 3. DexKit 扫 dex
 * 4. 写死的 5.3 (vc106) 结果 —— DexKit 的 .so 缺本机 ABI 时还能工作
 */
internal object WoMicDex {

    /** 5.3 (vc106) 实测结果。DexKit 走不通时的退路，不是主力。 */
    private const val PINNED_CLASS = "Z2.f"
    private const val PINNED_METHOD = "a"

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "sink.version"
    private const val KEY_CLASS = "sink.class"
    private const val KEY_METHOD = "sink.method"

    /**
     * 权益写入点，找不到返回 null。
     *
     * 返回 null 不等于解锁失败：[Prefs] 那一侧完全不依赖它，只是要等应用下次启动
     * 才生效。调用方按这个前提降级。
     */
    fun sink(scope: HookScope): Method? {
        configured(scope)?.let {
            scope.log.i("权益写入点来自设置项：${it.declaringClass.name}.${it.name}")
            return it
        }
        cached(scope)?.let {
            scope.log.d("权益写入点命中缓存：${it.declaringClass.name}.${it.name}")
            return it
        }
        scan(scope)?.let {
            remember(scope, it)
            scope.log.i("权益写入点扫描到：${it.declaringClass.name}.${it.name}")
            return it
        }
        return pinned(scope)?.also {
            scope.log.w("扫描没结果，退回写死的类名：${it.declaringClass.name}.${it.name}")
        }
    }

    // -----------------------------------------------------------------------

    private fun configured(scope: HookScope): Method? {
        val className = scope.string(WoMicHooker.KEY_SINK_CLASS)?.takeIf { it.isNotBlank() } ?: return null
        val methodName = scope.string(WoMicHooker.KEY_SINK_METHOD)?.takeIf { it.isNotBlank() }
        return scope.classOrNull(className)?.let { pick(it, methodName) }
    }

    private fun pinned(scope: HookScope): Method? =
        scope.classOrNull(PINNED_CLASS)?.let { pick(it, PINNED_METHOD) }

    /**
     * 类里挑出那个 `(int, String) -> void`。
     *
     * 名字只作为过滤条件之一 —— 混淆后的方法名会变，形状不会。同一个类里同时存在
     * 两个这种签名的方法在实践中没有出现过，真出现了取第一个也不会更糟。
     */
    private fun pick(owner: Class<*>, name: String?): Method? = owner.declaredMethods.firstOrNull {
        (name == null || it.name == name) &&
            it.returnType == Void.TYPE &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == Int::class.javaPrimitiveType &&
            it.parameterTypes[1] == String::class.java
    }

    private fun cached(scope: HookScope): Method? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val className = prefs.getString(KEY_CLASS, null)?.takeIf { it.isNotBlank() } ?: return null
        val methodName = prefs.getString(KEY_METHOD, null)?.takeIf { it.isNotBlank() } ?: return null
        // 名字对不上（原地覆盖安装但 versionCode 没变）就当没缓存，重新扫。
        return scope.classOrNull(className)?.let { pick(it, methodName) }
    }

    private fun remember(scope: HookScope, sink: Method) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(KEY_CLASS, sink.declaringClass.name)
                .putString(KEY_METHOD, sink.name)
                .apply()
        }
    }

    /** 缓存写在**目标自己**的数据目录：模块的远端配置是只读的，被 hook 的进程写不了。 */
    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    private fun scan(scope: HookScope): Method? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return null
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而 LSPosed
        // 给模块构造的 classloader 下那条路不通。症状有迷惑性：加载当时不报错，等到第一次
        // 调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块自己那套
        // 带绝对路径兜底的加载器装进来；soname 一个进程只加载一次，DexKit 自己那次随后
        // 就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val hits = runCatching {
                dex.findMethod {
                    matcher {
                        paramTypes("int", "java.lang.String")
                        returnType = "void"
                        usingStrings(WoMic.KEY_PURCHASE_STATE, WoMic.KEY_PRODUCT_ID)
                    }
                }
            }.onFailure { scope.log.w("dex 扫描失败：${it.message}") }.getOrNull()

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when {
                hits == null -> null
                hits.isEmpty() -> {
                    scope.log.w("dex 里找不到写 ${WoMic.KEY_PURCHASE_STATE} 的 (int,String) 方法（${elapsed}ms）")
                    null
                }

                else -> {
                    if (hits.size > 1) {
                        scope.log.w("命中 ${hits.size} 个，取第一个：${hits.joinToString { it.descriptor }}")
                    }
                    scope.log.d("dex 扫描用时 ${elapsed}ms")
                    runCatching { hits.first().getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.w("命中的方法反射不出来：${it.message}") }
                        .getOrNull()
                }
            }
        }
    }
}
