package com.chuanyi.hooker.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuanyi.hooker.core.LogLevel
import com.chuanyi.hooker.data.LogEntry
import com.chuanyi.hooker.data.LogFormat
import com.chuanyi.hooker.data.LogStore
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.HookerMonoFamily
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.component.copyToClipboard
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 日志页。
 *
 * 数据全部来自 [LogStore]：被注入的进程通过
 * [LogRelay][com.chuanyi.hooker.core.LogRelay] 广播回来，模块自己那些行直接写进去。
 * 这一屏只做筛选和显示，不产生也不加工任何日志。
 *
 * ## 几个不太显然的选择
 *
 * **新的在最上面。** 日志天然是「越新越要紧」，倒序之后不必自动滚到底，用户往回翻
 * 历史时也不会被新条目拽走。正序做法要靠「用户是不是正在看历史」那个判断来决定滚不
 * 滚，而那个判断没有一种写法是让人满意的。
 *
 * **筛选条挂在 [HookerTopAppBar] 的 `bottomContent` 里**，不是列表的第一项：日志
 * 动辄上千行，翻到一半想换个等级，控件必须还在原地。顺带它落进 [BlurScaffold] 那层
 * 渐进式蒙层，和标题共享同一块毛玻璃。
 *
 * **每行是一张 [Card]，点击走它自带的 `onClick` / `onLongPress`。**这里**不能**用
 * `Modifier.clickable` / `combinedClickable`：本主题的 `LocalIndication` 是 miuix 的
 * 实现，按下时对**整个节点矩形** `drawRect` 一层遮罩，不跟随圆角 —— 行的四角会各露
 * 出一块直角色块。[Card] 走的是 [PressFeedbackType.Sink]（整块微微下沉），形状天然
 * 正确，而且它的 `showIndication` 默认就是关的。
 *
 * **等级筛选是「就是这一档」而不是「这一档及以上」。** 后者会让「详细」和「全部」
 * 变成同一个东西，六个页签白占一个；而实际用的时候点「错误」要的就是只看错误。
 */
@Composable
fun LogScreen(settings: ModuleSettings) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var levelTab by rememberSaveable { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 暂停 = 把当前快照冻住。不是「停止接收」—— 后台照收不误，只是这一屏不再跟着变，
    // 否则日志一多，正在读的那一行会被不停地往下推。
    var frozen by remember { mutableStateOf<List<LogEntry>?>(null) }
    val paused = frozen != null

    // 展开过的那些行。按 id 记而不是按下标：筛选和裁剪都会让下标漂移。
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    val shown by remember {
        derivedStateOf {
            val source = frozen ?: LogStore.entries
            source.filter { it.matches(levelTab, query) }.asReversed()
        }
    }

    val listState = rememberLazyListState()
    // 新条目插在最前面时 LazyColumn 默认锚住原来那一条，新的会被顶到视口上面去。
    // 已经在顶部（或只差一行）时把它拉回来，用户翻到下面时不动它。
    LaunchedEffect(shown.firstOrNull()?.id) {
        if (!paused && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    // 导出走系统的「创建文档」，不写进应用私有目录 —— 那里用户根本拿不到。
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 导的是**当前筛选结果**：筛过之后再导出，要的就是筛出来的那一段。
        // asReversed 把显示用的「新在前」翻回文件里该有的「旧在前」。
        val text = LogStore.render(shown.asReversed())
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)
                        ?.use { it.write(text.toByteArray()) }
                        ?: error("openOutputStream 返回 null")
                }.isSuccess
            }
            // 写不进去（用户挑了个只读位置、或者提供方拒绝）就退回剪贴板，别让这一次
            // 导出白点一遍。
            if (!written) context.copyToClipboard("日志", text, "写文件失败，已改为复制")
        }
    }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = "日志",
                subtitle = statusLine(shown.size, paused, LogStore.dropped),
                onBack = { navigator.pop() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { frozen = if (paused) null else LogStore.entries.toList() },
                    ) {
                        Icon(
                            imageVector = if (paused) MiuixIcons.Play else MiuixIcons.Pause,
                            contentDescription = if (paused) "继续" else "暂停",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                    LogMenu(
                        onCopy = {
                            context.copyToClipboard(
                                label = "日志",
                                text = LogStore.render(shown.asReversed()),
                                confirmation = "已复制 ${shown.size} 条",
                            )
                        },
                        onExport = {
                            exporter.launch("hooker-${LogFormat.fileStamp(System.currentTimeMillis())}.txt")
                        },
                        onClear = { showClearConfirm = true },
                    )
                },
                bottomContent = {
                    LogFilterBar(
                        query = query,
                        onQueryChange = { query = it },
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        onCancelSearch = {
                            query = ""
                            searchExpanded = false
                        },
                        levelTab = levelTab,
                        onLevelTabChange = { levelTab = it },
                    )
                },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
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
            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyHint(
                        hasAnyLog = LogStore.entries.isNotEmpty(),
                        relayEnabled = settings.logRelay,
                    )
                }
                return@LazyColumn
            }

            items(shown, key = { it.id }) { entry ->
                LogRow(
                    entry = entry,
                    expanded = expanded[entry.id] == true,
                    onToggle = { expanded[entry.id] = expanded[entry.id] != true },
                    onCopy = {
                        context.copyToClipboard(
                            label = "日志",
                            text = LogStore.render(listOf(entry)).trimEnd(),
                            confirmation = "已复制这一条",
                        )
                    },
                )
            }
        }
    }

    ClearConfirmDialog(
        show = showClearConfirm,
        onDismiss = { showClearConfirm = false },
        onConfirm = {
            LogStore.clear()
            expanded.clear()
            frozen = null
            showClearConfirm = false
        },
    )
}

