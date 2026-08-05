package com.chuanyi.hooker.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.data.ColorSource
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.data.ThemeMode
import com.chuanyi.hooker.data.UiPreferences
import com.chuanyi.hooker.ui.LocalUiPreferences
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.component.isBlurSupported
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.window.WindowDialog

private val ThemeModeLabels = mapOf(
    ThemeMode.System to "跟随系统",
    ThemeMode.Light to "浅色",
    ThemeMode.Dark to "深色",
)

private val ColorSourceLabels = mapOf(
    ColorSource.Default to "默认",
    ColorSource.Wallpaper to "跟随壁纸",
    ColorSource.Custom to "自定义",
)

/**
 * 调色板风格。名字是 Material 的术语，直译过来没人看得懂，按**观感**给的中文。
 *
 * 顺序跟 [ThemePaletteStyle] 的声明顺序一致 —— 下拉框按序号回填，错位就选错了。
 */
private val PaletteStyleLabels = mapOf(
    ThemePaletteStyle.TonalSpot to "标准",
    ThemePaletteStyle.Neutral to "中性",
    ThemePaletteStyle.Vibrant to "鲜艳",
    ThemePaletteStyle.Expressive to "活泼",
    ThemePaletteStyle.Rainbow to "彩虹",
    ThemePaletteStyle.FruitSalad to "缤纷",
    ThemePaletteStyle.Monochrome to "单色",
    ThemePaletteStyle.Fidelity to "忠实原色",
    ThemePaletteStyle.Content to "跟随内容",
)

private val ColorSpecLabels = mapOf(
    ThemeColorSpec.Spec2021 to "2021",
    ThemeColorSpec.Spec2025 to "2025",
)

/** 只有这四种风格实现了 2025 规范，其余会在运行期被 miuix 悄悄降回 2021。 */
private val Spec2025Styles = setOf(
    ThemePaletteStyle.TonalSpot,
    ThemePaletteStyle.Neutral,
    ThemePaletteStyle.Vibrant,
    ThemePaletteStyle.Expressive,
)

/**
 * 设置页。
 *
 * 主题相关的几项彼此有依赖，用 `enabled` 表达而不是把行藏起来 —— 藏起来的话，用户
 * 看不出「调色板风格」的存在，也就不知道要先把取色改掉才能用它。
 *
 * 改动即时生效：偏好本身是 snapshot state（见 [UiPreferences]），主题在
 * [com.chuanyi.hooker.ui.HookerTheme] 里读它，所以这一页不需要「保存」按钮，也不需要
 * 重启 Activity。
 */
