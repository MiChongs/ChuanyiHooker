package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chuanyi.hooker.ui.LocalUiPreferences
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏和底栏都套上毛玻璃的 Scaffold。
 *
 * 三步走，顺序不能乱：
 *
 * 1. [rememberLayerBackdrop] 建一个采样层。它的 `onDraw` 里先 `drawRect` 铺一层
 *    不透明底色再 `drawContent()` —— 采样层只记录它所修饰的 composable 画的东西，
 *    不含 Scaffold 自己的背景。少了这一笔，内容里的透明区域（比如没有背景的文字
 *    行间）会在模糊时把周围颜色晕开，糊成一块块色斑。
 * 2. body 用 [layerBackdrop] 标记为被采样的内容。
 * 3. 两条栏用 [ProgressiveBlurScrim] 消费这个采样层。
 *
 * body 是整屏的，栏浮在它上面，所以内容滚动时会从栏底下经过——这正是模糊要糊的
 * 东西。各页面的 `contentPadding` 已经把栏高让出来了，首屏不会被挡。
 *
 * ## 硬件不支持、或者用户自己关掉时，一层都不建
 *
 * 支持位要**两个都查**（参考实现 `rememberBlurBackdrop` 的做法）：`RenderEffect` 是
 * API 31 起，`RuntimeShader` 是 API 33 起，miuix 的毛玻璃两个都要。查下来不支持、或者
 * 设置页里把它关了，就让 [rememberBlurBackdrop] 返回 null，此时**连采样层都不建**、
 * body 也不挂 [layerBackdrop] —— 那个采样层是一个整屏 GraphicsLayer，每帧要把 body
 * 重录一遍，建了却用不上纯属白烧。栏退化成不透明底色。
 */
@Composable
fun BlurScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    topBarMaxBlurRadius: Float = 28f,
    bottomBarMaxBlurRadius: Float = 32f,
    content: @Composable (PaddingValues) -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberBlurBackdrop(surface)

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurBar(
                backdrop = backdrop,
                edge = BlurEdge.Top,
                maxRadius = topBarMaxBlurRadius,
                content = topBar,
            )
        },
        bottomBar = {
            if (bottomBar != null) {
                BlurBar(
                    backdrop = backdrop,
                    edge = BlurEdge.Bottom,
                    maxRadius = bottomBarMaxBlurRadius,
                    content = bottomBar,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            content(padding)
        }
    }
}

/**
 * 这台设备能不能画毛玻璃。
 *
 * 两个都要：`RenderEffect` 是 API 31 起，`RuntimeShader` 是 API 33 起。设置页拿它决定
 * 那个开关是置灰还是可点 —— 不能让用户打开一个物理上不会发生的东西。
 */
fun isBlurSupported(): Boolean = isRenderEffectSupported() && isRuntimeShaderSupported()

/**
 * 毛玻璃的采样层，硬件不支持或用户关掉时返回 null。
 *
 * 抄参考实现 `ui/effect/BlurEffect.kt` 的 `rememberBlurBackdrop`：把「能不能用」的判断
 * 收在一处，调用方只需要判 null。
 */
@Composable
private fun rememberBlurBackdrop(containerColor: Color): LayerBackdrop? {
    val supported = remember { isBlurSupported() }
    if (!supported || !LocalUiPreferences.current.blurEnabled) return null
    return rememberLayerBackdrop {
        drawRect(containerColor)
        drawContent()
    }
}

/**
 * 一条栏 = 模糊蒙层 + 透明背景的真实内容。
 *
 * 内容必须画在蒙层**之上**：蒙层用 `DstIn` 拿自身内容的 alpha 当遮罩，栏里的标题
 * 文字要是画进那一层，就会被当成遮罩形状而不是显示出来。
 */
@Composable
private fun BlurBar(
    backdrop: LayerBackdrop?,
    edge: BlurEdge,
    maxRadius: Float,
    content: @Composable () -> Unit,
) {
    val tint = MiuixTheme.colorScheme.surface
    Box {
        if (backdrop != null) {
            ProgressiveBlurScrim(
                backdrop = backdrop,
                edge = edge,
                tint = tint,
                maxRadius = maxRadius,
            )
        } else {
            Spacer(Modifier.matchParentSize().background(tint))
        }
        content()
    }
}
