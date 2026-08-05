package com.chuanyi.hooker.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.ui.Motion
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 按键映射编辑器。
 *
 * 底下存的还是一行 `q=1, w=2`，但**不该拿这行文本去问用户**：手写二十几个键的映射
 * 既要记格式，改其中一个还得先在字符串里把它找出来。所以这里给的是一张键盘 ——
 * 每个键上就写着它现在滑出什么，点哪个改哪个，改完一眼能看出整体铺得齐不齐。
 *
 * 三个层次，从粗到细：
 *
 * 1. **预置方案** —— 「整行数字」这类一键铺满，多数人到这一步就够了；
 * 2. **点键改单个** —— 选中的键在下方输入框里改，输入框始终聚焦，
 *    可以点一个键、打一个字、再点下一个，不用在弹窗之间来回；
 * 3. **长按清掉一个键** —— 让它回到原版行为。
 *
 * ## 性能
 *
 * 这一屏一次要画二十几个键，很容易变卡，三处是关键：
 *
 *  * **状态容器必须是 [SnapshotStateMap] 而不是 `Map`**。声明成 `Map` 的话
 *    Compose 判定它不稳定，每次外层重组都要把整块键盘连同所有键重算一遍；
 *    换成快照 map 之后，改一个键只让那一个键重组。
 *  * **每个键只留一个动画**。三个颜色各挂一个 `animateColorAsState` 就是七十多个
 *    动画同时跑，和弹层的进场撞在一起必掉帧。前景色改成直接算 —— 它跟着背景一起变，
 *    单独补间没有额外信息。
 *  * **进场期间不画键盘**。弹层滑上来的同时首次组合二十几个键，是最容易看出卡顿的
 *    一帧；等动画走完再铺，观感上是「弹层先到位，内容随即出现」。
 *
 * ## 安全区
 *
 * 关掉弹层自带的 inset 处理，改成自己贴 [imePadding] + [navigationBarsPadding]：
 * 这一屏底部有输入框，键盘弹起时整块内容要跟着上移，而弹层默认那套只顾得上一头。
 */
@Composable
fun KeyMapEditorSheet(
    show: Boolean,
    option: HookOption.KeyMap?,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (option == null) return

    val entries = remember(option) { mutableStateMapOf<String, String>() }
    var selected by remember(option) { mutableStateOf<String?>(null) }
    var ready by remember(option) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // 每次打开都从当前配置重新读一遍：上次改到一半点了取消，这次不该还留着。
    // 键盘推迟到进场动画之后再铺，理由见类文档。
    LaunchedEffect(show, current) {
        if (!show) {
            ready = false
            return@LaunchedEffect
        }
        entries.clear()
        entries.putAll(option.parse(current))
        selected = null
        delay(ENTER_MILLIS)
        ready = true
    }

    WindowBottomSheet(
        show = show,
        title = option.title,
        onDismissRequest = onDismiss,
        defaultWindowInsetsPadding = false,
        insideMargin = SHEET_MARGIN,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Header(summary = option.summary, count = entries.size)

            if (option.presets.isNotEmpty()) {
                SmallTitle(
                    text = "整体方案",
                    insideMargin = TITLE_MARGIN,
                )
                PresetRow(
                    presets = option.presets,
                    onPick = { value ->
                        entries.clear()
                        entries.putAll(option.parse(value))
                        selected = null
                    },
                )
            }

            SmallTitle(
                text = "逐键设置",
                insideMargin = TITLE_MARGIN,
            )
            Box(modifier = Modifier.height(keyboardHeight(option.rows.size))) {
                if (ready) {
                    Keyboard(
                        rows = option.rows,
                        values = entries,
                        selected = selected,
                        onSelect = { selected = it },
                        onClear = { entries.remove(it); if (selected == it) selected = null },
                    )
                }
            }

            Editor(
                selected = selected,
                values = entries,
                focus = focus,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "清空",
                    onClick = { entries.clear(); selected = null },
                    modifier = Modifier.weight(1f),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
                TextButton(
                    text = "保存",
                    onClick = { onConfirm(option.format(entries.toMap())) },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
            }

            // 手势条离按钮太近会误触。navigationBarsPadding 只让出系统条本身，
            // 这一段是留给手指的余量。
            Spacer(Modifier.height(BOTTOM_SLACK))
        }
    }
}

/** 说明 + 已设了几个键。计数比「改完自己数」有用得多。 */
@Composable
private fun Header(summary: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        AnimatedLabel(
            text = if (count == 0) "未设置" else "已设 $count 个",
            modifier = Modifier.padding(start = 12.dp),
            style = MiuixTheme.textStyles.body2,
            color = if (count == 0) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.primary
            },
            label = "keyMapCount",
        )
    }
}

/**
 * 预置方案。
 *
 * 就那么两三项，用 `Row` + 横向滚动而不是 `LazyRow` —— 惰性列表在这个量级上
 * 只是白付出一份布局与回收的开销。
 */
