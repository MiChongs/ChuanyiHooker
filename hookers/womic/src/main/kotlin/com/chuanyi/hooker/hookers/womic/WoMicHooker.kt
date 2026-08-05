package com.chuanyi.hooker.hookers.womic

import android.content.res.Resources
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

/**
 * WO Mic（`com.wo.voice2`）—— 把手机当电脑麦克风用的工具，付费项是 Play 订阅
 * （`premium_monthly` / `premium_yearly`），解锁**两个**功能：
 *
 * 1. **去广告** —— 主界面底部的 AdMob 横幅，以及随之而来的 UMP 同意弹窗
 * 2. **音量调节** —— 主界面那条音量滑杆。未订阅时拖动会被弹回原位并弹订阅对话框；
 *    增益本身是纯 Java 的移位运算（衰减 4 档、增益 4 档），没有原生参与
 *
 * ## 授权是纯本地缓存，没有自证
 *
 * 全包搜不到任何签名校验、没有授权服务器、没有回执复验。判定就一句：
 *
 * ```java
 * settings.purchaseState == 1
 * ```
 *
 * 而 `purchaseState` 来自 `SharedPreferences("settings")`，启动读一次进内存，之后每次
 * 回到前台由 Play 的查询结果覆盖。**唯一一处 RSA 验签在 `onPurchasesUpdated`（新购买）
 * 那条路上，`queryPurchasesAsync` 的回调完全不验签** —— 不过这里连伪造回包都不需要，
 * 直接管住缓存的读与写就够了。
 *
 * ## 三个落点，两个不碰混淆名
 *
 * | 落点 | 位置 | 依赖 |
 * |---|---|---|
 * | 读取端 | `SharedPreferencesImpl.getInt("purchaseState")` | 只依赖键名 |
 * | 持久化 | 同上，借那次读取的实例自己 `edit()` 写回 | 只依赖键名 |
 * | 写入端 | 混淆后的 `(int,String)->void`，拦参数防清零 | [WoMicDex] 按特征串扫 |
 *
 * 前两个跨版本不会失效；第三个失效了只会退化成「模块开着才对」，不会崩。
 *
 * ## Google 服务被冻结时
 *
 * 查询回调的第一行是 `if (billingResult.code != 0) return;` —— 连不上 Play 时它
 * **原样返回，不动缓存**。所以冻结 GMS 之后：
 *
 * * 「写入永久授权缓存」写进磁盘的 1 不会被任何人改回去，模块关掉也照样是付费版
 * * 订阅入口会跳到「已订阅」状态页而不是购买页，不会卡在转圈的商品详情页
 * * 广告 SDK 因为权益成立根本不初始化，不会有连不上 GMS 的重试
 *
 * 冻结前如果已经点开过购买页，Play Billing 会在后台重试连接。那是它自己的行为，
 * 不影响麦克风功能；「原生层文件访问诊断」那一项可以用来确认它到底在读什么。
 */
class WoMicHooker : AppHooker {

