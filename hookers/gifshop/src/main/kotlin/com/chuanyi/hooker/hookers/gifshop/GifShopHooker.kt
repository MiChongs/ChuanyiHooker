package com.chuanyi.hooker.hookers.gifshop

import android.content.SharedPreferences
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GIFShop（`com.gif.gifmaker`）3.1.1 —— GIF 制作/录屏工具，计费走
 * **Zipoapps PremiumHelper 5.2.1 + Google Play Billing 7**。
 *
 * ## 结论先写
 *
 * 这个包**没有防护**：没加壳、没混淆字符串、没有签名自校验、没有服务端复验，
 * 四个 `.so` 全是 GIF 编解码，授权一行都不在原生层。整个高级版收敛到
 * `premium_helper_data` 里的一个布尔 —— [PremiumHelper.KEY_ENTITLEMENT]，
 * 判定链见 [PremiumHelper] 的类注释。
 *
 * 所以解锁不是「绕过一道校验」，而是**按住一个本地布尔**。这一点决定了它天然满足
 * 「Google 服务被冻结也要能用」：判定读的是本机 SharedPreferences，跟 Play 无关。
 * Play 那条链只负责**写**这个布尔，而写不进来也不影响读得到。
 *
 * ## 三项功能各自负责什么
 *
 * ```
 *                              ┌── lifetime        把「读」按成 true（运行时，不落盘）
 * premium_helper_data 里的布尔 ─┤
 *                              └── persist         把「写」按成 true + 首次读时补写一次
 *
 * Google Play 结算 ─────────────── offline_billing  连接与查询就地应答，不再真的发出去
 * ```
 *
 * [lifetime] 一项就足够解锁；[persist] 让解锁进到应用自己的存档，**停用本模块后仍然有效**；
 * [offline_billing] 处理的是另一个问题 —— 冻结 Google 之后应用会在启动路径上反复重试
 * 连接（`BillingConnection` 是 10 次 × 500ms 的退避重试），把它就地应答掉，
 * 启动时间与有没有 Google 无关。
 *
 * ## 高级版实际解锁了什么
 *
 * 全包只有两处读应用自己的会员封装 `ie.a0.b()`，其余判定都在 SDK 内部：
 *
 * | 位置 | 免费 | 高级版 |
 * |---|---|---|
 * | `ia.a.b()` 导出分辨率上限 | 1000 | **1280** |
 * | 主界面底栏第 5 项 | 购买入口 | **语言设置**（免费版进不去） |
 * | 横幅 / 插屏 / 开屏广告 | 有 | **全部直接 return** |
 * | GDPR 同意书（要联网） | 弹 | **跳过** |
 * | 各种订阅弹窗、重启促销 | 弹 | **跳过** |
 *
 * 最后两行在冻结 Google 时格外重要：同意书走的是 UMP SDK，广告走 AdMob/AppLovin，
 * 两者都要 Google 服务。高级版把它们整条跳过，等于顺带把这些依赖也摘了。
 *
 * ## 语言
 *
 * 顺带记一笔，因为容易被当成「解锁没生效」：这个包**不带中文资源**
 * （`aapt2 dump configurations` 只有 de/el/en/es/fa/fr/hi/hr/id/it/ja/ka/ko/nl/pl/pt/ru/sw/tr/vi），
 * 而且 `MvpApp.attachBaseContext` 把语言硬钉成 `"en"`。解锁后能进语言设置页，
 * 但列表里没有中文可选 —— 这是应用自身的资源缺失，不是 hook 的问题。
 */
class GifShopHooker : AppHooker {

    override val id = "gifshop"
    override val displayName = "GIFShop"
    override val description = "GIF 制作工具，解锁终身高级版"
    override val targetPackages = setOf("com.gif.gifmaker")

    /** 判定/写入函数只解析一次，三项功能共用。 */
    @Volatile
    private var refs: PremiumHelperDex.Refs? = null

    /** 存档补写只做一次。 */
    private val seeded = AtomicBoolean(false)

    /** 已经短路过的 BillingClient 实现类，避免同一个类挂两遍。 */
    private val shortCircuited = Collections.synchronizedSet(HashSet<String>())

