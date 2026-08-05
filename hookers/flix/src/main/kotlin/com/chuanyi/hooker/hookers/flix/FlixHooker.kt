package com.chuanyi.hooker.hookers.flix

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.hookers.flix.DartPatch.Site
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.util.Collections

/**
 * Flix（`com.ifreedomer.flix`）—— Flutter 写的跨设备互传工具，付费档叫 **Flix MAX**。
 *
 * ## 解锁点在哪
 *
 * 判定**一条都不在 Java 层**：`libapp.so` 里 21 MB 的 Dart AOT 代码，`VipService`、
 * `AdsManager`、订阅页全在里面。Java 那 27 MB dex 只是 Flutter 插件的胶水（fluwx、
 * url_launcher、shared_preferences），没有一行会员逻辑。
 *
 * 好消息是这个包**没开 `--obfuscate`**：2270 条 `package:flix/...` 源文件路径、类名、
 * 函数名全是明文，判定链是读出来的而不是猜的。
 *
 * 会员态收敛到一个字段 `VipService._isFlixMax`，而写它的只有一个函数 ——
 * `_setIsFlixMax`。联网校验和离线缓存两条路都以调用它收尾，补这一处，两条路一起变。
 * 落点细节见 [Entitlement]，补丁原语见 [DartPatch]。
 *
 * ## MAX 买的是什么
 *
 * 只有免广告。应用自己在公告里写着「投屏、远程控制等功能已全面免费开放……软件内将加入
 * 少量广告（Flix MAX 用户无广告）」，订阅页的类名也是 `FlixMaxNoAdsSubscribeSheet`。
 * 所以「功能全部可用」在这里等于「会员态成立 + 广告不出现」—— 前者由 [Entitlement.MAX_FLAG]
 * 管，后者由 [Entitlement.NO_ADS] 直接掐掉广告分发，两边独立，不指望一个盖住另一个。
 *
 * ## 微信 / 支付宝这一侧
 *
 * 结算走的是**自家服务端下单 + 轮询查单**，微信和支付宝只是两个付款入口：
 *
 * ```
 * 点订阅 → generate_payment.php 下单
 *        ├── 微信：_generateWxAppPaymentLink → fluwx → 微信 App
 *        └── 支付宝：_generatePaymentLink → url_launcher → alipays:// scheme
 *                          ↓ 付完回到应用
 *              _checkPaymentStatus() 轮询同一个查单接口   ← 两条路在这里合流
 *                          ↓ status == 1
 *              「订阅成功」→ _onPaymentSuccess() → 刷新会员态
 * ```
 *
 * **两个渠道只有一个查单出口**，所以 [Entitlement.PAY_BYPASS] 补那一处就同时覆盖了微信
 * 和支付宝，不用分别去接 fluwx 的 `PayResp` 回调和支付宝的 scheme 返回 —— 那两条路各有
 * 各的形状，还各自依赖对应 App 装没装。
 *
 * 解锁本身**不经过这条链**：[Entitlement.MAX_FLAG] 直接钉会员态，不下单、不联网、不需要
 * 账号。支付相关的两项是给「想让应用内的订阅流程也走得通」准备的，默认都关着。
 *
 * ## 为什么不伪造服务端回包
 *
 * 试过的路和放弃的理由，留给下次改这个模块的人：
 *
 * * **改本地缓存**（`flutter.vipRemainingDays`）：能让离线启动判 true，但一联网
 *   `loadVipStatus` 就用服务端日期重算并覆盖回去。当第二重保险可以（[Prefs]），当主力不行。
 * * **伪造查单响应**：得连 `VipService.makeSign` 的请求签名一起做，而解锁根本不需要走
 *   服务端那条路。补判定比伪造输入省事，也不依赖任何数据格式。
 */
class FlixHooker : AppHooker {

    override val id = "flix"
    override val displayName = "Flix"
    override val description = "跨设备互传，解锁终身 Flix MAX"
    override val targetPackages = setOf("com.ifreedomer.flix")