/** 副标题：条数、暂停态、发送侧丢了多少。三样都是数字，没有一句解释。 */
private fun statusLine(count: Int, paused: Boolean, dropped: Int): String = buildString {
    if (paused) append("已暂停 · ")
    append("$count 条")
    if (dropped > 0) append(" · 丢弃 $dropped")
}

/**
 * 一条是否落在当前筛选里。
 *
 * [levelTab] 的 0 是「全部」，之后依次对应 [LogLevel.entries]。搜索同时看正文、tag
 * 和进程名 —— 排查时最常输入的恰恰是包名。
 */
private fun LogEntry.matches(levelTab: Int, query: String): Boolean {
    if (levelTab != 0 && level.id != levelTab - 1) return false
    if (query.isBlank()) return true
    return message.contains(query, ignoreCase = true) ||
        tag.contains(query, ignoreCase = true) ||
        process.contains(query, ignoreCase = true)
}

/** 搜索框 + 等级页签。整块挂在顶栏的 `bottomContent` 里，滚动时不走。 */
@Composable
private fun LogFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCancelSearch: () -> Unit,
    levelTab: Int,
    onLevelTabChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        SearchBar(
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    // 列表本来就在实时过滤，搜索键只用来收键盘。
                    onSearch = { onExpandedChange(false) },
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    label = "搜索正文、tag 或包名",
                )
            },
            onExpandedChange = onExpandedChange,
            expanded = expanded,
            outsideEndAction = {
                Text(
                    text = "取消",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .tappable(onCancelSearch)
                        .padding(end = 16.dp),
                )
            },
            modifier = Modifier.padding(horizontal = 12.dp),
            content = {},
        )

        Spacer(Modifier.height(10.dp))

        TabRow(
            tabs = LevelTabs,
            selectedTabIndex = levelTab,
            onTabSelected = onLevelTabChange,
            modifier = Modifier.padding(horizontal = 12.dp),
            // 背景必须透明：默认是 surface，会把底下那层毛玻璃整块盖掉。
            colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
        )
    }
}

/** 「全部」加五个等级。顺序和 [LogLevel.entries] 一致，下标差 1。 */
private val LevelTabs: List<String> = listOf("全部") + LogLevel.entries.map { it.label }

