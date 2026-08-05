package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur

/** 模糊从哪一侧最强。 */
enum class BlurEdge { Top, Bottom }

/**
 * 一条栏的毛玻璃蒙层：**一趟 shader**，边缘软过渡。
 *
 * ## 为什么不是三层
 *
 * 上一版为了做真正的渐进式模糊（半径沿方向递减），叠了三层 [textureBlur]，每层配一张
 * `DstIn` 渐变遮罩，最后再铺一层渐变底色 —— 单条栏 3 趟 RuntimeShader + 4 个绘制层，
 * 顶栏底栏加起来每帧 6 趟。这在导航转场那种整屏都在动的时候是实打实的开销。
 *
 * 现在改成参考实现 [hyperx-compose](https://github.com/HowieHChen/hyperx-compose) 的路子
 * （`ui/layout/HyperXScaffold.kt` 的 `DynamicBlurBox`）：**单层固定半径**，色调不再单独
 * 铺一层，而是通过 [BlurColors.blendColors] 烘进 shader 里一起算完。
 *
 * ## 但没有照抄成均匀模糊
 *
 * 参考实现那层是铺满整条栏的均匀模糊，栏与内容的交界会留一条硬边（HyperOS 的栏本来就
 * 是这种「浮起来的一块」观感，硬边是设计的一部分）。这个应用的栏是完全透明、内容直接从
 * 底下穿过去的，硬边会很突兀。
 *
 * 所以保留一张 `DstIn` 渐变遮罩：**半径不再沿方向变化，但可见度还是渐变的**，交界处
 * 自然淡出。代价只是一个渐变矩形，不是一趟 shader。
 *
 * ```
 * 之前：3 × textureBlur + 3 × 遮罩 + 1 × 渐变底色     每条栏 3 趟 shader
 * 现在：1 × textureBlur（含色调）+ 1 × 遮罩          每条栏 1 趟 shader
 * ```
 *
 * `DstIn` 的含义决定了结构：该 modifier 拿**自身内容的 alpha** 去裁模糊结果，所以这层
 * 里只放一条垂直渐变的 Spacer，不放别的。真正的栏内容画在这层之上，见 `BlurBar`。
 *
 * 必须在 [BoxScope] 里调用：它用 `matchParentSize` 铺满整条栏。
 */
@Composable
fun BoxScope.ProgressiveBlurScrim(
    backdrop: Backdrop,
    edge: BlurEdge,
    tint: Color,
    modifier: Modifier = Modifier,
    maxRadius: Float = BlurDefaults.BlurRadius,
    tintAlpha: Float = 0.6f,
) {
    // 色调烘进 shader：一次采样里连模糊带上色一起算完，省掉单独一层渐变底色。
    val blurColors = remember(tint, tintAlpha) {
        BlurColors(blendColors = listOf(BlendColorEntry(tint.copy(alpha = tintAlpha))))
    }
    // Brush 每次构造都要重算 color stop 数组，而这层盖在滚动内容上、每帧都可能重组。
    val mask = remember(edge) { maskBrush(edge) }

    Box(
        modifier = modifier
            .matchParentSize()
            .textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = maxRadius,
                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                colors = blurColors,
                contentBlendMode = BlendMode.DstIn,
            ),
    ) {
        Spacer(Modifier.fillMaxSize().background(mask))
    }
}

/**
 * 遮罩：贴边那一侧全不透明，到另一侧降为全透明。
 *
 * 中段先留一小段实心再开始衰减，否则整条栏从头淡到尾，栏里的文字背后就没有足够的底，
 * 在浅色内容上会读不清。
 */
private fun maskBrush(edge: BlurEdge): Brush {
    val stops = arrayOf(
        0f to Color.Black,
        0.55f to Color.Black,
        0.88f to Color.Black.copy(alpha = 0.35f),
        1f to Color.Transparent,
    )
    return Brush.verticalGradient(colorStops = stops.orientTo(edge))
}

/**
 * [BlurEdge.Top] 的渐变按原样用；[BlurEdge.Bottom] 要整条翻过来，
 * 位置取 `1 - pos` 后还得反转顺序，否则 stop 不是递增的。
 */
private fun Array<Pair<Float, Color>>.orientTo(edge: BlurEdge): Array<Pair<Float, Color>> =
    if (edge == BlurEdge.Top) {
        this
    } else {
        Array(size) { i ->
            val (position, color) = this[size - 1 - i]
            (1f - position) to color
        }
    }