    /**
     * 这一轮要打的 Dart 补丁。
     *
     * 补丁必须等 `libapp.so` 映射进来才能落，而各个功能开关是在那之前就被逐个装上的。
     * 所以功能的 `install` 只负责**登记**，真正的落点统一交给 [onHooked] 起的那一个
     * 线程 —— 不是每个功能各起一个。
     */
    private val pending = Collections.synchronizedList(ArrayList<Site>())

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime_max",
            title = "解锁终身 Flix MAX",
            summary = "把会员判定钉成「已开通」，全部 MAX 权益立即可用。" +
                "不联网、不需要账号、不写入任何数据，关掉即恢复原样",
            install = { pending += Entitlement.MAX_FLAG },
        ),
        HookFeature(
            id = "no_ads",
            title = "屏蔽广告",
            summary = "MAX 买的就是免广告。这一项直接让广告组件走应用自带的空实现，" +
                "开屏广告不再加载。与上一项各管一头，互不依赖",
            install = { pending += Entitlement.NO_ADS },
        ),
        HookFeature(
            id = "lifetime_days",
            title = "会员有效期显示为永久",
            summary = "把个人中心的「剩 N 天后过期」改成一个极大的天数（默认 36500 天，约 100 年）。" +
                "这个值同时会被写进本地缓存，下次离线启动自己就认会员",
            install = { pending += Entitlement.lifetime(lifetimeDays()) },
        ),
        HookFeature(
            id = "prefs_guard",
            title = "本地会员状态兜底",
            summary = "从本地存储这一侧再兜一道：读到的会员剩余天数不低于设定值。" +
                "不改磁盘数据，只改应用读到的结果。" +
                "作用是应用更新、代码补丁失效时仍然保住会员态",
            install = { Prefs.installGuard(this, lifetimeDays()) },
        ),
        HookFeature(
            id = "pay_bypass",
            title = "支付直通（微信 / 支付宝）",
            summary = "点了订阅之后不必真的付款，查单一律按「已支付」处理，" +
                "照常弹出订阅成功并刷新会员态。微信和支付宝共用同一个查单出口，一并覆盖。" +
                "解锁不需要这一项，默认关闭",
            defaultEnabled = false,
            install = { pending += Entitlement.PAY_BYPASS },
        ),
        HookFeature(
            id = "pay_compat",
            title = "支付方式兼容",
            summary = "设备没装微信或支付宝时，让支付入口照样可选、拉起不再报错，" +
                "配合上一项就能把订阅流程走完。解锁不需要这一项，默认关闭",
            defaultEnabled = false,
            install = { installPayCompat() },
        ),
        HookFeature(
            id = "trace",
            title = "记录会员判定与本地存储（排查用）",
            summary = "把应用读写的会员相关数据打进日志，用来确认会员态是从缓存来的还是服务端来的。" +
                "平时留着会刷屏，默认关闭",
            defaultEnabled = false,
            install = { Prefs.installTrace(this) },
        ),
    )

    /**
     * 版本闸门用的是**补丁落点是否还在**，不是 versionCode 白名单。
     *
     * 钉版本号有两个毛病：目标发个小版本就得跟着出包，而落点其实没动；反过来落点真变了、
     * 版本号却对得上时，它又什么都拦不住。这里只确认「原生层能不能用」——真正的落点检查
     * 在 [DartPatch.locate] 里逐处做，失败就跳过那一处。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        if (!scope.isMainProcess) return false
        if (!NativeHook.isAvailable) {
            scope.log.w("原生层没加载起来（${NativeHook.lastError}），Dart 补丁走不了")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Flix ${scope.versionCode} in ${scope.processName}")
        NativeHook.setVerbose(scope.settings.isVerbose())
    }

    /**
     * 全部功能装完之后，起唯一的一个线程去等 `libapp.so`，然后把登记的补丁一次性打完。
     *
     * Xposed 给我们的时机比 Flutter 引擎加载那个库要早几百毫秒 —— `System.loadLibrary("app")`
     * 由 `FlutterLoader` 在第一个 Activity 前后发起。所以这里不能直接打，得等它出现。
     *
     * 用 [NativeHook.moduleBase] 轮询而不是 hook `dlopen`：便宜、无副作用，且 Flutter 加载
     * 引擎的路径在各版本间比 linker 的内部实现稳定得多。
     *
     * **不用担心赶不上**：Dart 快照的代码段是被 `mmap` 进来的只读镜像，不像 Dart 字符串那样
     * 在 isolate 启动时反序列化进堆。补丁落在镜像上，什么时候打都一样有效 —— 只要赶在用户
     * 点开订阅页或广告要弹之前，而那至少是几秒之后的事。
     */
    override fun onHooked(scope: HookScope, installed: List<HookFeature>) {
        val sites = pending.toList()
        if (sites.isEmpty()) {
            scope.log.d("没有需要打的 Dart 补丁")
            return
        }
        val log = scope.log
        Thread({ awaitImageThenPatch(sites, log) }, "flix-unlock")
            .apply { isDaemon = true }
            .start()
    }

    private fun awaitImageThenPatch(sites: List<Site>, log: HookerLog) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (NativeHook.moduleBase(DartPatch.IMAGE) != 0L) {
                val ok = sites.count { runCatching { Entitlement.install(it, log) }.getOrDefault(false) }
                if (ok == sites.size) {
                    log.i("Flix MAX 已解锁（$ok/${sites.size} 处落点）")
                } else {
                    log.w(
                        "只补上了 $ok/${sites.size} 处落点 —— " +
                            "目标版本可能变了，上面的日志有具体是哪一处",
                    )
                }
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.e("等了 ${LOAD_TIMEOUT_MS / 1000} 秒也没等到 ${DartPatch.IMAGE}，放弃")
    }

    // -----------------------------------------------------------------------

    /**
     * 让两个付款入口在对应 App 缺席时也走得通。
     *
     * 两处都只影响「能不能选、能不能拉起」，跟会员态没有关系；任何一处定位不到都只是记一行
     * 日志 —— 这一项本来就是可选的。
     */
    private fun HookScope.installPayCompat() {
        var done = 0

        // 微信：没装微信时 isWXAppInstalled() 返回 false，支付入口会被判为不可用。
        // 这个方法只服务于「装没装」这一个问题，钉死它不影响别处。
        FlixDex.wechatInstalledCheck(this)?.let { method ->
            method.createReturnConstantHook("flix.pay.wechat", true)
            log.i("微信安装检测已钉为已安装：${method.declaringClass.name}.${method.name}")
            done++
        } ?: log.w("定位不到微信安装检测，跳过（不影响解锁）")

        // 支付宝走的是 alipays:// scheme，没装支付宝时抛 ActivityNotFoundException，
        // 界面上就是那句「无法拉起支付宝，请稍后重试」。
        //
        // 这里**不能**整个钉成 true —— url_launcher 是全局的，用户协议、「了解 Flix MAX」
        // 那些外链走的是同一个方法，一律短路的话它们会变成点了没反应。所以按 URL 过滤：
        // 只有支付链接才假装拉起成功，其余原样交给应用自己处理。
        //
        // before hook 里赋 result 会自动跳过原方法（见 ezhooktool 的 HookParam），所以
        // 支付链接根本不会真的去 startActivity，也就不会抛异常。
        val launchers = FlixDex.urlLaunchers(this)
        if (launchers.isEmpty()) {
            log.w("定位不到 URL 拉起方法，跳过（不影响解锁）")
        } else {
            launchers.forEach { method ->
                method.createBeforeHook("flix.pay.launch.${method.name}") { param ->
                    val url = param.args.firstOrNull() as? String ?: return@createBeforeHook
                    if (!url.isPaymentUrl()) return@createBeforeHook
                    param.result = true
                    log.d("拦下支付跳转：${url.take(80)}")
                }
            }
            log.i("支付跳转已接管：${launchers.joinToString { it.name }}（仅支付链接，其余外链不受影响）")
            done++
        }

        if (done == 0) error("两处支付兼容点都没定位到")
    }

    /**
     * 是不是付款用的链接。
     *
     * 两种形态都要认：支付宝/微信的私有 scheme（`alipays://`、`weixin://`），以及服务端
     * 直接下发的收银台网址（域名里带 `alipay`、`wx.tenpay`）。判宽一点不要紧 —— 判错的
     * 代价只是某个外链没跳转，而这一整项本来就是可选的。
     */
    private fun String.isPaymentUrl(): Boolean {
        val u = lowercase()
        return PAY_SCHEMES.any { u.startsWith(it) } || PAY_HOST_HINTS.any { u.contains(it) }
    }

    /** 会员剩余天数。默认约 100 年，够久到不用再管，又不至于把界面上的数字排版撑爆。 */
    private fun HookScope.lifetimeDays(): Long =
        int(KEY_LIFETIME_DAYS, DEFAULT_LIFETIME_DAYS).coerceIn(1, MAX_LIFETIME_DAYS).toLong()

    private companion object {
        /** 付款专用的私有 scheme。 */
        val PAY_SCHEMES = listOf("alipays://", "alipay://", "weixin://", "wechat://")

        /** 收银台网址里一定会出现的片段。 */
        val PAY_HOST_HINTS = listOf("alipay.com", "wx.tenpay.com", "mclient.alipay")

        const val KEY_LIFETIME_DAYS = "lifetime_days"
        const val DEFAULT_LIFETIME_DAYS = 36_500

        /** 再大就该担心目标那边算日期时溢出了，没有必要。 */
        const val MAX_LIFETIME_DAYS = 999_999

        /** libapp.so 出现的等待上限。冷启动到 Flutter 引擎就绪通常在 1 秒内。 */
        const val LOAD_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
