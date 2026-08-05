package com.chuanyi.hooker.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.chuanyi.hooker.data.ColorSource
import com.chuanyi.hooker.data.ThemeMode
import com.chuanyi.hooker.data.UiPreferences
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 界面偏好。整棵树都能读到，[com.chuanyi.hooker.ui.component.BlurScaffold] 这种
 * 深埋在页面里的组件不必一路传参。
 */
val LocalUiPreferences = staticCompositionLocalOf<UiPreferences> {
    error("LocalUiPreferences 没有提供 —— 界面必须套在 HookerTheme 里")
}

/**
 * 应用主题。
 *
 * ## 配色是怎么算出来的
 *
 * miuix 的 [ThemeController] 把「深浅」和「配色从哪来」合成一个 [ColorSchemeMode]。这里
 * 先自己把深浅解析成一个布尔值，再选择显式的 Light/Dark/MonetLight/MonetDark ——
 * 不用 `System` / `MonetSystem` 那两档是因为跟随系统的判断这里已经做过一次了，交给它
 * 再判一次的话，「强制浅色」就没法在深色系统上生效。
 *
 * Monet 档下 `keyColor` 决定走哪条路：
 *
 *  * 传 null  → `platformDynamicColors`，读系统调色板（12 起是 `system_*` 颜色角色，
 *    13 起直接读 `theme_customization_overlay_packages` 里的种子色和风格）
 *  * 传颜色  → materialkolor 按种子色 + 调色板风格 + 色彩规范现算一整套
 *
 * ## 为什么是 `MiuixTheme(colors = …)` 而不是 `MiuixTheme(controller = …)`
 *
 * 后者会把 `currentColors()` 的结果直接铺下去，中间插不进手 —— 而纯黑深色要在配色**算完
 * 之后**改一个字段。所以这里自己调 `currentColors()`，改完再交给取 colors 的那个重载。
 * 代价只有一个 `LocalColorSchemeMode`（controller 那条路会额外提供它）：整个 miuix 里除了
 * `MiuixTheme.isDynamicColor` 这个便利属性之外没有任何组件读它，而那个属性本模块不用。
 *
 * 纯黑只改 `background` 一个字段：卡片、栏、弹窗用的是 `surfaceContainer` / `surface`，
 * 保持原样它们才在纯黑底上分得出层次；Monet 配色里那几个容器色带着壁纸的色相，一并压黑
 * 等于把取色的效果抹掉。
 */
@Composable
fun HookerTheme(
    ui: UiPreferences,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (ui.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val controller = remember(
        dark,
        ui.colorSource,
        ui.seedColor,
        ui.paletteStyle,
        ui.colorSpec,
    ) {
        ThemeController(
            colorSchemeMode = when (ui.colorSource) {
                ColorSource.Default -> if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light
                else -> if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
            },
            keyColor = Color(ui.seedColor).takeIf { ui.colorSource == ColorSource.Custom },
            colorSpec = ui.colorSpec,
            paletteStyle = ui.paletteStyle,
        )
    }

    // 不套 remember：Monet 档下 currentColors() 每次组合都会新算一个 Colors（miuix 自己
    // 就是这么设计的，MiuixTheme 内部持有一个实例、逐字段更新过去），拿它当 remember 的
    // key 只会让缓存永远命不中，白搭一层。
    val colors = controller.currentColors()
    val themedColors = if (dark && ui.pureBlack) colors.copy(background = Color.Black) else colors

    // 系统栏图标的明暗。不跟着走的话，深色系统上强制浅色主题会得到「白底白图标」——
    // 状态栏时间和信号格当场消失。enableEdgeToEdge() 只在 Activity 创建时按系统深浅设
    // 一次，改主题不会回头通知它。
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MiuixTheme(colors = themedColors) {
        CompositionLocalProvider(LocalUiPreferences provides ui, content = content)
    }
}
