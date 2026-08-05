package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏：标题 + 可选的返回按钮 + 可选的右侧动作。
 *
 * 背景固定透明，底色和模糊由 [BlurScaffold] 铺在它下面的渐进式蒙层负责。
 *
 * [onBack] 为 null 时不画返回按钮（首页用）。传了 [scrollBehavior] 就自带 miuix
 * 的大标题折叠动画，各页面只要把 `nestedScroll` 接上即可。
 */
@Composable
fun HookerTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    scrollBehavior: ScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        color = Color.Transparent,
        subtitle = subtitle,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            if (onBack != null) BackNavigationIcon(onClick = onBack)
        },
        actions = actions,
    )
}

@Composable
fun BackNavigationIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(modifier = modifier, onClick = onClick) {
        Icon(
            // 返回箭头在 RTL 下要翻过来。
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}
