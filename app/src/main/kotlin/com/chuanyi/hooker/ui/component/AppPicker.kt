package com.chuanyi.hooker.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.data.InstalledApp
import com.chuanyi.hooker.data.InstalledApps
import com.chuanyi.hooker.ui.Motion
import coil3.ImageLoader
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 应用选择器。
 *
 * 底下存的还是一行 `com.termux, org.connectbot`，但**不该拿这行去问用户**：
 * 包名记不住，而且错一个字符是静默失效 —— 界面上什么都不会提示，功能就是不生效。
 * 所以这里给的是本机装了哪些应用，带图标和名字，勾一下就行。
 *
 * 四段，从「你多半要选的」到「全部」：
 *
 * 1. **已选** —— 打开时就选中的那些，钉在最上面。**这一段在勾选过程中不重排**，
 *    否则每点一个应用它就跳到顶上去，手指还停在原来的位置；
 * 2. **推荐** —— 这个功能最可能要的那几个（终端类应用之类），由 hooker 声明；
 * 3. **全部应用** —— 其余的，默认只列有启动图标的，系统组件用上面的分段切换；
 * 4. **搜索** —— 一旦输入就只剩搜索结果，名字和包名都能搜。
 *
 * 搜索没有结果时给一条「直接使用这个包名」——目标还没装、或者要写
 * `com.termux*` 这样的前缀规则时，这是唯一的入口。
 *
 * ## 性能
 *
 * 两三百个应用、每个还要读图标，是这一屏唯一的性能风险。滑动要顺，四处都得管住：
 *
 *  * **图标必须栅格化**。应用图标几乎都是 `AdaptiveIconDrawable`，把它当 Drawable
 *    交给 Coil 的话每一帧都要重画两层再裁一次遮罩 —— 这是滚动卡顿的主因，
 *    见 [AppIcon] 里的 `rasterize`；ImageLoader 也是进程内共用一个，不然每个界面
 *    各刷各的缓存；
 *  * **分段结果不能依赖选中集合**。依赖了的话每勾一下都要重新过滤排序两三百项；
 *  * **[Catalog] 用身份判等**。直接拿 `List<InstalledApp>` 当 `remember` 的 key，
 *    每次重组都要逐个 equals 比三百个元素；
 *  * 列表本身在 [InstalledApps] 里按进程缓存，弹层反复开关不重新读；
 *  * 进场动画期间不铺列表，理由同 [KeyMapEditorSheet]。
 */
@Composable
fun AppPickerSheet(
    show: Boolean,
    option: HookOption.AppList?,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (option == null) return

    val context = LocalContext.current
    val iconLoader = rememberAppIconLoader()

    val selected = remember(option) { mutableStateListOf<String>() }
    var query by remember(option) { mutableStateOf("") }
    var systemVisible by remember(option) { mutableStateOf(false) }
    var catalog by remember(option) { mutableStateOf<Catalog?>(null) }
    var ready by remember(option) { mutableStateOf(false) }

    // 打开时就选中的那些。**故意不跟着 selected 走**：这一段是「进来时的样子」，
    // 勾选过程中保持原位，取消勾选也留在原地（还能再勾回来）。
    var pinned by remember(option) { mutableStateOf(emptyList<String>()) }

    // 每次打开都从当前配置重新读：上次改到一半点了取消，这次不该还留着。
    LaunchedEffect(show, current) {
        if (!show) {
            ready = false
            return@LaunchedEffect
        }
        selected.clear()
        selected.addAll(option.parse(current))
        pinned = option.parse(current)
        query = ""
        catalog = Catalog(InstalledApps.load(context))
        delay(ENTER_MILLIS)
        ready = true
    }

    // 列表高度按窗口给，写死的话小屏上会顶到按钮、大屏上白白空着半页。
    val listHeight = rememberListHeight()

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
            Header(
                summary = option.summary,
                count = selected.size,
                emptyMeansAll = option.emptyMeansAll,
            )

            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth().padding(top = SEARCH_GAP),
                label = "搜索应用名或包名",
            )

            // 分段切换只在没搜索时有意义：搜索本来就是在全集里找。
            AnimatedVisibility(
                visible = query.isBlank(),
                enter = Motion.enterInline,
                exit = Motion.exitInline,
                label = "appScopeTabs",
            ) {
                TabRow(
                    tabs = SCOPE_TABS,
                    selectedTabIndex = if (systemVisible) 1 else 0,
                    onTabSelected = { systemVisible = it == 1 },
                    modifier = Modifier.fillMaxWidth().padding(top = TAB_GAP),
                )
            }

            // 列表进卡片：和详情页每一段一样的分组观感，也给滚动区一个明确的边界，
            // 否则一列行浮在弹层背景上，看不出哪里是可滚的。
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = LIST_GAP),
                insideMargin = PaddingValues(0.dp),
            ) {
                Box(modifier = Modifier.height(listHeight)) {
                    val loaded = catalog
                    if (!ready || loaded == null) {
                        Loading()
                    } else {
                        AppList(
                            option = option,
                            catalog = loaded,
                            pinned = pinned,
                            query = query.trim(),
                            systemVisible = systemVisible,
                            selected = selected,
                            iconLoader = iconLoader,
                            onToggle = { pkg, on -> if (on) selected.add(pkg) else selected.remove(pkg) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = BUTTON_GAP),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "清空",
                    onClick = { selected.clear() },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
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
                    onClick = { onConfirm(option.format(selected.toList())) },
                    modifier = Modifier.weight(1.3f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(BOTTOM_SLACK))
        }
    }
}

/**
 * 说明 + 选了几个。
 *
 * 计数做成一枚色块而不是一行普通文字：说明会换行，两段同级的文字并排时，
 * 短的那段会被挤到长段的第一行末尾，看着像没排完。色块有自己的边界，
 * 长度再变也各是各的。
 *
 * 「空 = 全部生效」的功能要把这层意思说出来，否则清空看起来像是把功能关掉了。
 */
@Composable
private fun Header(summary: String, count: Int, emptyMeansAll: Boolean) {
    val active = count > 0 || emptyMeansAll
    val tint = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        AnimatedLabel(
            text = when {
                count > 0 -> "已选 $count"
                emptyMeansAll -> "全部"
                else -> "未选择"
            },
            modifier = Modifier
                .squircleBackground(tint.copy(alpha = BADGE_TINT), BADGE_CORNER)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MiuixTheme.textStyles.body2,
            color = tint,
            label = "appPickerCount",
        )
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 28.dp)
    }
}

