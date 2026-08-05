package com.chuanyi.hooker.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 全部导航目的地。
 *
 * Navigation 3 的返回栈会被序列化保存（进程被杀后恢复），所以整条路由层级必须
 * `@Serializable`；带参数的目的地直接把参数写成构造参数，这就是 nav3 的类型安全
 * 传参方式，不需要 Bundle，也不需要字符串拼接。
 *
 * 用 `data object` / `data class` 是硬性要求：nav3 以路由实例本身作为 contentKey，
 * 靠 `equals` 判定是不是同一个页面，靠 `toString` 给 `rememberSaveable` 状态
 * 分命名空间。默认的身份 `toString()`（`pkg.Cls@1a2b3c`）在进程重建后会变，
 * 页面内的 `rememberSaveable` 状态就被悄悄清空了。
 *
 * 新增页面的步骤：这里加一个 `@Serializable data object/data class`，
 * 在 [HookerNavHost] 的 `SerializersModule` 里 `subclass(...)` 注册，
 * 再在 `entryProvider` 里加一个 `entry<...> { }`。三处，缺一不可。
 */
@Serializable
sealed interface Route : NavKey {

    /** 首页宿主：底部导航栏 + 三个平级页签。 */
    @Serializable
    data object Home : Route

    /** 某个 hooker 的详情（功能开关列表）。 */
    @Serializable
    data class HookerDetail(val hookerId: String) : Route

    /** 设置：主题、界面、排查开关。 */
    @Serializable
    data object Settings : Route

    /** 原生层（Dobby）详情。 */
    @Serializable
    data object NativeLayer : Route

    /** 赞赏：收款地址与二维码。 */
    @Serializable
    data object Donate : Route

    /** 开源许可：本应用用到的第三方依赖一览。 */
    @Serializable
    data object Licenses : Route

    /**
     * 单个依赖的详情与许可全文。
     *
     * 参数是 AboutLibraries 的 `uniqueId`（`group:artifact`，不含版本号），而不是
     * 列表下标 —— 下标会随依赖增删漂移，进程重建后恢复出来就指到别的库上了。
     */
    @Serializable
    data class LibraryLicense(val uniqueId: String) : Route
}