/**
 * 一行日志。点一下展开/收起（长栈用得上），长按复制这一条。
 *
 * 两个手势都走 [Card] 自带的回调，不挂 `Modifier.clickable` —— 理由见本文件顶部
 * 关于按压反馈的那一段。
 */
@Composable
private fun LogRow(
    entry: LogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val accent = entry.level.accentColor()
    val container = MiuixTheme.colorScheme.surfaceContainer
    // 警告和错误给整张卡染一点等级色。扫一屏时先看到的是这一层，不是那个字母。
    val tinted = when (entry.level) {
        LogLevel.Warn, LogLevel.Error -> lerp(container, accent, 0.10f)
        else -> container
    }

    Card(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        colors = CardDefaults.defaultColors(color = tinted),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onToggle,
        onLongPress = onCopy,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            LevelMark(level = entry.level, color = accent)

            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = entry.meta(),
                    style = MiuixTheme.textStyles.footnote2,
                    fontFamily = HookerMonoFamily,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.message,
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = HookerMonoFamily,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    // 收起时封顶几行：一条异常栈能有几十行，不封的话一条就占满一屏。
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** 行首那个字母。方块用 squircle，和应用里其余圆角是同一套连续曲率。 */
@Composable
private fun LevelMark(level: LogLevel, color: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .squircleBackground(color = color.copy(alpha = 0.18f), cornerRadius = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = level.mark,
            fontFamily = HookerMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** `14:03:41.220 · poweramp · com.maxmpz.audioplayer` */
private fun LogEntry.meta(): String =
    "${LogFormat.clock(time)} · ${tag.substringAfterLast('/')} · $process"

/**
 * 等级配色。
 *
 * 「信息」用主题色、「错误」用主题的错误色，这两档跟着配色走。「调试」和「警告」
 * 在主题里没有对应角色，只能自己给，深浅两套各一个 —— 深色下要用浅的那版，否则在
 * 深底上糊成一团。
 *
 * 「详细」故意是灰的：它的量最大，不该跟别的档抢注意力。
 */
@Composable
private fun LogLevel.accentColor(): Color {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        LogLevel.Verbose -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        LogLevel.Debug -> if (dark) Color(0xFF56C7BC) else Color(0xFF0E7C86)
        LogLevel.Info -> MiuixTheme.colorScheme.primary
        LogLevel.Warn -> if (dark) Color(0xFFEFB44A) else Color(0xFFB26A00)
        LogLevel.Error -> MiuixTheme.colorScheme.error
    }
}

/**
 * 空列表。
 *
 * 三种空是三件不同的事，说错了会把人引到错误的地方去查：筛没了、回传关着、
 * 以及真的还没有。
 */
@Composable
private fun EmptyHint(hasAnyLog: Boolean, relayEnabled: Boolean) {
    val text = when {
        hasAnyLog -> "没有匹配的日志"
        !relayEnabled -> "日志回传已关闭"
        else -> "还没有日志"
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 顶栏右上角那个菜单。 */
@Composable
private fun LogMenu(
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val entry = remember(onCopy, onExport, onClear) {
        DropdownEntry(
            items = listOf(
                DropdownItem(text = "复制", onClick = onCopy),
                DropdownItem(text = "导出", onClick = onExport),
                DropdownItem(text = "清空", onClick = onClear),
            ),
        )
    }
    WindowIconDropdownMenu(entry = entry) {
        Icon(
            imageVector = MiuixIcons.More,
            contentDescription = "更多",
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ClearConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = "清空日志",
        summary = "内存里和本机存档一起清掉。",
        onDismissRequest = onDismiss,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "清空",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 一段可点的文字，**不画任何按压效果**。
 *
 * 本主题的 `LocalIndication` 是 miuix 那个对整个节点矩形 `drawRect` 的实现，挂在一段
 * 文字上会在字的四周糊出一块直角色块。这里要的只是命中区域，所以把 indication 关掉；
 * 反馈由文字本身的主题色承担。
 */
@Composable
private fun Modifier.tappable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

/** 收起时正文最多显示几行。 */
private const val COLLAPSED_LINES = 6