/**
 * 列表本体。
 *
 * 分段结果 [remember] 在「列表 / 搜索词 / 系统应用开关 / 钉住的那些」上 ——
 * **选中集合不在里面**：勾一下就重新过滤排序两三百项的话，点击会有可感的延迟，
 * 而且行会在手指底下重排。
 */
@Composable
private fun AppList(
    option: HookOption.AppList,
    catalog: Catalog,
    pinned: List<String>,
    query: String,
    systemVisible: Boolean,
    selected: SnapshotStateList<String>,
    iconLoader: ImageLoader,
    onToggle: (String, Boolean) -> Unit,
) {
    // key 里一个都不能是「要逐元素比较才知道变没变」的东西：catalog 按身份判等，
    // pinned 在弹层打开后就不再变，其余是基本类型。
    val sections = remember(catalog, pinned, query, systemVisible, option) {
        buildSections(option, catalog.apps, pinned, query, systemVisible)
    }
    val state = rememberLazyListState()

    if (sections.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "没有匹配的应用",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        state = state,
        contentPadding = LIST_PADDING,
    ) {
        sections.forEach { section ->
            if (section.title != null) {
                item(key = "title:${section.title}") {
                    SmallTitle(text = section.title, insideMargin = TITLE_MARGIN)
                }
            }
            items(section.entries, key = { it.packageName }) { entry ->
                AppRow(
                    entry = entry,
                    checked = entry.packageName in selected,
                    iconLoader = iconLoader,
                    onCheckedChange = { onToggle(entry.packageName, it) },
                )
            }
        }
    }
}

/**
 * 一行。走 miuix 的 [CheckboxPreference]：勾选框、按压反馈、禁用配色都跟系统设置
 * 一致，图标塞进它的 startAction 槽位。
 *
 * 勾选框放**行尾**。默认在行首，于是一行读起来是「□ 图标 名字」——那个空心圈占着
 * 本该属于图标的位置，一列看下来像是每个应用都多了个灰头像，图标也跟着往右缩一截，
 * 和上面的搜索框对不齐。
 */
