package com.chuanyi.hooker.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.anim.folmeSpring

/**
 * 全局动效基准。
 *
 * 界面显得「生硬」几乎从来不是少了某一个动画，而是各处各写各的时长曲线 —— 一处
 * `tween(200)`、一处默认 spring、一处干脆瞬变，凑在同一屏里就没有统一的物理感。
 * 所以时长与曲线集中在这里，页面只挑档位。
 *
 * 全部走 miuix 的 [folmeSpring]，参数是 HyperOS 那套 Folme 的说法：
 *
 *  * `damping` 阻尼比。1.0 临界阻尼（不过冲），< 1.0 会回弹一点。
 *  * `response` 响应时间（秒），越小越快。它换算成 `stiffness = (2π/response)²`。
 *
 * **为什么全用弹簧不用 tween**：这一屏的动画大多会被打断 —— 开关连点、刷新回来
 * 列表又变了。弹簧从当前速度接着走，tween 会从头重放，被打断时就是「一顿一顿」的
 * 来源。
 *
 * 挡位按**位移量**分，不按控件类型分：
 *
 * | 档 | response | 用在 |
 * |---|---|---|
 * | QUICK | 0.26 | 透明度、颜色 —— 必须立刻跟手 |
 * | STANDARD | 0.36 | 单个控件的值变化：进度、缩放 |
 * | SPATIAL | 0.46 | 整段内容进出、尺寸与位置 —— 走得远，要慢一点才看得清去向 |
 */
object Motion {

    private const val QUICK = 0.26f
    private const val STANDARD = 0.36f
    private const val SPATIAL = 0.46f

    /** 透明度。临界阻尼：alpha 过冲会被钳到 [0,1]，回弹在这里只会变成一次卡顿。 */
    val alpha: FiniteAnimationSpec<Float> = folmeSpring(damping = 1f, response = QUICK)

    /** 普通标量：进度、缩放。留一点点回弹，落位才有分量。 */
    val scalar: FiniteAnimationSpec<Float> = folmeSpring(damping = 0.92f, response = STANDARD)

    /** 颜色。和透明度同理，不允许过冲 —— 过冲的颜色是另一个颜色。 */
    val tint: FiniteAnimationSpec<Color> = folmeSpring(damping = 1f, response = STANDARD)

    /** 尺寸。阈值给到 1px，免得为了最后一个像素多跑几帧。 */
    val bounds: FiniteAnimationSpec<IntSize> =
        folmeSpring(damping = 1f, response = SPATIAL, visibilityThreshold = IntSize(1, 1))

    /** 位置。列表项重排、滑入滑出都走它。 */
    val slide: FiniteAnimationSpec<IntOffset> =
        folmeSpring(damping = 0.9f, response = SPATIAL, visibilityThreshold = IntOffset(1, 1))

    /**
     * 跟随：外层容器追内层内容的高度（`animateContentSize`）。
     *
     * 比 [bounds] 快一档是刻意的 —— 它追的目标本身常常也在动（里面的说明文字正走
     * 自己的 `SizeTransform`），两条同速的弹簧串起来就会一直差着一截，表现为内容
     * 底部被裁掉一小条。追的那一方必须比被追的快。
     */
    val follow: FiniteAnimationSpec<IntSize> =
        folmeSpring(damping = 1f, response = QUICK, visibilityThreshold = IntSize(1, 1))

    /** 行尾多出/少掉一个小东西（转圈、按钮）：从右侧挤进来，同时缩放。 */
    val enterInline: EnterTransition =
        fadeIn(alpha) + expandHorizontally(bounds, Alignment.End) + scaleIn(scalar, initialScale = 0.6f)
    val exitInline: ExitTransition =
        fadeOut(alpha) + shrinkHorizontally(bounds, Alignment.End) + scaleOut(scalar, targetScale = 0.6f)
}

/**
 * 一个值被另一个值换掉：新的从下方顶上来，旧的往上退场，容器高度跟着走。
 *
 * [downwards] 反转方向，用来表达「数字变小了」这类有方向的变化。
 */
fun AnimatedContentTransitionScope<*>.swapVertically(
    downwards: Boolean = false,
    sizeTransform: SizeTransform? = SizeTransform { _, _ -> Motion.bounds },
): ContentTransform {
    val sign = if (downwards) -1 else 1
    return (fadeIn(Motion.alpha) + slideInVertically(Motion.slide) { height -> sign * height / 3 })
        .togetherWith(
            fadeOut(Motion.alpha) + slideOutVertically(Motion.slide) { height -> -sign * height / 3 },
        )
        .using(sizeTransform)
}

/**
 * 整块内容被换掉（空态 ↔ 列表、正常 ↔ 报错）。
 *
 * 尺寸默认不在这里动：这类切换外面通常已经套了 `animateContentSize`，两处各自
 * animate 一次高度就会互相追赶。需要它自己管高度时显式传 [sizeTransform]。
 */
fun AnimatedContentTransitionScope<*>.crossFade(
    sizeTransform: SizeTransform? = null,
): ContentTransform =
    fadeIn(Motion.alpha)
        .togetherWith(fadeOut(Motion.alpha))
        .using(sizeTransform)

/**
 * LazyColumn 里一项的标配：进出淡入淡出，位置变化走弹簧。
 *
 * 没有它，任何一项的出现或消失都会把下面的内容瞬间顶走 —— 这是「生硬」最主要的
 * 来源。**每个 item 必须带稳定 key**，否则这个 modifier 不生效。
 */
fun LazyItemScope.motionItem(): Modifier = Modifier.animateItem(
    fadeInSpec = Motion.alpha,
    placementSpec = Motion.slide,
    fadeOutSpec = Motion.alpha,
)
