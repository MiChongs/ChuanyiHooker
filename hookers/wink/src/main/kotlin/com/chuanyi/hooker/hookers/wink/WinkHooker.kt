package com.chuanyi.hooker.hookers.wink

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wink（`com.meitu.wink`）—— 美图的视频剪辑应用，会员分 VIP 与 SVIP 两级。
 *
 * ## 它的会员是怎么判的
 *
 * 服务端下发一份会员信息（`VipInfoData`），客户端把它折成一个**位掩码**，此后全 app
 * 的会员判断都只看这个掩码：
 *
 * ```
 * 会员类型(信息)Ⅰ   4 = VIP，12 = SVIP(4|8)，1 = 过期，2 = 从来不是
 *      ↓
 *  isVipUser()      (掩码 & 4)  == 4
 *  isSVIPUser()     (掩码 & 12) == 12
 *      ↓
 *  设置页 / 我的页 / 桌面入口 / 编辑器素材锁 / 导出档位 / 广告开关
 * ```
 *
 * **那个折算函数是全 app 唯一的计算点**，所以主落点只有一个。实测接管它之后：
 *
 * | 面 | 结果 |
 * |---|---|
 * | 我的页 | 变成 Wink SVIP 卡片 |
 * | 编辑器 | 会员等级从 1 变成 6（SVIP），素材不再带锁 |
 * | 素材 / 滤镜 / 特效 | 「该不该弹会员提示」全程返回 false |
 * | 导出 | 无水印、无片尾，2K / 4K / 60fps 档位全开 |
 * | 广告 | 广告标记跟着会员状态置成「不展示」 |
 *
 * 另外两个判定方法（`isVipUser` / `isSVIPUser`）自己会先查「会员模块初始化了没」，
 * 没初始化就直接返回 false —— 那一小段窗口里折算函数根本不会被调到，所以它们也各挂一处。
 *
 * ## 有效期为什么要单独补
 *
 * 判定归判定，界面上那句「会员有效期至 …」读的是会员信息实体里的 `invalid_time`。
 * 服务端对非会员不下发这个值，于是解锁之后卡片上会写着 **1970/01/01**。补法见 [VipInfo]：
 * 按 `@SerializedName` 定位字段，就地写进目标自己那个对象 —— 顺带把 `is_vip` /
 * `use_vip` 也置上，于是**原生那套折算逻辑自己就能算出「已开通」**，即使某条路绕过了
 * 模块的 hook，答案依然一致。
 *
 * 只在原值为 0（服务端压根没给）时才写：真付费用户的到期时间是真的，不该被改成 2099 年。
 *
 * ## 为什么没有用 Dobby
 *
 * 这个包带了七十多个 `.so`，但会员判定从头到尾都在托管层：折算、判定、信息实体、
 * 存储全是 Java/Kotlin，原生库里没有任何一处参与授权（`libdexvmp.so` 那套 DEX 保护
 * 也没盖到这条链上 —— 盖到了的话这些方法根本无法反编译，更不可能被 Java hook 接管）。
 * 目标也不做 Xposed / Frida 检测。既然没有原生的门要开，就不引 `:native`。
 *
 * ## 云端 AI 功能的边界
 *
 * 画质修复、AI 超清这类是**上传到服务端算**的。解锁之后客户端不再拦截，请求能正常发出
 * 并被受理，但最终给不给结果由服务端决定 —— 本地 hook 管不到那一侧。带「美豆」角标的
 * 项目走的是消耗品余额，同理。这两类不在本 hooker 的承诺范围内，其余本地能力（剪辑、
 * 滤镜、特效、贴纸、导出档位、去水印）都是客户端判定，实测全部可用。
 */
class WinkHooker : AppHooker {

    override val id = "wink"
    override val displayName = "Wink"
    override val description = "解锁 SVIP，会员有效期显示为永久"
    override val targetPackages = setOf("com.meitu.wink")

    /** 在 [isCompatible] 里解析一次，那时候还没有任何东西装上去。 */
    @Volatile
    private var refs: WinkDex.Refs? = null

    /** 会员信息的字段对照表，拿不到就只解锁不补有效期。 */
    @Volatile
    private var vipInfo: VipInfo? = null