@Composable
private fun PresetRow(presets: List<HookOption.KeyMap.Preset>, onPick: (String) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            key(preset.label) {
                TextButton(
                    text = preset.label,
                    onClick = { onPick(preset.value) },
                    cornerRadius = 10.dp,
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 选中某个键之后的输入区。
 *
 * 没选中时留一句提示，而不是摆一个不知道在改什么的输入框。输入框本身**不随选中的键
 * 重建**（不套 `key`）—— 重建会让软键盘收起再弹出，快速换键时闪得厉害。
 */
@Composable
private fun Editor(
    selected: String?,
    values: SnapshotStateMap<String, String>,
    focus: FocusRequester,
) {
    Box(modifier = Modifier.padding(top = 14.dp).fillMaxWidth()) {
        if (selected == null) {
            Text(
                text = "点一个键设置它滑出什么；长按可以清掉，让它回到原版行为",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LaunchedEffect(selected) { runCatching { focus.requestFocus() } }
            TextField(
                value = values[selected].orEmpty(),
                onValueChange = { text ->
                    if (text.isEmpty()) values.remove(selected) else values[selected] = text
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = "「${selected.uppercase()}」滑出",
                useLabelAsPlaceholder = false,
                singleLine = true,
            )
        }
    }
}

/**
 * 键盘本体。
 *
 * 每行按**最长的那一行**分格再居中：真实键盘的第二、三行就是缩进的，按各自长度
 * 平均分会让三行的键宽都不一样，看着不像键盘。
 *
 * [values] 的类型必须是 [SnapshotStateMap]：声明成 `Map` 会让整块键盘变成不可跳过，
 * 改一个键就要把二十几个键全部重算。
 */
@Composable
private fun Keyboard(
    rows: List<String>,
    values: SnapshotStateMap<String, String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val theme = MiuixTheme.colorScheme
    val palette = remember(theme) {
        KeyPalette(
            idle = theme.onBackground.copy(alpha = TINT_IDLE),
            set = theme.primary.copy(alpha = TINT_SET),
            active = theme.primary,
            onIdle = theme.onBackground,
            onSet = theme.primary,
            onActive = theme.onPrimary,
            muted = theme.onSurfaceVariantSummary,
        )
    }
    val columns = rows.maxOfOrNull { it.length } ?: 1

    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        rows.forEach { row ->
            key(row) {
                val pad = (columns - row.length) / 2f
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    if (pad > 0f) Spacer(Modifier.weight(pad))
                    row.forEach { char ->
                        val name = char.toString()
                        key(name) {
                            KeyCap(
                                name = name,
                                value = values[name],
                                selected = selected == name,
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(name) },
                                onLongClick = { onClear(name) },
                            )
                        }
                    }
                    if (pad > 0f) Spacer(Modifier.weight(pad))
                }
            }
        }
    }
}

/**
 * 一个键。
 *
 * 上面是键本身，下面是它现在滑出什么 —— 两行一起看，整块键盘就是「哪些键设过、
 * 设成了什么」的全貌，不用逐个点开确认。没设过的显示一个点，比留空更明确
 * （留空会让人以为是渲染坏了）。
 *
 * **只有背景色补间**。三个颜色各挂一个动画的话，二十几个键就是七十多个动画同时
 * 在跑；前景色跟着背景一起变，单独补间并不多给出任何信息。
 */
@Composable
private fun KeyCap(
    name: String,
    value: String?,
    selected: Boolean,
    palette: KeyPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = when {
            selected -> palette.active
            value != null -> palette.set
            else -> palette.idle
        },
        animationSpec = Motion.tint,
        label = "keyCap",
    )
    val nameColor = if (selected) palette.onActive else palette.onIdle
    val valueColor = when {
        selected -> palette.onActive
        value != null -> palette.onSet
        else -> palette.muted
    }

    Column(
        modifier = modifier
            .height(KEY_HEIGHT)
            .squircleBackground(background, KEY_CORNER)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name.uppercase(),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = nameColor,
            maxLines = 1,
        )
        Text(
            text = value ?: "·",
            style = MiuixTheme.textStyles.footnote2,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 键盘用到的全部颜色，一次算好传下去。
 *
 * 标 [Immutable] 是为了让 [KeyCap] 保持可跳过 —— 每个键都从 `MiuixTheme` 里现取
 * 颜色的话，主题一变就是二十几次独立读取，而且每个键的参数列表里多出一个
 * 不稳定的来源。
 */
@Immutable
private class KeyPalette(
    val idle: Color,
    val set: Color,
    val active: Color,
    val onIdle: Color,
    val onSet: Color,
    val onActive: Color,
    val muted: Color,
)

/** 键盘整体高度，用来在内容还没铺出来时先把位置占住，避免弹层高度跳一下。 */
private fun keyboardHeight(rows: Int) = KEY_HEIGHT * rows + KEY_GAP * (rows - 1)

/**
 * 弹层内容的四周留白。
 *
 * 比列表页的 12dp 宽出不少 —— 弹层是「一件事的专属空间」，四周松一点才不显得
 * 内容被压在边上；列表页的窄边距是为了让卡片之间的分隔更清楚，两者诉求不同。
 */
private val SHEET_MARGIN = DpSize(24.dp, 20.dp)

/** 段标题的留白。左侧对齐正文，不再自己缩进。 */
private val TITLE_MARGIN = PaddingValues(top = 16.dp, bottom = 6.dp)

/** 最底下按钮与手势条之间留给手指的余量。 */
private val BOTTOM_SLACK = 8.dp

/** 弹层进场大致用时。键盘等它走完再铺，避免两件事挤在同几帧里。 */
private const val ENTER_MILLIS = 220L

/** 键帽高度。两行字（键名 + 当前值）刚好，再矮就挤了。 */
private val KEY_HEIGHT = 48.dp

private val KEY_GAP = 6.dp

private val KEY_CORNER = 10.dp

/** 设过值的键的底色浓度 —— 一眼扫过去能看出哪些设过。 */
private const val TINT_SET = 0.16f

/** 没设过的键。 */
private const val TINT_IDLE = 0.06f