@Composable
private fun AppRow(
    entry: PickerEntry,
    checked: Boolean,
    iconLoader: ImageLoader,
    onCheckedChange: (Boolean) -> Unit,
) {
    CheckboxPreference(
        title = entry.title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        summary = entry.summary,
        checkboxLocation = CheckboxLocation.End,
        insideMargin = ROW_MARGIN,
        startAction = {
            AppIcon(
                packageName = entry.packageName,
                fallbackLabel = entry.title,
                imageLoader = iconLoader,
                size = ICON_SIZE,
                cornerRadius = ICON_CORNER,
                installed = entry.installed,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    )
}

// ---------------------------------------------------------------------------
// 分段
// ---------------------------------------------------------------------------

/**
 * 已装应用列表的持有者。
 *
 * 存在的唯一理由是**按身份判等**：`List<InstalledApp>` 直接当 `remember` 的 key，
 * 每次重组都会逐个 `equals` 比两三百个元素，而这份列表在弹层打开期间根本不会变。
 */
@Immutable
private class Catalog(val apps: List<InstalledApp>)

/** 列表里的一行要画的全部东西，已经算好，行本身不再判断。 */
@Immutable
private class PickerEntry(
    val packageName: String,
    val title: String,
    val summary: String,
    val installed: Boolean,
)

@Immutable
private class PickerSection(val title: String?, val entries: List<PickerEntry>)

/**
 * 把应用列表拆成几段。
 *
 * 搜索时只有一段（搜索结果），外加找不到时那条「直接使用这个包名」——
 * 分段在搜索场景下只会让人多滚一屏。
 */
private fun buildSections(
    option: HookOption.AppList,
    apps: List<InstalledApp>,
    pinned: List<String>,
    query: String,
    systemVisible: Boolean,
): List<PickerSection> {
    val byPackage = apps.associateBy { it.packageName }

    if (query.isNotEmpty()) {
        val needle = query.lowercase()
        val hits = apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }.map { it.toEntry() }
        // 搜的就是包名本身、或者在写一条前缀规则时，列表里不会有它 —— 给一条直接用。
        val manual = if (hits.none { it.packageName.equals(query, ignoreCase = true) }) {
            listOf(
                PickerEntry(
                    packageName = query,
                    title = query,
                    summary = if (option.isPattern(query)) PATTERN_HINT else "直接使用这个包名（未安装或未列出）",
                    installed = false,
                ),
            )
        } else {
            emptyList()
        }
        val all = hits + manual
        return if (all.isEmpty()) emptyList() else listOf(PickerSection(null, all))
    }

    val sections = mutableListOf<PickerSection>()

    if (pinned.isNotEmpty()) {
        sections += PickerSection(
            "已选",
            pinned.map { entry -> byPackage[entry]?.toEntry() ?: entry.toManualEntry(option) },
        )
    }

    val suggested = option.suggested
        .filter { it !in pinned }
        .mapNotNull { byPackage[it] }
        .map { it.toEntry() }
    if (suggested.isNotEmpty()) sections += PickerSection("推荐", suggested)

    val taken = pinned.toSet() + suggested.map { it.packageName }
    val rest = apps.asSequence()
        .filter { it.packageName !in taken }
        .filter { systemVisible || it.launchable || !it.isSystem }
        .map { it.toEntry() }
        .toList()
    if (rest.isNotEmpty()) {
        sections += PickerSection(if (systemVisible) "全部应用" else "应用", rest)
    }

    return sections
}

private fun InstalledApp.toEntry() = PickerEntry(
    packageName = packageName,
    title = label,
    summary = packageName,
    installed = true,
)

/** 配置里有、但本机没有的条目：前缀规则，或者目标还没装。两种都不能默默丢掉。 */
private fun String.toManualEntry(option: HookOption.AppList) = PickerEntry(
    packageName = this,
    title = this,
    summary = if (option.isPattern(this)) PATTERN_HINT else "未安装",
    installed = false,
)

/** 列表高度：窗口的一部分，并夹在一个上下界里。 */
@Composable
private fun rememberListHeight(): Dp {
    val density = LocalDensity.current
    val height = LocalWindowInfo.current.containerSize.height
    return remember(density, height) {
        with(density) { (height.toDp() * LIST_HEIGHT_RATIO).coerceIn(LIST_MIN, LIST_MAX) }
    }
}

private const val PATTERN_HINT = "前缀规则：匹配所有以此开头的包名"

private val SCOPE_TABS = listOf("常用", "全部")

/** 弹层四周留白，与其他编辑器一致。 */
private val SHEET_MARGIN = DpSize(24.dp, 20.dp)

// 纵向节奏：说明 → 搜索 → 分段 → 列表 → 按钮，间距逐级放大一点，
// 让「一组控件」和「下一块内容」区分得开。
private val SEARCH_GAP = 14.dp
private val TAB_GAP = 10.dp
private val LIST_GAP = 12.dp
private val BUTTON_GAP = 16.dp

/** 段标题：上下都收一点，卡片里不该出现大段空白。 */
private val TITLE_MARGIN = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)

/** 列表首尾各留一点，首行末行不至于贴着卡片边缘。 */
private val LIST_PADDING = PaddingValues(vertical = 6.dp)

/** 行内缩进用 miuix 列表的常规值：外面是卡片，不能再让内容贴边。 */
private val ROW_MARGIN = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

private val ICON_SIZE = 38.dp
private val ICON_CORNER = 11.dp

private const val BADGE_TINT = 0.14f
private val BADGE_CORNER = 8.dp

private val LIST_MIN = 240.dp
private val LIST_MAX = 460.dp
private const val LIST_HEIGHT_RATIO = 0.44f

private val BOTTOM_SLACK = 8.dp

/** 弹层进场大致用时。列表等它走完再铺，避免两件事挤在同几帧里。 */
private const val ENTER_MILLIS = 220L