    override val id = "womic"
    override val displayName = "WO Mic"
    override val description = "解锁订阅，去广告 + 音量调节，冻结 Google 服务后依然可用"
    override val targetPackages = setOf("com.wo.voice2")

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "unlock",
            title = "解锁全部付费功能",
            summary = "去掉主界面广告，放开音量调节滑杆。" +
                "同时挡住应用每次回到前台向 Google 查询后把状态改回未订阅的那一步",
            install = { installUnlock() },
        ),
        HookFeature(
            id = "persist",
            title = "写入永久授权缓存",
            summary = "把「已订阅」真正写进应用的设置文件。写完之后即使关掉本模块也依然是" +
                "付费版 —— 前提是 Google 服务查不到你的订阅（冻结、停用、或设备无 GMS）。" +
                "Google 服务正常时应用会自己改回去，那时仍需保持上一项开启",
            install = { installPersist() },
        ),
        HookFeature(
            id = "lifetime_label",
            title = "订阅状态显示为永久版",
            summary = "把设置页和订阅状态页里的「已订阅 / 未订阅 / 处理中」统一显示成永久版。" +
                "只改显示文案，不影响功能",
            install = { installLifetimeLabel() },
        ),
        HookFeature(
            id = "billing_trace",
            title = "记录计费链路",
            summary = "排查用：记录订阅状态的每一次读取与写入，看得出状态是从本地缓存来的" +
                "还是被 Google 查询结果改的",
            defaultEnabled = false,
            install = { installTrace() },
        ),
        HookFeature(
            id = "native_probe",
            title = "原生层文件访问诊断",
            summary = "排查用：在原生层记录应用打开的文件路径（只保留设置文件与 Google " +
                "相关的），用来确认冻结 Google 服务之后它还在尝试读什么。" +
                "本应用的授权逻辑全在 Java 层，这一项只用于排查，不参与解锁",
            defaultEnabled = false,
            install = { installNativeProbe() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        // 读取端落在系统类上，这个进程里没有 SharedPreferencesImpl 才是真的没救。
        if (scope.classOrNull(WoMic.PREFS_IMPL) == null) {
            scope.log.w("找不到 ${WoMic.PREFS_IMPL}，本进程不处理")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("WO Mic ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 读取端 + 写入端。
     *
     * 顺序是有讲究的：读取端必须先装，因为写入端的拦截依赖「内存里已经是 1」这个
     * 前提才能起作用 —— 见 [installSinkGuard] 里对 early return 的说明。
     */
    private fun HookScope.installUnlock() {
        Prefs.installGuard(this, productId())
        installSinkGuard()
    }

    /**
     * 拦住把订阅状态改回去的那一步。
     *
     * 写入点的第一行是「新旧值相同就直接返回」：
     *
     * ```java
     * if (newState == settings.purchaseState) return;
     * ```
     *
     * 读取端已经保证内存里是 1，所以这里把入参一律改成 1 之后，应用自己就 early
     * return 了 —— 既没有写磁盘，也没有触发权益 LiveData 的重新广播。**什么都没发生**
     * 正是这里想要的结果：Play 那边查不到订阅时的清零被完整地吞掉。
     *
     * 反过来，如果读取端没装（比如用户只开了这一项），入参改成 1 会让应用把 1 写进
     * 磁盘并广播权益成立 —— 也是对的，只是多绕一步。两种情况都不会坏。
     *
     * 找不到写入点不算失败：读取端仍然管着每次启动，只是运行中被 Play 改掉之后要等
     * 下次启动才回来。所以这里只记一条警告，不抛。
     */
    private fun HookScope.installSinkGuard() {
        val sink = WoMicDex.sink(this)
        if (sink == null) {
            log.w("找不到权益写入点，运行中的状态改写拦不住（每次启动仍然是已订阅）")
            return
        }

        val productId = productId()
        sink.createBeforeHook("womic.sink") { param ->
            val state = param.args.getOrNull(0) as? Int
            val incoming = param.args.getOrNull(1) as? String
            if (state != WoMic.STATE_PURCHASED) {
                log.d("拦下状态改写 $state → ${WoMic.STATE_PURCHASED}")
                param.args[0] = WoMic.STATE_PURCHASED
            }
            if (incoming.isNullOrBlank()) param.args[1] = productId
        }
        log.i("权益写入点已接管：${sink.declaringClass.name}.${sink.name}")
    }

    private fun HookScope.installPersist() = Prefs.installPersist(this, productId())

    /**
     * 把三条订阅状态文案统一换成「永久版」。
     *
     * 走 `Resources.getText(int)` 而不是 `getString(int)`：设置页用的是
     * `Context.getString`（内部就是 `getText(id).toString()`），状态页用的是
     * `TextView.setText(int)`（内部直接走 `getText`）。挂在下面那层，两处一次覆盖。
     *
     * 资源 **id** 每次构建都会变，资源**名**不会（R8 改的是 R 类的引用，`resources.arsc`
     * 里的名字还在）。所以 id 是运行期用 `getIdentifier` 现查的，且推迟到第一次调用
     * ——安装时还没有可用的 [Resources] 实例。
     */
    private fun HookScope.installLifetimeLabel() {
        val label = string(KEY_LABEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL
        val getText = Resources::class.java.declaredMethods
            .firstOrNull { it.name == "getText" && it.parameterCount == 1 }
            ?: error("Resources 上没有 getText(int)")

        // 三个 id 一次解析，之后只做整数比较。-1 表示还没解析过。
        var ids: IntArray? = null

        getText.createAfterHook("womic.label") { param ->
            val id = param.args.getOrNull(0) as? Int ?: return@createAfterHook
            val resources = param.thisObjectOrNull as? Resources ?: return@createAfterHook

            val resolved = ids ?: runCatching {
                intArrayOf(
                    resources.getIdentifier(WoMic.STR_STATE_ACTIVE, "string", packageName),
                    resources.getIdentifier(WoMic.STR_STATE_NONE, "string", packageName),
                    resources.getIdentifier(WoMic.STR_STATE_PENDING, "string", packageName),
                )
            }.getOrDefault(IntArray(3)).also {
                ids = it
                if (it.all { v -> v == 0 }) log.w("三条订阅状态文案的资源名都没查到，文案不会改")
                else log.d("订阅状态文案 id：${it.joinToString()}")
            }

            if (id == 0 || resolved.none { it == id }) return@createAfterHook
            param.result = label
        }
        log.i("订阅状态文案将显示为「$label」")
    }

    private fun HookScope.installTrace() {
        Prefs.installTrace(this)
        WoMicDex.sink(this)?.createAfterHook("womic.trace.sink") { param ->
            log.i("权益写入 state=${param.args.getOrNull(0)} productId=${param.args.getOrNull(1)}")
        } ?: log.d("找不到权益写入点，只追踪存储读写")
    }

    /**
     * 原生层的文件访问记录。
     *
     * **这一项不参与解锁**，写在这里是因为「冻结 Google 服务之后应用还正不正常」这个
     * 问题，光看 Java 侧的日志答不完整：Play Billing 连不上时的重试、GMS 相关的
     * provider 探测，都发生在应用自己的代码之外。
     *
     * 本应用的原生库只有 opus / speex 编解码和一个 Jetpack DataStore 的计数器，
     * 授权链路一个字节都不在原生层 —— 所以这里用的是原生层的**观察**能力，
     * 而不是去改什么。过滤器只留三类路径，否则 logcat 会被刷爆。
     */
    private fun HookScope.installNativeProbe() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())
        NativeHook.setOpenatFilters(*OPENAT_FILTERS)
        if (!NativeHook.install("openat_logger")) error("openat 记录装不上")
        log.i("原生层文件访问诊断已开启，过滤：${OPENAT_FILTERS.joinToString()}")
    }

    // -----------------------------------------------------------------------

    /**
     * 写进缓存的商品 ID。只影响界面显示，不参与判定。
     *
     * 默认年付：应用在状态页只显示「已订阅」，不显示周期，选哪个都一样；真买过月付的
     * 用户读到的是自己那份，这里不会覆盖（见 [Prefs.installGuard]）。
     */
    private fun HookScope.productId(): String =
        string(KEY_PRODUCT_ID)?.takeIf { it.isNotBlank() } ?: WoMic.PRODUCT_YEARLY

    companion object {
        /** 设置项：写进缓存的商品 ID。 */
        const val KEY_PRODUCT_ID = "product_id"

        /** 设置项：订阅状态显示的文案。 */
        const val KEY_LABEL = "label"

        /** 设置项：应急覆盖权益写入点所在的类。 */
        const val KEY_SINK_CLASS = "sink_class"

        /** 设置项：应急覆盖权益写入点的方法名。 */
        const val KEY_SINK_METHOD = "sink_method"

        private const val DEFAULT_LABEL = "永久版"

        /** openat 记录的路径过滤。留设置文件和 Google 两侧，其余噪音全滤掉。 */
        private val OPENAT_FILTERS = arrayOf("settings.xml", "com.android.vending", "com.google.android.gms")
    }
}