    /** 日志限流：权益读取在启动期会被调用很多次。 */
    private val logged = AtomicInteger(0)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "解锁终身高级版",
            summary = "让应用认定终身版已购买：导出分辨率上限提到 1280、去掉全部广告、" +
                "开放语言设置。判定只在运行时按住，不修改任何数据，关掉即恢复原样",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "persist",
            title = "解锁写入应用存档",
            summary = "把解锁状态写进应用自己的记录里，之后即使停用本模块也仍然有效；" +
                "同时挡住应用在查不到购买时把它改回未购买",
            install = { installPersist() },
        ),
        HookFeature(
            id = "offline_billing",
            title = "不连 Google 结算",
            summary = "应用对 Google Play 结算的连接与查询就地应答，不再真的发出去。" +
                "冻结或卸载 Google 服务时启动不会卡在重试上；解锁本身不依赖这一项",
            install = { installOfflineBilling() },
        ),
        HookFeature(
            id = "log_premium",
            title = "记录权益判定",
            summary = "排查用：记录应用每次读到的会员状态，用来确认解锁是否生效",
            defaultEnabled = false,
            install = { installPremiumLog() },
        ),
        HookFeature(
            id = "native_file_log",
            title = "记录文件读取",
            summary = "排查用：记录应用读取了哪些本地数据文件。解锁本身不需要原生层，" +
                "这一项只是排查手段",
            defaultEnabled = false,
            requiresRestart = false,
            install = { installNativeFileLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        if (scope.classOrNull(PremiumHelper.PREMIUM_HELPER) == null) {
            scope.log.w("找不到 ${PremiumHelper.PREMIUM_HELPER}，PremiumHelper 可能被换掉了")
            return false
        }
        if (PremiumHelper.getBooleanMethod(scope) == null) {
            scope.log.w("拿不到 ${PremiumHelper.SHARED_PREFS_IMPL}.getBoolean，系统实现不符合预期")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("GIFShop ${scope.versionCode} in ${scope.processName}")
        refs = PremiumHelperDex.resolve(scope)
    }

    // -----------------------------------------------------------------------

    /**
     * 把权益判定按成「已购买」。
     *
     * 两条路一起走，各自补对方的短板：
     *
     * **应用自己的判定函数**（[PremiumHelperDex] 找到的 `Preferences.hasActivePurchase()`）——
     * 最精确的一点，一个方法覆盖全部消费者，进程里别的东西一概不受影响。缺点是名字被
     * R8 重命名过，得靠扫描定位，扫不到就没有。
     *
     * **存储层**（`SharedPreferencesImpl.getBoolean`）—— 完全不需要目标的任何名字：
     * 键名是 PremiumHelper 协议、类名是 Android 框架，跨版本都不变。代价是进程内每次
     * 布尔读取都要过一次字符串比较；长度不同时 `equals` 直接短路，可以忽略。
     *
     * 两条都返回 true，不冲突。判定函数扫不到时，存储层这条独自成立 —— 这也是这里
     * 不给「写死方法名」留退路的原因：写死一个每次构建都会变的字母只会带来误伤。
     */
    private fun HookScope.installLifetime() {
        val verdict = refs?.verdict
        if (verdict != null) {
            verdict.createReturnConstantHook("gifshop.lifetime.verdict", true)
            log.i("权益判定已钉死：${verdict.declaringClass.name}.${verdict.name}()")
        } else {
            log.w("没定位到权益判定函数，只走存储层拦截（功能不受影响）")
        }

        entitlementRead("gifshop.lifetime.pref") { param ->
            if (param.result == true) return@entitlementRead
            param.result = true
        }
        log.i("存储层已拦截 ${PremiumHelper.KEY_ENTITLEMENT}")
    }

    /**
     * 让解锁进到应用自己的存档。
     *
     * 两件事：
     *
     * 1. **挡住回写。** 应用每次查完 Play 都会用查询结果覆盖这个键
     *    （`Preferences.setHasActivePurchase(有没有已购)`）—— 查不到就是 `false`。
     *    把写入的值按成 true，这次覆盖就变成一次确认。
     * 2. **补写一次。** 冻结 Google 时那次查询可能根本不会发生，应用也就永远不会写。
     *    所以在**第一次读到**这个键时，就地用同一个 `SharedPreferences` 实例补一条 ——
     *    能读到它就说明拿到的正是 PremiumHelper 那个文件，不用猜文件名、也不用等
     *    `Context`。
     *
     * 写入端拦的是 `SharedPreferencesImpl$EditorImpl.putBoolean`，和读取端一样不依赖
     * 目标的任何名字。真找不到编辑器实现时只是少一层保护，补写照做。
     */
    private fun HookScope.installPersist() {
        val putBoolean = PremiumHelper.putBooleanMethod(this)
        if (putBoolean == null) {
            log.w("拿不到 ${PremiumHelper.SHARED_PREFS_EDITOR_IMPL}.putBoolean，跳过回写保护")
        } else {
            putBoolean.createBeforeHook("gifshop.persist.write") { param ->
                if (param.arg(0) != PremiumHelper.KEY_ENTITLEMENT) return@createBeforeHook
                if (param.arg(1) == true) return@createBeforeHook
                param.args[1] = true
                log.d("应用想把权益写回未购买，已改成已购买")
            }
        }

        entitlementRead("gifshop.persist.seed") { param ->
            if (!seeded.compareAndSet(false, true)) return@entitlementRead
            val prefs = param.thisObjectOrNull as? SharedPreferences ?: return@entitlementRead
            runCatching { prefs.edit().putBoolean(PremiumHelper.KEY_ENTITLEMENT, true).apply() }
                .onSuccess { log.i("已把解锁补写进 ${PremiumHelper.PREFS_FILE}") }
                .onFailure { log.w("补写解锁失败：${it.message}") }
        }
    }

    /**
     * 把应用对 Google Play 结算的调用就地应答掉。
     *
     * 冻结 Google 之后，`BillingConnection.connect()` 会做 10 次 × 500ms 的退避重试
     * 才放弃，而这件事发生在启动路径上（`PHSplashActivity` 等 PremiumHelper 初始化完成，
     * 上限由 `ph_initialization_timeout_seconds` = 10 秒兜底）。就地应答之后，
     * 启动时间跟有没有 Google 无关。
     *
     * 落点是**实现类**而不是 `BillingClient`：后者是抽象类，钩不上；实现类在 3.1.1 里
     * 被 R8 改名成了 `com.android.billingclient.api.a`，写死同样会漂。稳的是
     * `BillingClient.Builder.build()` —— 那是 Google 的公开 API，从它的**返回值**取
     * 真实实现类，名字一个都不用写。方法名本身不会被改：它们是抽象基类里的公开 API，
     * 实现类只是覆写。
     *
     * 应答的内容是「成功 + 没有任何购买」，不是伪造一笔订单：解锁由 [lifetime] /
     * [persist] 负责，这里只负责**别让应用等**。查询回空会让应用想把权益写回未购买，
     * 那一步由 [persist] 挡住。
     *
     * 副作用讲清楚：开启后应用不再真的连接 Play，**应用内购买流程也就不可用**了。
     * 已经解锁的情况下这条路本来也用不上。
     */
    private fun HookScope.installOfflineBilling() {
        val builder = classOrNull(PremiumHelper.BILLING_CLIENT_BUILDER)
            ?: error("找不到 ${PremiumHelper.BILLING_CLIENT_BUILDER}，Play Billing 库可能被换掉了")
        val build = runCatching { builder.getDeclaredMethod("build") }.getOrNull()
            ?: error("${builder.name} 上没有 build()")

        build.createAfterHook("gifshop.offline.builder") { param ->
            val client = param.result ?: return@createAfterHook
            shortCircuit(client.javaClass)
        }
        log.i("已监视 ${builder.name}.build()，等实现类出现再短路")
    }

    /**
     * 在 `BillingClient` 的真实实现类上装短路。每个类只装一次。
     *
     * 回调一律通过**接口**上的方法反射调用（`BillingClientStateListener` 等都是
     * Google 的公开 API），虚分派会落到实现上 —— 这样就不必关心回调实例本身
     * 被 R8 改成了什么名字。
     */
    private fun HookScope.shortCircuit(clientClass: Class<*>) {
        if (!shortCircuited.add(clientClass.name)) return

        val ok = PremiumHelper.okResult(this) ?: run {
            log.w("造不出 BillingResult，结算短路跳过")
            return
        }
        val empty = emptyList<Any?>()
        val onSetupFinished = listenerMethod(
            "com.android.billingclient.api.BillingClientStateListener",
            "onBillingSetupFinished",
            single = true,
        )
        val onPurchases = listenerMethod(
            "com.android.billingclient.api.PurchasesResponseListener",
            "onQueryPurchasesResponse",
            single = false,
        )
        val onHistory = listenerMethod(
            "com.android.billingclient.api.PurchaseHistoryResponseListener",
            "onPurchaseHistoryResponse",
            single = false,
        )

        var installed = 0
        clientClass.declaredMethods.forEach { method ->
            if (method.isSynthetic || method.isBridge) return@forEach
            val target = when {
                method.name == "startConnection" && method.parameterCount == 1 ->
                    onSetupFinished?.let { it to 0 }

                method.name == "queryPurchasesAsync" && method.parameterCount == 2 ->
                    onPurchases?.let { it to 1 }

                method.name == "queryPurchaseHistoryAsync" && method.parameterCount == 2 ->
                    onHistory?.let { it to 1 }

                else -> null
            } ?: return@forEach

            val (callback, listenerIndex) = target
            runCatching {
                method.createReplaceHook("gifshop.offline.${method.name}.${method.parameterCount}") { param ->
                    val listener = param.args.getOrNull(listenerIndex)
                    if (listener != null) {
                        runCatching {
                            if (callback.parameterCount == 1) callback.invoke(listener, ok)
                            else callback.invoke(listener, ok, empty)
                        }.onFailure { log.w("应答 ${method.name} 失败：${it.message}") }
                    }
                    null
                }
                installed++
            }.onFailure { log.w("短路 ${method.name} 失败：${it.message}") }
        }

        if (installed == 0) log.w("${clientClass.name} 上没找到可短路的入口")
        else log.i("${clientClass.name} 已短路 $installed 个结算入口")
    }

    /**
     * 只读诊断。
     *
     * 判定函数打的是「谁在问、答了什么」；存储层那条是兜底，用来确认扫描没找到判定函数
     * 时解锁仍然生效。启动期读取次数很多，所以限流到前 [LOG_LIMIT] 次。
     */
    private fun HookScope.installPremiumLog() {
        log.i(
            "版本 ${versionCode}，判定函数 " +
                (refs?.verdict?.let { "${it.declaringClass.name}.${it.name}()" } ?: "未定位") +
                "，写入函数 " + (refs?.writer?.let { "${it.name}(boolean)" } ?: "未定位"),
        )

        refs?.verdict?.createAfterHook("gifshop.log.verdict", priority = -100) { param ->
            if (logged.incrementAndGet() > LOG_LIMIT) return@createAfterHook
            log.i("应用读到会员状态 = ${param.result}")
        }

        entitlementRead("gifshop.log.pref", priority = -100) { param ->
            if (logged.incrementAndGet() > LOG_LIMIT) return@entitlementRead
            log.i("存储层读到 ${PremiumHelper.KEY_ENTITLEMENT} = ${param.result}")
        }
    }

    /**
     * Dobby 挂 libc 的 `openat`，记录应用碰了哪些文件。
     *
     * 解锁不需要原生层 —— 这个包的四个 `.so` 全是 GIF 编解码。留这一项是因为它是
     * 「应用到底读了哪个存档」最直接的答案，排查解锁没生效时比翻日志快。
     */
    private fun HookScope.installNativeFileLog() {
        if (!NativeHook.isAvailable) {
            error("原生层不可用：${NativeHook.lastError}")
        }
        NativeHook.setVerbose(true)
        NativeHook.setOpenatFilters("premium_helper", "shared_prefs", "gifmaker", "billing")
        if (!NativeHook.install("openat_logger")) {
            error("openat_logger 没装上")
        }
        log.i("原生文件日志已开启")
    }

    // -----------------------------------------------------------------------

    /**
     * 在 `SharedPreferencesImpl.getBoolean` 上挂一个只认
     * [PremiumHelper.KEY_ENTITLEMENT] 的 after hook。
     *
     * 三项功能都要这一层（改结果 / 补写 / 记日志），键名过滤统一放这里，
     * 免得每处各写一遍还写歪。
     */
    private inline fun HookScope.entitlementRead(
        key: String,
        priority: Int = 0,
        crossinline body: (io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam) -> Unit,
    ) {
        val getBoolean = PremiumHelper.getBooleanMethod(this)
            ?: error("拿不到 ${PremiumHelper.SHARED_PREFS_IMPL}.getBoolean")
        getBoolean.createAfterHook(key, priority = priority) { param ->
            if (param.arg(0) != PremiumHelper.KEY_ENTITLEMENT) return@createAfterHook
            body(param)
        }
    }

    /** 取回调接口上的方法。接口名是 Google 公开 API，虚分派会落到 R8 改过名的实现上。 */
    private fun HookScope.listenerMethod(
        interfaceName: String,
        methodName: String,
        single: Boolean,
    ): Method? {
        val iface = classOrNull(interfaceName) ?: return null
        val result = classOrNull(PremiumHelper.BILLING_RESULT) ?: return null
        return runCatching {
            if (single) iface.getMethod(methodName, result)
            else iface.getMethod(methodName, result, List::class.java)
        }.onFailure { log.d("$interfaceName.$methodName 取不到：${it.message}") }.getOrNull()
    }

    internal companion object {
        /** 判定函数所在类，应急覆盖用（正常由 [PremiumHelperDex] 扫出来）。 */
        const val KEY_VERDICT_CLASS = "verdict_class"
        const val KEY_VERDICT_METHOD = "verdict_method"

        private const val LOG_LIMIT = 20
    }
}