@Composable
fun SettingsScreen(settings: ModuleSettings) {
    val navigator = LocalNavigator.current
    val ui = LocalUiPreferences.current
    val scrollBehavior = MiuixScrollBehavior()

    // 老设备上没有系统取色这一项。存着的值要是恰好是它（换机、恢复备份），静默退回默认，
    // 否则界面显示「默认」而实际走的是 Monet 的兜底紫。
    val colorSources = remember {
        ColorSource.entries.filter { it != ColorSource.Wallpaper || UiPreferences.canFollowWallpaper }
    }
    LaunchedEffect(colorSources) {
        if (ui.colorSource !in colorSources) ui.colorSource = ColorSource.Default
    }

    val blurSupported = remember { isBlurSupported() }
    val revision = settings.revision
    val verboseLog = remember(revision) { settings.verboseLog }

    var showColorPicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = "设置",
                onBack = { navigator.pop() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            item { SmallTitle("外观") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = "深浅模式",
                        items = ThemeMode.entries.map { ThemeModeLabels.getValue(it) },
                        selectedIndex = ThemeMode.entries.indexOf(ui.themeMode),
                        onSelectedIndexChange = { ui.themeMode = ThemeMode.entries[it] },
                    )
                    SwitchPreference(
                        title = "纯黑背景",
                        checked = ui.pureBlack,
                        onCheckedChange = { ui.pureBlack = it },
                        // 浅色下这个开关没有任何效果，与其让它看起来能点，不如置灰。
                        enabled = ui.themeMode != ThemeMode.Light,
                    )
                }
            }

            item { SmallTitle("配色") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = "取色",
                        summary = "跟随壁纸需要 Android 12".takeIf { !UiPreferences.canFollowWallpaper },
                        items = colorSources.map { ColorSourceLabels.getValue(it) },
                        selectedIndex = colorSources.indexOf(ui.colorSource).coerceAtLeast(0),
                        onSelectedIndexChange = { ui.colorSource = colorSources[it] },
                    )
                    ArrowPreference(
                        title = "主题色",
                        enabled = ui.colorSource == ColorSource.Custom,
                        endActions = { ColorSwatch(Color(ui.seedColor)) },
                        onClick = { showColorPicker = true },
                    )
                    WindowDropdownPreference(
                        title = "调色板风格",
                        items = ThemePaletteStyle.entries.map { PaletteStyleLabels.getValue(it) },
                        selectedIndex = ThemePaletteStyle.entries.indexOf(ui.paletteStyle),
                        onSelectedIndexChange = { ui.paletteStyle = ThemePaletteStyle.entries[it] },
                        enabled = ui.colorSource != ColorSource.Default,
                    )
                    WindowDropdownPreference(
                        title = "色彩规范",
                        // 选了不生效的情况必须说，否则表现就是「改了没反应」。其余时候不写
                        // 副标题 —— 每行都挂一句解释，这一页就变成说明书了。
                        summary = "当前风格没有 2025，实际按 2021 出图".takeIf {
                            ui.colorSpec == ThemeColorSpec.Spec2025 &&
                                ui.paletteStyle !in Spec2025Styles
                        },
                        items = ThemeColorSpec.entries.map { ColorSpecLabels.getValue(it) },
                        selectedIndex = ThemeColorSpec.entries.indexOf(ui.colorSpec),
                        onSelectedIndexChange = { ui.colorSpec = ThemeColorSpec.entries[it] },
                        enabled = ui.colorSource != ColorSource.Default,
                    )
                }
            }

            item { SmallTitle("界面") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "毛玻璃",
                        summary = "需要 Android 13".takeIf { !blurSupported },
                        checked = ui.blurEnabled && blurSupported,
                        onCheckedChange = { ui.blurEnabled = it },
                        enabled = blurSupported,
                    )
                    WindowDropdownPreference(
                        title = "启动页签",
                        items = HomeTab.entries.map { it.title },
                        selectedIndex = ui.startTab.coerceIn(HomeTab.entries.indices),
                        onSelectedIndexChange = { ui.startTab = it },
                    )
                }
            }

            item { SmallTitle("排查") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = "详细日志",
                        summary = "排查完记得关",
                        checked = verboseLog,
                        onCheckedChange = { settings.verboseLog = it },
                    )
                }
            }

            item { SmallTitle("重置") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "恢复默认外观",
                        onClick = { showResetConfirm = true },
                    )
                }
            }

            item { FooterNote("日志用 logcat 看，标签 ChuanyiHooker。") }
        }
    }

    SeedColorDialog(
        show = showColorPicker,
        initial = Color(ui.seedColor),
        onDismiss = { showColorPicker = false },
        onConfirm = {
            ui.seedColor = it.toArgb()
            showColorPicker = false
        },
    )

    ConfirmDialog(
        show = showResetConfirm,
        title = "恢复默认外观",
        summary = "外观设置全部回到初始值。",
        confirmText = "恢复",
        onDismiss = { showResetConfirm = false },
        onConfirm = {
            ui.resetToDefaults()
            showResetConfirm = false
        },
    )
}

/**
 * 行尾那一小块颜色。
 *
 * 用 miuix 的 `squircleBackground` 而不是 `background(color, RoundedCornerShape(8.dp))`：
 * 连续曲率圆角，跟应用图标和卡片是同一套形状。它自己会在 RuntimeShader 不可用（低于
 * API 33）时退回普通圆角，不用再判一次。
 */
@Composable
private fun ColorSwatch(color: Color) {
    Box(modifier = Modifier.size(24.dp).squircleBackground(color = color, cornerRadius = 8.dp))
}

/**
 * 主题色取色盘。
 *
 * 用 miuix 的 [ColorPalette]（色相 × 明度的网格）而不是 `ColorPicker`（HSV 三条滑杆 +
 * 透明度）：这里挑的是一颗**种子**，整套配色由算法从它推导，滑到小数位的精度没有意义，
 * 而透明度对种子色更是无意义 —— 网格点一下就好。
 *
 * 选中的颜色先落在草稿上，点「使用」才写进偏好：取色盘拖动时会连续回调，直接写的话
 * 整个应用会跟着手指闪成幻灯片。
 */
@Composable
private fun SeedColorDialog(
    show: Boolean,
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var draft by remember(initial, show) { mutableStateOf(initial) }

    WindowDialog(
        show = show,
        title = "主题色",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ColorPalette(
                color = draft,
                onColorChanged = { draft = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "使用",
                    onClick = { onConfirm(draft) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 两个按钮的确认弹窗。 */
@Composable
private fun ConfirmDialog(
    show: Boolean,
    title: String,
    summary: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
