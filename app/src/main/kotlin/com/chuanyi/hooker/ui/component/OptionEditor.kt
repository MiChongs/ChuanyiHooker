package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.core.HookOption
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 改一个 [HookOption] 取值的弹窗。
 *
 * 数字和文本共用同一个壳，差别只在键盘类型和「什么算合法」：数字项超出上下界会被
 * 夹回去而不是拒绝提交 —— 用户填 999 的意思是「要最大」，不是「要报错」。
 *
 * 壳用 miuix 的 [OverlayDialog]：它带自己的进出动效、遮罩与安全区处理，和这一屏
 * 其余控件是同一套材质。输入框同理用 miuix 的 [TextField]，浮动标签直接承担
 * 「合法范围是多少」这句提示。
 */
@Composable
fun OptionEditDialog(
    show: Boolean,
    option: HookOption?,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (option == null) return

    var input by remember(option) { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    val isNumber = option !is HookOption.Text

    // [OverlayDialog] 是**常驻组合、靠 show 切换**的：只在需要时才把它放进组合树的
    // 话，它首次出现时 show 已经是 true，那个从 false 到 true 的跳变没发生过，
    // 进场动画不触发，看上去就是「点了没反应」。
    //
    // 所以内容的重置改由 show 的上升沿驱动：每次打开都从当前配置重新读一遍，
    // 上次改到一半点了取消，这次不该还留着。
    LaunchedEffect(show, current) {
        if (!show) return@LaunchedEffect
        input = current
        runCatching { focus.requestFocus() }
    }

    val hint = when (option) {
        is HookOption.Number -> rangeHint(option.min, option.max, option.unit)
        is HookOption.Choice -> option.custom?.let { rangeHint(it.min, it.max, it.unit) }.orEmpty()
        is HookOption.Text -> option.hint
        // 这两种有自己的编辑器（KeyMapEditorSheet / AppPickerSheet），走不到这里。
        is HookOption.KeyMap, is HookOption.AppList -> ""
    }

    WindowDialog(
        show = show,
        title = option.title,
        summary = option.summary.takeIf { it.isNotEmpty() },
        onDismissRequest = onDismiss,
        // 弹窗是「一件事的专属空间」，四周比列表页松一些才不显得内容被压在边上。
        insideMargin = DIALOG_MARGIN,
    ) {
        Column(modifier = Modifier.imePadding()) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = hint,
                useLabelAsPlaceholder = true,
                singleLine = (option as? HookOption.Text)?.singleLine ?: true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            )

            // 数字项的三个快捷值：省得为了「调回默认」去记原来是多少。
            quickValues(option)?.let { presets ->
                Row(
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { (label, value) ->
                        TextButton(
                            text = label,
                            onClick = { input = value.toString() },
                            modifier = Modifier.weight(1f),
                            cornerRadius = 10.dp,
                            minWidth = 0.dp,
                            minHeight = 32.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
                TextButton(
                    text = "确定",
                    onClick = { onConfirm(input) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
            }
        }
    }
}

/** 弹窗内容的四周留白。和 [KeyMapEditorSheet] 用同一档，两处弹层观感才一致。 */
private val DIALOG_MARGIN = DpSize(24.dp, 20.dp)

private fun rangeHint(min: Int, max: Int, unit: String): String =
    "范围 $min – $max" + if (unit.isBlank()) "" else "（${unit.trim()}）"

/** 数字项底下那排一键值：最小 / 默认 / 最大。文本项没有。 */
private fun quickValues(option: HookOption): List<Pair<String, Int>>? = when (option) {
    is HookOption.Number -> listOf(
        "最小 ${option.min}" to option.min,
        "默认 ${option.default}" to option.default,
        "最大 ${option.max}" to option.max,
    )

    is HookOption.Choice -> option.custom?.let {
        listOf(
            "最小 ${it.min}" to it.min,
            "默认 ${it.default}" to it.default,
            "最大 ${it.max}" to it.max,
        )
    }

    is HookOption.Text, is HookOption.KeyMap, is HookOption.AppList -> null
}
