package com.chuanyi.hooker.hookers.osmin

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.osmin.Entitlement.installAuthTrace
import com.chuanyi.hooker.hookers.osmin.Entitlement.installLifetimeTier
import com.chuanyi.hooker.hookers.osmin.Entitlement.installUnlock

/**
 * OS々（`org.chsi.min`）—— ColorOS 界面增强，自身也是个 Xposed 模块。
 *
 * ## 这个目标是怎么破的
 *
 * 授权体系看起来不小：GitHub / 邮箱登录、点数、试用、月授权、永久绑定、邀请返点、
 * 服务端下发状态、设备绑定哈希。但**判定全部压在两个静态方法上**，而且都在
 * 应用进程里：
 *
 * ```
 * i2(tier)  = tier ∈ {月授权, 永久绑定, 试用}      ← 唯一的「算不算已授权」
 * S2(ctx, key, value)                              ← 唯一的权益写入通道
 *     ├─ 本地 module_settings
 *     └─ 框架托管的远端副本                        ← 被注入的进程只读得到这份
 * ```
 *
 * 付费功能真正生效的地方在 SystemUI 和一批 ColorOS 包里，那一侧的判定是
 *
 * ```
 * auth_active && <该功能自己的开关>
 * ```
 *
 * 完整地列一遍它看的东西：**没有了**。不校验设备指纹、不看有效期、不验签名，
 * 就读一个布尔。于是整条链的支点只有 `auth_active` 一个键。
 *
 * ## 为什么注入范围只有目标应用自己
 *
 * 那十个 ColorOS 进程一个都不用进。目标的类在它自己的 `LspModuleClassLoader` 里，
 * 我们在 SystemUI 拿到的是 SystemUI 的 classloader，压根看不见它；而权益值存在
 * 框架托管的远端配置里，**写它的权限在应用进程手上**。所以在应用进程里把那个布尔
 * 写成 true，另外十个进程照常读到已授权 —— 注入面收到最小。
 *
 * ## 免登录
 *
 * 等级判定的入参是账号等级，与登录状态无关：未登录时等级是「未授权」，判定被
 * 常量化后照样返回 true。而未登录**必定**会走到「把权益写回 false」那条路，
 * 那一处正好被写入拦截接住。所以免登录不需要伪造令牌，也不需要额外开关 ——
 * 它是解锁的自然结果。
 *
 * ## 定位与原生层
 *
 * 那两个静态方法在 v0.3.1 里叫 `zq0.i2` / `zq0.S2`，名字每次构建都会变，
 * 走 DexKit 按特征串找（见 [OsMinDex]）。等级判定还做了行为复核 ——
 * 拿三个等级名调一遍看返回值，证明的是「就是它」而不是「长得像」。
 *
 * **没有用到原生层**：目标是纯 Kotlin/Compose，授权链路上没有一行原生代码，
 * 也没有加固、反调试或完整性校验。Dobby 在这里没有可解决的问题。
 */
class OsMinHooker : AppHooker {

    override val id = "osmin"
    override val displayName = "OS々"
    override val description = "ColorOS 界面增强，解锁全部付费功能"
    override val targetPackages = setOf(OsMin.PKG)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "unlock",
            title = "解锁全部功能",
            summary = "流体云、调色盘、充电动画等付费项一律按已授权处理，" +
                "并把授权状态写入跨进程配置，系统界面侧同步生效。未登录亦可使用",
            install = { installUnlock() },
        ),
        HookFeature(
            id = "lifetime",
            title = "显示为永久授权",
            summary = "把账号页的等级显示为永久绑定。仅改显示，不写入账号数据",
            install = { installLifetimeTier() },
        ),
        HookFeature(
            id = "trace",
            title = "记录授权判定",
            summary = "排查用，记录等级判定与权益开关的每一次读写",
            defaultEnabled = false,
            install = { installAuthTrace() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        if (scope.classOrNull(OsMin.APP) == null) {
            scope.log.w("找不到 ${OsMin.APP}，应用可能已更新")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("OS々 ${scope.versionCode} in ${scope.processName}")
    }
}
