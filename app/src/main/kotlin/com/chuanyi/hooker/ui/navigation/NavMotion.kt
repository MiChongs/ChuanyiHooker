package com.chuanyi.hooker.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 由弹簧参数推导出来的缓动曲线。
 *
 * 从参考实现的 `NavTransitionEasing` 抄来（miuix 内部也有一份一模一样的，但标了
 * `internal`，用不了）。它把一条阻尼振动的归一化位移曲线包成 [Easing]：
 *
 * ```
 * ω = 2π / response          k = ω²          c = damping · 4π / response
 * x(t) = e^(rt)·(-cos(wt) + (r/w)·sin(wt)) + 1
 * ```
 *
 * 相比三次贝塞尔，它**起步和落位都是平滑的**。M3 那条 `cubic-bezier(0.2, 0, 0, 1)`
 * 在 t=0 处斜率就很大，观感是「弹出去」；这条是「推过去」。damping 取 0.95 接近临界
 * 阻尼，不回弹。
 */
@Immutable
class NavTransitionEasing(
    response: Float,
    damping: Float,
) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response

        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}

/**
 * 导航转场的全部参数。
 *
 * 结构与数值照搬 [hyperx-compose](https://github.com/HowieHChen/hyperx-compose) 的
 * `HyperXNavTransitions`（`ui/animation/HyperXNavTransitions.kt`）—— 一份已经在真机上
 * 跑顺的 miuix + navigation3 实现。三条 spec 的形状、偏移量、RTL 处理、曲线、时长都按
 * 它来。
 *
 * ## 三条 spec 的形状
 *
 * ```
 * 前进     新页  it   -> 0        旧页  0 -> -it/4      Soft, 500ms
 * 返回     新页 -it/4 -> 0        旧页  0 ->  it        Soft, 500ms
 * 手势返回  新页 -it/4 -> 0        旧页  0 ->  it        线性, 550ms
 * ```
 *
 * 三条都用 `slideInHorizontally` / `slideOutHorizontally`，偏移量基于**内容自身宽度**
 * （那两个 lambda 的入参就是 `fullWidth`）。
 *
 * ## 三条踩过的坑，别再犯
 *
 * **1. 不要用 `slideIntoContainer` / `slideOutOfContainer`。**
 * 那两个 API 看着更合适（按 start/end 算方向、自带 RTL），但 `slideOutOfContainer` 的
 * 位移量取自**即将进入那一页**的尺寸：
 *
 * ```kotlin
 * val targetSize = targetSizeMap[transition.targetState]?.value ?: IntSize.Zero
 * targetOffset.invoke(-calculateOffset(IntSize(it, it), targetSize).x + targetSize.width)
 * ```
 *
 * 转场刚开始那一帧进入页还没被测量过，兜底成 `IntSize.Zero`，退出动画的目标值于是变成
 * **0，也就是原地不动** —— 退出页压在最上面一动不动待满整个时长再凭空消失。参考实现
 * 全程只用 `slideOutHorizontally`，是对的。RTL 靠 [LayoutDirection] 自己翻符号。
 *
 * **2. 手势返回的退出位移必须是整屏宽度。**
 * 曾按 AOSP 预测式返回规范写成 `(屏宽/20 - 8dp)`，那个数出自**跨 Activity** 的返回，
 * **和「窗口缩到 90%」是配套的** —— 窗口缩小了才只需要挪那么一点。单独搬位移不搬缩放，
 * 就是 1440px 的屏上手指划过整屏而页面只挪 64px，看起来跟卡死一样
 * （实测 0 janky frames，不是掉帧，是真没动）。
 *
 * **3. 手势段不加缓动。**
 * miuix 把手指进度直接 `seekTo` 到 transition fraction 上
 * （`transitionState.seekTo(progress, previousScene)`），再叠一层缓动等于把手指位置
 * 重新映射一遍。所以手势那条用 [LinearEasing]。
 *
 * ## 一个改不了的地方
 *
 * 手指松开后的收尾（提交或取消）走 miuix 内部写死的 `tween(500, NavAnimationEasing)`，
 * 外部传不进去。好在那条曲线和这里的 [Soft] 是同一组参数，接得上。
 */
object NavMotion {

    /** 前进与返回的曲线。参数与参考实现一致（response 0.8s、damping 0.95）。 */
    val Soft: Easing = NavTransitionEasing(response = 0.8f, damping = 0.95f)

    /** 前进与返回。和参考实现一致。 */
    const val DurationNormal = 500

    /** 手势返回：只决定松手之后收尾那一段的时长。和参考实现一致。 */
    const val DurationPredictive = 550

    /**
     * 旧页视差：新页整屏推入时，旧页只走反方向的 1/4。
     *
     * 两页同速反向会读成「两张纸各走各的」，慢一档才有前后层次。参考实现取的也是 1/4。
     */
    const val ParallaxDivisor = 4
}

/** LTR 下「往里走」是从右边来，RTL 下反过来。 */
private fun LayoutDirection.slideSign(): Int = if (this == LayoutDirection.Rtl) -1 else 1

/** 前进（压栈）：新页整屏推进来，旧页退 1/4 做视差。 */
fun <T : Any> navPushTransition(
    layoutDirection: LayoutDirection,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    val sign = layoutDirection.slideSign()
    val spec = tween<IntOffset>(
        durationMillis = NavMotion.DurationNormal,
        easing = NavMotion.Soft,
    )
    ContentTransform(
        slideInHorizontally(animationSpec = spec, initialOffsetX = { it * sign }),
        slideOutHorizontally(
            animationSpec = spec,
            targetOffsetX = { -it / NavMotion.ParallaxDivisor * sign },
        ),
    )
}

/** 返回（出栈，非手势）：把 [navPushTransition] 完全反过来。 */
fun <T : Any> navPopTransition(
    layoutDirection: LayoutDirection,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    val sign = layoutDirection.slideSign()
    val spec = tween<IntOffset>(
        durationMillis = NavMotion.DurationNormal,
        easing = NavMotion.Soft,
    )
    ContentTransform(
        slideInHorizontally(
            animationSpec = spec,
            initialOffsetX = { -it / NavMotion.ParallaxDivisor * sign },
        ),
        slideOutHorizontally(animationSpec = spec, targetOffsetX = { it * sign }),
    )
}

/**
 * 预测式返回（边缘滑动手势）：形状和 [navPopTransition] 一样，只是换成线性跟手。
 *
 * `swipeEdge` 参数收下但不使用 —— 方向由布局方向决定，不由手指从哪条边起手决定。
 * 参考实现也是这么处理的，配套 `popDirectionFollowsSwipeEdge = false`：那个开关为
 * true 时，miuix 会在右边缘起手的情况下**整个跳过**外部传入的 pop / predictivePop
 * spec，改用自己私有的版本。
 */
fun <T : Any> navPredictivePopTransition(
    layoutDirection: LayoutDirection,
): AnimatedContentTransitionScope<Scene<T>>.(@NavigationEvent.SwipeEdge Int) -> ContentTransform = {
    val sign = layoutDirection.slideSign()
    val spec = tween<IntOffset>(
        durationMillis = NavMotion.DurationPredictive,
        easing = LinearEasing,
    )
    ContentTransform(
        slideInHorizontally(
            animationSpec = spec,
            initialOffsetX = { -it / NavMotion.ParallaxDivisor * sign },
        ),
        slideOutHorizontally(animationSpec = spec, targetOffsetX = { it * sign }),
    )
}
