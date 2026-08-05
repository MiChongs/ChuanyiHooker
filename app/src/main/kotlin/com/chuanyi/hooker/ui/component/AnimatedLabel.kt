package com.chuanyi.hooker.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.chuanyi.hooker.ui.swapVertically
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一行会变的文字。
 *
 * miuix 的 [Text] 换文案是瞬间的：一行说明从「等待框架确认…」跳成「不在作用域内」，
 * 中间没有任何过渡，而这类行在这个模块里换得很勤（作用域回调、进程状态、root
 * 授权结果）。这里把它包进 `AnimatedContent`，顺带用 `SizeTransform` 接住行数变化
 * 引起的高度跳动。
 *
 * 空串渲染成零尺寸的 [Spacer]，所以「本来没有说明，后来有了」也是一次展开动画，
 * 而不是把行高直接撑开。
 *
 * 参数刻意和 miuix [Text] 对齐（[fontSize] / [fontWeight] / [style] 各自独立），
 * 这样替换调用点时不必换一套写法。
 */
@Composable
fun AnimatedLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = MiuixTheme.textStyles.main,
    maxLines: Int = Int.MAX_VALUE,
    label: String = "animatedLabel",
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { swapVertically() },
        contentAlignment = Alignment.CenterStart,
        label = label,
    ) { value ->
        if (value.isEmpty()) {
            Spacer(Modifier)
        } else {
            Text(
                text = value,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一个会变的数字。
 *
 * 和 [AnimatedLabel] 的区别只有方向：变大时新数字从下往上顶，变小时反过来。数字
 * 本身没有方向感的话，「3/7 变成 4/7」和「变成 2/7」看起来是同一个动画。
 */
@Composable
fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = MiuixTheme.textStyles.main,
    label: String = "animatedNumber",
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = { swapVertically(downwards = targetState < initialState) },
        contentAlignment = Alignment.Center,
        label = label,
    ) { current ->
        Text(
            text = current.toString(),
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            style = style,
        )
    }
}