    private val verdictInstalled = AtomicBoolean(false)
    private val grantLogged = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = FEATURE_UNLOCK,
            title = "解锁会员",
            summary = "接管会员类型的判定，让应用认定会员已开通。" +
                "编辑器的素材锁、导出档位（2K / 4K / 60 帧）、无水印导出、去广告全部跟着放开。" +
                "不往应用的存储里写任何东西，关掉就恢复原样",
            install = { installUnlock() },
        ),
    )

    override val options: List<HookOption> = listOf(
        HookOption.Choice(
            key = KEY_LEVEL,
            title = "会员等级",
            summary = "超级会员比普通会员多一档权益，应用里有些入口只认前者",
            featureId = FEATURE_UNLOCK,
            default = LEVEL_SVIP,
            entries = listOf(
                HookOption.Choice.Entry("超级会员（SVIP）", LEVEL_SVIP),
                HookOption.Choice.Entry("普通会员（VIP）", LEVEL_VIP),
            ),
        ),
        HookOption.Choice(
            key = KEY_EXPIRY_YEARS,
            title = "会员有效期",
            summary = "服务端不会给非会员下发到期时间，不补的话「我的」页会写成 1970/01/01。" +
                "补的值只写进内存里那份会员信息，不落盘；已经是真会员时不覆盖",
            featureId = FEATURE_UNLOCK,
            default = EXPIRY_FOREVER,
            entries = listOf(
                HookOption.Choice.Entry("永久（${VipInfo.FOREVER_YEAR} 年）", EXPIRY_FOREVER),
                HookOption.Choice.Entry("1 年后", 1),
                HookOption.Choice.Entry("3 年后", 3),
                HookOption.Choice.Entry("10 年后", 10),
                HookOption.Choice.Entry("不修改（保留 1970/01/01）", EXPIRY_KEEP),
            ),
            custom = HookOption.Number(
                key = KEY_EXPIRY_YEARS,
                title = "自定义有效期",
                default = 1,
                min = 1,
                max = 50,
                unit = " 年后",
            ),
        ),
    )

    /**
     * 只在主进程动手。
     *
     * Wink 另外还有四个进程（崩溃上报、下载、推送、埋点），它们既不画界面也不查会员，
     * 在那里跑一次 107 MB 的 dex 扫描纯属浪费。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        if (!scope.isMainProcess) return false
        if (scope.classOrNull(PROBE_CLASS) == null) {
            scope.log.w("找不到 $PROBE_CLASS，Wink 可能换了会员模块的结构")
            return false
        }
        val resolved = WinkDex.resolve(scope)
        if (resolved.isEmpty) {
            scope.log.w("没在 dex 里定位到会员类型计算 —— 版本 ${scope.versionCode} 可能改了结构")
        } else {
            scope.log.d("会员体系定位到：$resolved")
        }
        refs = resolved
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Wink ${scope.versionCode} 进程 ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 一处接管，三个落点。
     *
     * 折算函数是主落点，另外两个判定方法补的是它们各自的短路分支：会员模块还没初始化
     * 时它们不查掩码就直接答 false，那一小段窗口靠常量顶上。
     *
     * `isSVIPUser` 给的是 `等级 == SVIP` 而不是恒真 —— 用户选了「普通会员」时它必须
     * 答 false，否则两个方法会给出互相矛盾的答案（掩码 4 说不是超级会员，方法说是）。
     */
    private fun HookScope.installUnlock() {
        if (!verdictInstalled.compareAndSet(false, true)) return

        val refs = refs ?: error("refs 尚未解析；isCompatible 一定先于任何功能安装运行")
        val vipType = refs.vipType
        if (vipType == null) {
            log.w("没定位到会员类型计算方法，解锁无法生效")
            return
        }

        val level = when (int(KEY_LEVEL, LEVEL_SVIP)) {
            LEVEL_VIP -> LEVEL_VIP
            else -> LEVEL_SVIP
        }

        // 有效期是「顺带修正」，拿不到字段对照表只影响那一行文案，不影响解锁本身。
        val years = int(KEY_EXPIRY_YEARS, EXPIRY_FOREVER)
        val binder = if (years == EXPIRY_KEEP) {
            null
        } else {
            refs.vipInfoClass?.let { VipInfo.of(this, it) }.also { vipInfo = it }
        }
        val expiryMillis = if (binder == null) 0L else VipInfo.expiryMillis(years)

        vipType.createInterceptHook("wink.vip_type") { chain ->
            // 目标正要拿这份信息去折算，此刻改它，同一次调用里就能读到新值 ——
            // 界面那句「会员有效期至 …」正是紧接着这一步渲染的。
            val info = chain.getArg(0)
            if (binder != null && info != null && binder.grant(info, expiryMillis)) {
                if (grantLogged.compareAndSet(false, true)) {
                    log.i("已补上会员有效期：${renderExpiry(years)}")
                }
            }
            level
        }
        log.i("会员类型已接管：${vipType.declaringClass.name}.${vipType.name} -> $level（${levelName(level)}）")

        // 这两个只在「会员模块尚未初始化」时才轮得到，装上是为了那一小段窗口里
        // 答案也一致；定位不到就算了，主落点已经覆盖了绝大多数路径。
        refs.isVip?.createReturnConstantHook("wink.is_vip", true)
            ?: log.d("没定位到 isVipUser，初始化前的窗口不覆盖")
        refs.isSvip?.createReturnConstantHook("wink.is_svip", level == LEVEL_SVIP)
            ?: log.d("没定位到 isSVIPUser，初始化前的窗口不覆盖")
    }

    private fun levelName(level: Int): String =
        if (level == LEVEL_SVIP) "超级会员" else "普通会员"

    private fun renderExpiry(years: Int): String =
        if (years <= 0) "${VipInfo.FOREVER_YEAR} 年（永久）" else "$years 年后"

    private companion object {
        const val FEATURE_UNLOCK = "unlock_vip"

        const val KEY_LEVEL = "vip_level"
        const val KEY_EXPIRY_YEARS = "expiry_years"

        /** 目标自己的位掩码取值：4 = VIP，12 = SVIP（4|8）。 */
        const val LEVEL_VIP = 4
        const val LEVEL_SVIP = 12

        /** 有效期档位里的两个哨兵值。 */
        const val EXPIRY_FOREVER = 0
        const val EXPIRY_KEEP = -1

        /**
         * 兼容性探针。会员代理是 Wink 自己的模块边界类，名字没有被混淆过，
         * 拿它判断「这还是那个会员体系吗」比任何两个字母的类名都可靠。
         */
        const val PROBE_CLASS = "com.meitu.wink.vip.proxy.ModularVipSubProxy"
    }
}
