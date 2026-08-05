package com.chuanyi.hooker.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.ImageLoader
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.core.HookPreset
import com.chuanyi.hooker.data.FrameworkService
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.data.RootShell
import com.chuanyi.hooker.data.RunningTarget
import com.chuanyi.hooker.ui.Motion
import com.chuanyi.hooker.ui.component.AnimatedLabel
import com.chuanyi.hooker.ui.component.AnimatedNumber
import com.chuanyi.hooker.ui.component.AppIcon
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.component.KeyMapEditorSheet
import com.chuanyi.hooker.ui.component.OptionEditDialog
import com.chuanyi.hooker.ui.component.rememberAppIconLoader
import com.chuanyi.hooker.ui.crossFade
import com.chuanyi.hooker.ui.motionItem
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.rememberAppListPermissionState
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 二级页面：一个 hooker 的开关、作用域、正在被注入的进程，以及功能列表。
 *
 * 三层状态在这里汇合，缺一个功能就不生效，所以要在同一屏里说清楚：
 *
 * | 层 | 归谁管 | 这一页怎么呈现 |
 * |---|---|---|
 * | 模块开关 / 功能开关 | 模块自己（远程 SharedPreferences） | 顶部卡片与「功能」段的 Switch |
 * | 作用域 | Xposed 框架 | 「作用域」段，可直接申请/移除 |
 * | 进程是否已装载新配置 | 目标应用的生命周期 | 「运行中的进程」+「让改动生效」 |
 *
 * ## 关于这一屏的过渡
 *
 * 这一页几乎每个值都会在用户没动手的时候自己变：回到前台会重拉作用域和进程列表，
 * 框架的作用域回调是异步的，root 命令要跑几百毫秒。**没有过渡的话，这些变化表现
 * 为文字原地跳字、行凭空出现、卡片高度瞬间弹跳**，也就是「生硬」的实际来源。
 *
 * 所以这一屏的动画不是装饰，而是按「哪些东西会自己变」逐个补上的：
 *
 *  * 会换文案的行一律走 [AnimatedLabel]，不用裸 `Text`；
 *  * 会增减行的卡片挂 `animateContentSize`，高度变化由弹簧接住；
 *  * LazyColumn 每项都有稳定 key 并挂 [motionItem]，整段出现/消失才有进出；
 *  * 异步操作（重启、热重载、申请作用域）在对应行上转圈，而不是只改一句摘要；
 *  * 曲线全部来自 [Motion]，一处调手感，全屏一致。
 */
@Composable
fun HookerDetailScreen(
    hooker: AppHooker,
    settings: ModuleSettings,
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val framework = settings.framework
    val iconLoader = rememberAppIconLoader()

    val revision = settings.revision
    val hookerOn = remember(revision, hooker) { settings.isHookerEnabled(hooker.id) }
    val featureStates = remember(revision, hooker) {
        hooker.features.associate {
            it.id to settings.isFeatureEnabled(hooker.id, it.id, it.defaultEnabled)
        }
    }
    val activeCount = featureStates.count { it.value }

    // 取值设置。两份：一份给界面看（已渲染成人话），一份给编辑框（原始值）。
    // 都在这里算好，LazyColumn 的 item lambda 是独立重组域，值必须由外层捕获。
    val optionShown = remember(revision, hooker) {
        hooker.options.associate { it.key to settings.displayOf(hooker.id, it) }
    }
    val optionRaw = remember(revision, hooker) {
        hooker.options.associate { it.key to settings.rawOf(hooker.id, it) }
    }
    val optionValues = remember(revision, hooker) {
        hooker.options.associate { it.key to settings.valueOf(hooker.id, it) }
    }
    val matchedPreset = remember(revision, hooker) { settings.matchedPreset(hooker) }

    // 编辑器是**常驻组合、靠 show 切换**的（miuix 的弹层都这样），所以关掉之后还得
    // 留着最后编辑的那一项 —— 退场动画期间它仍要有内容可画，否则收起时会先闪成空白。
    var editing by remember(hooker) { mutableStateOf<HookOption?>(null) }
    var lastEdited by remember(hooker) { mutableStateOf<HookOption?>(null) }
    LaunchedEffect(editing) { editing?.let { lastEdited = it } }
    val editTarget = editing ?: lastEdited

    // 只对装着的目标谈作用域和重启。权限参与 key 的理由同 rememberHookerOverviews：
    // 被系统拦掉时 getPackageInfo 抛的异常和真没装一样。
    val permission = rememberAppListPermissionState()
    val installedTargets = remember(hooker, context, permission.isGranted) {
        hooker.targetPackages.filter { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }
    // 顶部卡片那个图标要有归属：优先已装的那个包，都没装就拿第一个声明的包画首字母占位。
    val heroPackage = installedTargets.firstOrNull()
        ?: hooker.targetPackages.firstOrNull()
        ?: hooker.id

    // 作用域可能是在框架的系统弹窗里改的，进程列表更是随时在变 —— 回前台必刷。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { framework.refresh() }

    val targets = framework.targetsOf(hooker.targetPackages)
    val scopeState = when {
        !framework.isBound || installedTargets.isEmpty() -> ScopeState.Unknown
        framework.missingScope(installedTargets).isEmpty() -> ScopeState.Granted
        else -> ScopeState.Missing
    }

    val actions = remember(scope, framework) { DetailActions(scope, framework) }

    // 成功的播报自己退场；进行中的等结果顶掉它，失败的留着让用户看完。
    val notice = actions.notice
    LaunchedEffect(notice) {
        if (notice == null || notice.tone != NoticeTone.Success) return@LaunchedEffect
        delay(NOTICE_LINGER_MILLIS)
        actions.dismissIfStill(notice)
    }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = hooker.displayName,
                subtitle = hooker.targetPackages.joinToString("、"),
                onBack = { navigator.pop() },
                scrollBehavior = scrollBehavior,
                actions = { RefreshAction(framework) },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
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
            item(key = "hero") {
                HeroCard(
                    hooker = hooker,
                    packageName = heroPackage,
                    installed = installedTargets.isNotEmpty(),
                    iconLoader = iconLoader,
                    enabled = hookerOn,
                    activeCount = activeCount,
                    runningCount = targets.size,
                    scopeState = scopeState,
                    onCheckedChange = { settings.setHookerEnabled(hooker.id, it) },
                    modifier = motionItem(),
                )
            }

            // 播报常驻一项：藏起来时高度为 0。这样它的出现与消失由自己的过渡控制，
            // 而不是靠增删 item —— 后者在 LazyColumn 里会把下面的内容整段顶走。
            item(key = "notice") {
                NoticeBanner(
                    notice = notice,
                    onDismiss = actions::dismiss,
                    modifier = motionItem(),
                )
            }

            // --- 作用域 ----------------------------------------------------
            if (installedTargets.isNotEmpty()) {
                item(key = "scope-title") { SmallTitle("作用域", modifier = motionItem()) }
                item(key = "scope") {
                    ScopeSection(
                        framework = framework,
                        packages = installedTargets,
                        modifier = motionItem(),
                    )
                }
            }

            // --- 运行中的进程 ----------------------------------------------
            item(key = "process-title") { SmallTitle("运行中的进程", modifier = motionItem()) }
            item(key = "process") {
                ProcessSection(
                    framework = framework,
                    targets = targets,
                    onHotReload = actions::hotReload,
                    modifier = motionItem(),
                )
            }

            // --- 让改动生效 ------------------------------------------------
            if (installedTargets.isNotEmpty()) {
                item(key = "effect-title") { SmallTitle("让改动生效", modifier = motionItem()) }
                item(key = "effect") {
                    RestartSection(
                        packages = installedTargets,
                        busy = actions.busy,
                        onRestart = actions::restart,
                        onForceStop = actions::forceStop,
                        modifier = motionItem(),
                    )
                }
            }

            // --- 快捷预设 --------------------------------------------------
            if (hooker.presets.isNotEmpty()) {
                item(key = "preset-title") { SmallTitle("快捷预设", modifier = motionItem()) }
                item(key = "preset") {
                    PresetSection(
                        presets = hooker.presets,
                        matched = matchedPreset,
                        enabled = hookerOn,
                        onApply = { settings.applyPreset(hooker, it) },
                        modifier = motionItem(),
                    )
                }
            }

            // --- 功能 ------------------------------------------------------
            item(key = "features-title") { SmallTitle("功能", modifier = motionItem()) }
            item(key = "features") {
                FeatureSection(
                    hooker = hooker,
                    featureStates = featureStates,
                    enabled = hookerOn,
                    onToggle = { featureId, value ->
                        settings.setFeatureEnabled(hooker.id, featureId, value)
                    },
                    modifier = motionItem(),
                )
            }

            // --- 设置 ------------------------------------------------------
            if (hooker.options.isNotEmpty()) {
                item(key = "options-title") { SmallTitle("设置", modifier = motionItem()) }
                item(key = "options") {
                    OptionSection(
                        options = hooker.options,
                        shown = optionShown,
                        values = optionValues,
                        enabled = hookerOn,
                        featureStates = featureStates,
                        onEdit = { editing = it },
                        onPick = { option, value -> settings.writeOption(hooker.id, option, value) },
                        modifier = motionItem(),
                    )
                }
            }

            item(key = "footer") {
                FooterNote(footerFor(hooker), modifier = motionItem())
            }
        }
    }

    // 映射表有自己的编辑器（一张能点的键盘），其余走通用的输入弹窗。
    OptionEditDialog(
        show = editing != null && editing !is HookOption.KeyMap,
        option = editTarget?.takeIf { it !is HookOption.KeyMap },
        current = editTarget?.let { optionRaw[it.key] }.orEmpty(),
        onDismiss = { editing = null },
        onConfirm = { text ->
            editing?.let { settings.writeOption(hooker.id, it, text) }
            editing = null
        },
    )
    KeyMapEditorSheet(
        show = editing is HookOption.KeyMap,
        option = editTarget as? HookOption.KeyMap,
        current = editTarget?.let { optionRaw[it.key] }.orEmpty(),
        onDismiss = { editing = null },
        onConfirm = { text ->
            editing?.let { settings.writeOption(hooker.id, it, text) }
            editing = null
        },
    )
}

// ---------------------------------------------------------------------------
// 状态模型
// ---------------------------------------------------------------------------

/** 框架侧作用域的三态。[Unknown] = 服务没连上或没有已装的目标，无从判断。 */
private enum class ScopeState { Unknown, Granted, Missing }

private enum class NoticeTone { Progress, Success, Failure }

/**
 * 一条操作播报。
 *
 * [seq] 是世代号：同一句话再来一次也要被当成新的一条，否则重复点「重启」时
 * 自动退场的计时不会重置。
 */
@Immutable
private data class Notice(val seq: Int, val text: String, val tone: NoticeTone)

private enum class BusyKind { Restart, Stop }

@Immutable
private data class BusyAction(val packageName: String, val kind: BusyKind)

/**
 * 这一屏所有异步操作的落点。
 *
 * 单独成类不是为了分层，是为了让「谁在忙」和「播报什么」这两个状态跟改它们的
 * 代码待在一起 —— 三个操作各有各的成功/失败措辞，散在 composable 里会把主体
 * 结构淹掉。
 *
 * 标 [Stable]：两个属性都是 snapshot state，实例在 [remember] 里固定不变，
 * 传给下面的卡片时不会让它们变成不可跳过。
 */
@Stable
private class DetailActions(
    private val scope: CoroutineScope,
    private val framework: FrameworkService,
) {

    var notice by mutableStateOf<Notice?>(null)
        private set

    /**
     * 正在执行的那一个包 + 动作。
     *
     * 只有一个槽位（一次只跑一条 root 命令），但记到「哪个包 + 哪个动作」这么细，
     * 是为了让转圈能画在用户刚点的那一行上。只存一个 boolean 的话，四个按钮会一起转。
     */
    var busy by mutableStateOf<BusyAction?>(null)
        private set

    private var seq = 0

    fun dismiss() {
        notice = null
    }

    /** 只有还是同一条时才收起来：等待期间可能已经被新的播报顶掉了。 */
    fun dismissIfStill(target: Notice) {
        if (notice?.seq == target.seq) notice = null
    }

    private fun post(text: String, tone: NoticeTone) {
        seq += 1
        notice = Notice(seq, text, tone)
    }

    fun restart(packageName: String) {
        busy = BusyAction(packageName, BusyKind.Restart)
        post("正在重启 $packageName…", NoticeTone.Progress)
        scope.launch {
            val outcome = RootShell.restart(packageName)
            if (outcome.isSuccess) {
                post("$packageName 已重启，改动生效", NoticeTone.Success)
            } else {
                post("重启失败：${outcome.message}", NoticeTone.Failure)
            }
            busy = null
            framework.refresh()
        }
    }

    fun forceStop(packageName: String) {
        busy = BusyAction(packageName, BusyKind.Stop)
        post("正在停止 $packageName…", NoticeTone.Progress)
        scope.launch {
            val outcome = RootShell.forceStop(packageName)
            if (outcome.isSuccess) {
                post("$packageName 已停止，下次打开时生效", NoticeTone.Success)
            } else {
                post("停止失败：${outcome.message}", NoticeTone.Failure)
            }
            busy = null
            framework.refresh()
        }
    }

    fun hotReload(target: RunningTarget) {
        post("正在热重载 ${target.processName}…", NoticeTone.Progress)
        framework.hotReload(target) { result ->
            val succeeded = result.status() == HotReloadResult.Status.SUCCEEDED
            val detail = result.message() ?: result.status().name
            post(
                "热重载 ${target.processName}：$detail",
                if (succeeded) NoticeTone.Success else NoticeTone.Failure,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部
// ---------------------------------------------------------------------------

/**
 * 顶栏右侧的刷新。
 *
 * 转圈直接绑 [FrameworkService.isRefreshing]：这个页面回到前台会自动刷新一次，
 * 没有一个转动的图标的话，作用域和进程列表就是无缘无故自己变了。
 *
 * 停下时先把当前这一圈转完再归零 —— 半路刹住比不转还显得卡。角度只在
 * `graphicsLayer` 里读，所以整段动画不触发任何重组。
 */
@Composable
private fun RefreshAction(framework: FrameworkService) {
    val spin = remember { Animatable(0f) }
    val refreshing = framework.isRefreshing

    LaunchedEffect(refreshing) {
        if (refreshing) {
            while (isActive) {
                spin.animateTo(spin.value + 360f, tween(SPIN_MILLIS, easing = LinearEasing))
            }
        } else if (spin.value != 0f) {
            spin.animateTo(ceil(spin.value / 360f) * 360f, Motion.scalar)
            spin.snapTo(0f)
        }
    }

    val tint by animateColorAsState(
        targetValue = if (framework.isBound) {
            MiuixTheme.colorScheme.onBackground
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
        animationSpec = Motion.tint,
        label = "refreshTint",
    )

    IconButton(onClick = framework::refresh, enabled = framework.isBound) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = "刷新",
            tint = tint,
            modifier = Modifier.graphicsLayer { rotationZ = spin.value },
        )
    }
}

/**
 * 顶部卡片：图标、总开关、一句「现在到底生不生效」，以及功能计数条。
 *
 * 那一句摘要是这一页信息量最大的一行 —— 它把「没装 / 关了 / 不在作用域 / 一项都
 * 没开 / 已注入 N 个进程」这条判定链的结论直接说出来，用户不用自己把下面三段
 * 拼起来。顺序即优先级：先说不可能生效的原因，再说生效到什么程度。
 */
@Composable
private fun HeroCard(
    hooker: AppHooker,
    packageName: String,
    installed: Boolean,
    iconLoader: ImageLoader,
    enabled: Boolean,
    activeCount: Int,
    runningCount: Int,
    scopeState: ScopeState,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = hooker.features.size
    val accent = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val summary: String
    val summaryTint: Color
    when {
        !installed -> {
            summary = "目标应用没有安装"
            summaryTint = muted
        }

        !enabled -> {
            summary = "已关闭，不会注入目标应用"
            summaryTint = muted
        }

        scopeState == ScopeState.Missing -> {
            summary = "不在框架作用域内，功能不会生效"
            summaryTint = MiuixTheme.colorScheme.error
        }

        activeCount == 0 -> {
            summary = "一项功能都没开"
            summaryTint = muted
        }

        runningCount > 0 -> {
            summary = "已注入 $runningCount 个进程"
            summaryTint = accent
        }

        else -> {
            summary = "等目标应用下次启动时注入"
            summaryTint = muted
        }
    }

    // 关掉的模块不该和开着的一样显眼；但也不该消失 —— 变淡，不是隐藏。
    val dim by animateFloatAsState(
        targetValue = if (enabled && installed) 1f else DIMMED_ALPHA,
        animationSpec = Motion.scalar,
        label = "heroDim",
    )
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 0f else activeCount.toFloat() / total,
        animationSpec = Motion.scalar,
        label = "heroProgress",
    )

    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        SwitchRow(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            title = "启用 ${hooker.displayName}",
            summary = summary,
            summaryColor = summaryTint,
            glyph = {
                AppIcon(
                    packageName = packageName,
                    fallbackLabel = hooker.displayName,
                    imageLoader = iconLoader,
                    installed = installed,
                    modifier = Modifier.graphicsLayer { alpha = dim },
                )
            },
        )

        if (hooker.description.isNotEmpty()) {
            Text(
                text = hooker.description,
                style = MiuixTheme.textStyles.body2,
                color = muted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            )
        }

        if (total > 0) {
            Column(
                // 上面那行自带 16dp 内边距，这里只补 4dp —— 两处都写 12 的话，
                // 没有描述文字时开关和进度条之间会空出一大截。
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                    .graphicsLayer { alpha = dim },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "已开启的功能", style = MiuixTheme.textStyles.body2, color = muted)
                    Spacer(Modifier.weight(1f))
                    AnimatedNumber(
                        value = activeCount,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                        label = "heroActiveCount",
                    )
                    Text(text = " / $total", style = MiuixTheme.textStyles.body2, color = muted)
                }
                LinearProgressIndicator(
                    modifier = Modifier.padding(top = 8.dp),
                    progress = progress,
                )
            }
        }
    }
}

/**
 * 操作播报。
 *
 * `contentKey` 只认「有没有」，所以从「正在重启…」变成「已重启」时卡片本身不
 * 重建 —— 图标、颜色、文字各自在原地过渡。整张卡换掉的话，一次成功的操作会被
 * 演成两张卡在打架。
 *
 * 点一下收起：失败的播报不自动消失，得给用户一个关掉的办法。
 */
@Composable
private fun NoticeBanner(
    notice: Notice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = notice,
        modifier = modifier,
        transitionSpec = { crossFade(sizeTransform = SizeTransform { _, _ -> Motion.bounds }) },
        contentKey = { it != null },
        label = "noticeBanner",
    ) { current ->
        if (current == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            NoticeCard(notice = current, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice, onDismiss: () -> Unit) {
    val target = when (notice.tone) {
        NoticeTone.Progress, NoticeTone.Success -> MiuixTheme.colorScheme.primary
        NoticeTone.Failure -> MiuixTheme.colorScheme.error
    }
    val accent by animateColorAsState(target, Motion.tint, label = "noticeAccent")

    Card(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onDismiss,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(GLYPH_SIZE)
                    .squircleBackground(accent.copy(alpha = GLYPH_TINT_ALPHA), GLYPH_CORNER),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = notice.tone,
                    transitionSpec = {
                        (fadeIn(Motion.alpha) + scaleIn(Motion.scalar, initialScale = 0.5f))
                            .togetherWith(fadeOut(Motion.alpha) + scaleOut(Motion.scalar, targetScale = 0.5f))
                            .using(null)
                    },
                    label = "noticeGlyph",
                ) { tone ->
                    when (tone) {
                        NoticeTone.Progress -> InfiniteProgressIndicator(color = accent, size = GLYPH_ICON)
                        NoticeTone.Success -> Icon(MiuixIcons.Ok, null, Modifier.size(GLYPH_ICON), accent)
                        NoticeTone.Failure -> Icon(MiuixIcons.Report, null, Modifier.size(GLYPH_ICON), accent)
                    }
                }
            }
            AnimatedLabel(
                text = notice.text,
                modifier = Modifier.padding(start = 14.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                label = "noticeText",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 作用域
// ---------------------------------------------------------------------------

/**
 * 逐包的作用域开关。
 *
 * 打开 = 向框架申请（会弹系统确认框），关闭 = 直接移出。这是模块这边唯一能影响
 * 框架侧作用域的途径，做成开关是因为它本质上就是个二元状态，而不是一次性动作。
 *
 * 申请在途时那一行禁用并转圈：框架的回调是异步的，只把摘要改成「等待确认」的话，
 * 用户看不出这一行和别的行有什么不同，会接着点。
 */
@Composable
private fun ScopeSection(
    framework: FrameworkService,
    packages: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        // animateContentSize 挂在**卡片内侧**：挂外面的话它自带的 clipToBounds 会
        // 用一个矩形去裁卡片，动画期间底部两个圆角会被削平。挂里面则是卡片跟着
        // 内容的动画高度重新测量，圆角始终是圆角。
        Column(Modifier.animateContentSize(animationSpec = Motion.follow, alignment = Alignment.TopStart)) {
            AnimatedContent(
                targetState = framework.isBound,
                transitionSpec = { crossFade() },
                label = "scopeBinding",
            ) { bound ->
                if (!bound) {
                    DetailRow(
                        title = "Xposed 服务未连接",
                        summary = "作用域由框架管理。模块未被启用，或框架不提供模块服务。",
                        glyph = { GlyphBadge(MiuixIcons.Link, MiuixTheme.colorScheme.onSurfaceVariantActions) },
                    )
                } else {
                    Column {
                        packages.forEach { pkg ->
                            key(pkg) { ScopeRow(framework = framework, packageName = pkg) }
                        }
                    }
                }
            }

            FailureRow(error = framework.lastError, title = "上一次作用域操作失败")
        }
    }
}

@Composable
private fun ScopeRow(framework: FrameworkService, packageName: String) {
    val pending = packageName in framework.pendingScope
    val inScope = framework.isInScope(packageName)

    SwitchRow(
        checked = inScope,
        onCheckedChange = { wanted ->
            if (wanted) framework.requestScope(listOf(packageName)) else framework.removeScope(listOf(packageName))
        },
        title = packageName,
        summary = when {
            pending -> "等待框架确认…"
            inScope -> "框架会把本模块注入这个应用"
            else -> "不在作用域内，功能不会生效"
        },
        summaryColor = if (!pending && !inScope) MiuixTheme.colorScheme.error else Color.Unspecified,
        // 申请在途时别让用户重复点：框架的回调是异步的。
        enabled = !pending,
        trailing = { InlineSpinner(visible = pending) },
    )
}

/**
 * 出错时才出现的一行。
 *
 * `contentKey` 认「有没有」而不是错误内容本身，所以退场时渲染的仍是最后那条
 * 错误 —— 直接对 `error` 取 key 的话，收起动画会先把文字变成空白。
 */
@Composable
private fun FailureRow(error: String?, title: String) {
    AnimatedContent(
        targetState = error,
        transitionSpec = { crossFade() },
        contentKey = { it != null },
        label = "failureRow",
    ) { current ->
        if (current == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            DetailRow(
                title = title,
                summary = current,
                glyph = { GlyphBadge(MiuixIcons.Report, MiuixTheme.colorScheme.error) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 运行中的进程
// ---------------------------------------------------------------------------

private enum class ProcessMode { NotBound, Unsupported, Empty, Running }

/**
 * 「运行中的进程」这一段要画的全部东西。
 *
 * 打包成一个值，是因为 [AnimatedContent] 退场时会拿**旧的** targetState 再渲染
 * 一遍出场内容。直接从外层闭包读 `targets`，从"有进程"切到"没进程"时那一帧读到
 * 的已经是空列表 —— 退场动画会先把列表清空再收起来，看起来像闪了一下。
 */
@Immutable
private data class ProcessSnapshot(
    val mode: ProcessMode,
    val targets: List<RunningTarget>,
    val apiVersion: Int,
)

/**
 * 正在被注入的进程。
 *
 * **热重载**（框架 API 102）换掉进程里的模块代码，不重启进程。官方文档写明它是
 * 给「模块 APK 更新了」用的，不要拿来传播配置变更，所以这里只对
 * [RunningTarget.isStale]（进程里跑的确实是旧一代模块）的进程给这个入口 ——
 * 那个按钮也因此是会自己出现和消失的，得有进出动画。
 *
 * 卡片内侧挂一层 `animateContentSize` 当唯一的高度负责人，里层的 [AnimatedContent]
 * 就不再各自管高度（[crossFade] 默认不带 `SizeTransform`）—— 两处同时 animate 高度
 * 会互相追赶。
 */
@Composable
private fun ProcessSection(
    framework: FrameworkService,
    targets: List<RunningTarget>,
    onHotReload: (RunningTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = ProcessSnapshot(
        mode = when {
            !framework.isBound -> ProcessMode.NotBound
            !framework.supportsRunningTargets -> ProcessMode.Unsupported
            targets.isEmpty() -> ProcessMode.Empty
            else -> ProcessMode.Running
        },
        targets = targets,
        apiVersion = framework.apiVersion,
    )

    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.animateContentSize(animationSpec = Motion.follow, alignment = Alignment.TopStart)) {
            AnimatedContent(
                targetState = snapshot,
                transitionSpec = { crossFade() },
                // 只有档位换了才做切换动画；同一档里进程增减靠外面那层
                // animateContentSize 接住，否则每次回前台刷新都要整段淡出淡入一次。
                contentKey = { it.mode },
                label = "processMode",
            ) { current ->
                when (current.mode) {
                    ProcessMode.NotBound -> DetailRow(
                        title = "未连接框架",
                        summary = "无法查询进程",
                        glyph = { GlyphBadge(MiuixIcons.Link, MiuixTheme.colorScheme.onSurfaceVariantActions) },
                    )

                    ProcessMode.Unsupported -> DetailRow(
                        title = "框架不支持进程查询",
                        summary = "需要 Xposed 服务 API 102，当前 ${current.apiVersion}",
                        glyph = { GlyphBadge(MiuixIcons.Info, MiuixTheme.colorScheme.onSurfaceVariantActions) },
                    )

                    ProcessMode.Empty -> DetailRow(
                        title = "没有正在运行的目标进程",
                        summary = "目标未启动，或不在作用域内",
                        glyph = { GlyphBadge(MiuixIcons.Layers, MiuixTheme.colorScheme.onSurfaceVariantActions) },
                    )

                    ProcessMode.Running -> Column {
                        current.targets.forEach { target ->
                            key(target.pid, target.processName) {
                                ProcessRow(target = target, onHotReload = onHotReload)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(target: RunningTarget, onHotReload: (RunningTarget) -> Unit) {
    val reloading = target.state == HookedTarget.State.RELOADING
    val stateColor by animateColorAsState(
        targetValue = when (target.state) {
            HookedTarget.State.UP_TO_DATE, HookedTarget.State.RELOADING -> MiuixTheme.colorScheme.primary
            HookedTarget.State.STALE -> MiuixTheme.colorScheme.onSurfaceVariantActions
            HookedTarget.State.FAILED -> MiuixTheme.colorScheme.error
        },
        animationSpec = Motion.tint,
        label = "processState",
    )

    DetailRow(
        title = target.processName + if (target.isSubProcess) "（子进程）" else "",
        summary = "pid ${target.pid} · ${target.state.describe()}" +
            if (target.loadedVersionCode > 0) " · 已装载版本 ${target.loadedVersionCode}" else "",
        glyph = { ProcessGlyph(reloading = reloading, color = stateColor) },
        // 只有确实过期的进程才值得热重载，其余点了也是 UNSUPPORTED。
        onClick = if (target.isStale) ({ onHotReload(target) }) else null,
        enabled = !reloading,
        trailing = {
            AnimatedVisibility(
                visible = target.isStale,
                modifier = Modifier.align(Alignment.CenterVertically),
                enter = Motion.enterInline,
                exit = Motion.exitInline,
                label = "hotReloadAction",
            ) {
                TextButton(
                    text = "热重载",
                    onClick = { onHotReload(target) },
                    cornerRadius = 10.dp,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        },
    )
}

/** 进程状态指示：正常是一个点，热重载中换成转圈。 */
@Composable
private fun ProcessGlyph(reloading: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(GLYPH_SIZE)
            .squircleBackground(color.copy(alpha = GLYPH_TINT_ALPHA), GLYPH_CORNER),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = reloading,
            transitionSpec = {
                (fadeIn(Motion.alpha) + scaleIn(Motion.scalar, initialScale = 0.4f))
                    .togetherWith(fadeOut(Motion.alpha) + scaleOut(Motion.scalar, targetScale = 0.4f))
                    .using(null)
            },
            label = "processGlyph",
        ) { busy ->
            if (busy) {
                InfiniteProgressIndicator(color = color, size = GLYPH_ICON)
            } else {
                Spacer(Modifier.size(DOT_SIZE).squircleBackground(color, DOT_SIZE / 2))
            }
        }
    }
}

private fun HookedTarget.State.describe(): String = when (this) {
    HookedTarget.State.UP_TO_DATE -> "已是最新"
    HookedTarget.State.STALE -> "模块已更新，进程里还是旧代码"
    HookedTarget.State.RELOADING -> "正在热重载"
    HookedTarget.State.FAILED -> "上次热重载失败"
}

// ---------------------------------------------------------------------------
// 让改动生效
// ---------------------------------------------------------------------------

/**
 * 让配置改动生效的两条路径，都走 libsu 的 root shell。
 *
 * 没有任何非 root 途径能停掉别的应用的进程，所以这一段在没有 root 时只能如实
 * 说明，而不是把入口藏起来 —— 藏起来用户只会以为改动本来就该立刻生效。
 *
 * 有操作在跑时**所有**行都禁用（一次只跑一条 root 命令，[DetailActions.busy] 也
 * 只有一个槽位），但转圈只画在真正在跑的那一行上 —— [busy] 精确到「哪个包 + 哪个
 * 动作」就是为这个。全部一起转的话，用户分不清自己点的到底是哪一条。
 */
@Composable
private fun RestartSection(
    packages: List<String>,
    busy: BusyAction?,
    onRestart: (String) -> Unit,
    onForceStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootDenied = RootShell.access == RootShell.Access.Denied

    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        packages.forEach { pkg ->
            key(pkg) {
                val restarting = busy?.packageName == pkg && busy.kind == BusyKind.Restart
                val stopping = busy?.packageName == pkg && busy.kind == BusyKind.Stop

                DetailRow(
                    title = "重启 $pkg",
                    summary = when {
                        restarting -> "正在停止并重新拉起…"
                        rootDenied -> "需要 root：${RootShell.lastError ?: "授权被拒绝"}"
                        else -> "停止并重新拉起，让改动立即生效"
                    },
                    glyph = { GlyphBadge(MiuixIcons.Refresh, MiuixTheme.colorScheme.primary) },
                    onClick = { onRestart(pkg) },
                    enabled = busy == null,
                    trailing = { InlineSpinner(visible = restarting) },
                )
                DetailRow(
                    title = "仅停止 $pkg",
                    summary = if (stopping) "正在停止…" else "下次由用户自己打开时生效",
                    glyph = { GlyphBadge(MiuixIcons.Pause, MiuixTheme.colorScheme.onSurfaceVariantActions) },
                    onClick = { onForceStop(pkg) },
                    enabled = busy == null,
                    trailing = { InlineSpinner(visible = stopping) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 功能
// ---------------------------------------------------------------------------

/**
 * 功能开关。走 miuix 自己的 `SwitchPreference`，手感（按压、开关动画、禁用配色）
 * 与系统设置一致，不必自己复刻。
 *
 * `requiresRestart = false` 的功能标出来：默认值是 true，所以标出来的是少数，
 * 噪音小，而底下那句脚注也才对得上。
 */
@Composable
private fun FeatureSection(
    hooker: AppHooker,
    featureStates: Map<String, Boolean>,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hooker.features.isEmpty()) {
        Card(modifier = modifier.padding(horizontal = 12.dp)) {
            DetailRow(
                title = "这个 hooker 没有可开关的功能",
                summary = "它要么整体生效，要么整体不生效",
                glyph = { GlyphBadge(MiuixIcons.Info, MiuixTheme.colorScheme.onSurfaceVariantActions) },
            )
        }
        return
    }

    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        hooker.features.forEach { feature ->
            key(feature.id) {
                SwitchPreference(
                    checked = featureStates[feature.id] ?: feature.defaultEnabled,
                    onCheckedChange = { onToggle(feature.id, it) },
                    title = feature.title,
                    summary = feature.summary,
                    enabled = enabled,
                    endActions = {
                        if (!feature.requiresRestart) InstantTag(enabled = enabled)
                    },
                )
            }
        }
    }
}

/**
 * 快捷预设。
 *
 * 一个 hooker 有五个开关加六个取值时，「我该怎么配」本身就成了负担 —— 用户想要的
 * 是「剪贴板别再一小时就没了」，不是逐项理解每个数字。这一段把常见诉求直接摆成
 * 选项，选中即全量套用。
 *
 * 末尾那个「自定义」不可选，只是用来表达「你现在的配置不等于任何一套预设」——
 * 改过任何一项之后它会自己亮起来，用户不会以为自己还在某套预设上。
 */
@Composable
private fun PresetSection(
    presets: List<HookPreset>,
    matched: HookPreset?,
    enabled: Boolean,
    onApply: (HookPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 每一项都带上它到底改了什么 —— 光有「推荐 / 绝不丢失」这样的名字，
    // 用户得点开一次才知道差别在哪。
    val items = remember(presets) {
        presets.map { DropdownItem(text = it.title, summary = it.summary) } +
            DropdownItem(text = CUSTOM_PRESET, summary = "手动调过的配置", enabled = false)
    }
    val index = presets.indexOfFirst { it.id == matched?.id }.takeIf { it >= 0 } ?: presets.size

    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        WindowSpinnerPreference(
            items = items,
            selectedIndex = index,
            title = "方案",
            summary = matched?.summary ?: "当前配置是自己调的。选一套预设会覆盖全部开关与取值",
            enabled = enabled,
            onSelectedIndexChange = { picked -> presets.getOrNull(picked)?.let(onApply) },
        )
    }
}

/**
 * 取值设置。
 *
 * 三种形态各用各的控件，因为它们问的其实是三种不同的问题：
 *
 *  * [HookOption.Choice] —— 常用值就那么几个（有效期、灵敏度），下拉选一下最快；
 *  * [HookOption.Number] —— 连续量（条数），滑块能一边拖一边看效果，比反复开关
 *    输入框快得多；点标题进精确输入，兼顾「我就要 37」这种要求；
 *  * [HookOption.Text] —— 自由文本（按键映射表），只能弹输入框。
 *
 * [HookOption.featureId] 非空的行跟着那个功能一起禁用：一个不生效的数字摆在那里
 * 只会让人以为它还管用。
 */
@Composable
private fun OptionSection(
    options: List<HookOption>,
    shown: Map<String, String>,
    values: Map<String, Int>,
    enabled: Boolean,
    featureStates: Map<String, Boolean>,
    onEdit: (HookOption) -> Unit,
    onPick: (HookOption, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp)) {
        options.forEach { option ->
            key(option.key) {
                val live = enabled && (option.featureId?.let { featureStates[it] != false } ?: true)
                val current = values[option.key] ?: 0
                when (option) {
                    is HookOption.Choice -> ChoiceRow(option, current, live, onEdit, onPick)
                    is HookOption.Number -> NumberRow(option, current, live, onEdit, onPick)
                    is HookOption.Text, is HookOption.KeyMap -> ArrowPreference(
                        title = option.title,
                        summary = option.summary,
                        enabled = live,
                        onClick = { onEdit(option) },
                        endActions = { OptionValue(shown[option.key].orEmpty(), live) },
                    )
                }
            }
        }
    }
}

/**
 * 档位选择。
 *
 * [HookOption.Choice.custom] 非空时列表末尾多一项「自定义…」，选中它弹出数字输入
 * —— 于是既有快捷档位，又不失去填任意值的能力。当前值不在任何档上时，选中态就停在
 * 那一项上，行尾显示实际数值。
 */
@Composable
private fun ChoiceRow(
    option: HookOption.Choice,
    current: Int,
    enabled: Boolean,
    onEdit: (HookOption) -> Unit,
    onPick: (HookOption, Int) -> Unit,
) {
    val hasCustom = option.custom != null
    val matched = option.indexOf(current)
    val items = remember(option, current) {
        option.entries.map { DropdownItem(text = it.label) } +
            if (hasCustom) {
                // 当前值不在任何档上时，把它显示在「自定义」这一项里，
                // 否则用户只看到「自定义…」三个字，不知道现在到底是多少。
                listOf(
                    DropdownItem(
                        text = CUSTOM_VALUE,
                        summary = if (matched < 0) "当前 ${option.labelOf(current)}" else null,
                    ),
                )
            } else {
                emptyList()
            }
    }
    val index = if (matched >= 0) matched else if (hasCustom) option.entries.size else 0

    WindowSpinnerPreference(
        items = items,
        selectedIndex = index,
        title = option.title,
        summary = option.summary,
        enabled = enabled,
        onSelectedIndexChange = { picked ->
            val entry = option.entries.getOrNull(picked)
            if (entry != null) onPick(option, entry.value) else onEdit(option)
        },
    )
}

/**
 * 连续量。
 *
 * 拖动过程中只改本地状态，松手才写回配置 —— 每一帧都写一次远端 preferences 会把
 * binder 打满，而且中间那些值用户并不想要。
 */
@Composable
private fun NumberRow(
    option: HookOption.Number,
    current: Int,
    enabled: Boolean,
    onEdit: (HookOption) -> Unit,
    onPick: (HookOption, Int) -> Unit,
) {
    var live by remember(option.key, current) { mutableFloatStateOf(current.toFloat()) }
    SliderPreference(
        value = live,
        onValueChange = { live = it },
        title = option.title,
        summary = option.summary,
        valueText = option.render(live.roundToInt()),
        enabled = enabled,
        valueRange = option.min.toFloat()..option.max.toFloat(),
        steps = option.sliderSteps,
        onValueChangeFinished = { onPick(option, live.roundToInt()) },
        onClick = { onEdit(option) },
    )
}

/** 行尾的当前值。换值时是过渡而不是跳字 —— 和这一屏其余会自己变的文字一致。 */
@Composable
private fun RowScope.OptionValue(text: String, enabled: Boolean) {
    val color by animateColorAsState(
        targetValue = if (enabled) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
        animationSpec = Motion.tint,
        label = "optionValue",
    )
    AnimatedLabel(
        text = text,
        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = color,
        label = "optionValueText",
    )
}

/** 「改完就生效」的标记。跟着行一起变灰，不然禁用时它会突兀地亮着。 */
@Composable
private fun RowScope.InstantTag(enabled: Boolean) {
    val color by animateColorAsState(
        targetValue = if (enabled) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
        animationSpec = Motion.tint,
        label = "instantTag",
    )
    Text(
        text = "立即生效",
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        modifier = Modifier
            .align(Alignment.CenterVertically)
            .squircleBackground(color.copy(alpha = GLYPH_TINT_ALPHA), 8.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun footerFor(hooker: AppHooker): String {
    val instant = hooker.features.count { !it.requiresRestart }
    return when {
        hooker.features.isEmpty() ->
            "改动在目标应用下次启动时生效。上面的「重启」可以直接完成这一步。"

        instant == 0 ->
            "改动在目标应用下次启动时生效。上面的「重启」可以直接完成这一步。"

        instant == hooker.features.size ->
            "这里的功能改完就生效，不需要重启目标应用。"

        else ->
            "标了「立即生效」的功能改完就生效；其余的要等目标应用下次启动，" +
                "上面的「重启」可以直接完成这一步。"
    }
}

// ---------------------------------------------------------------------------
// 行与零件
// ---------------------------------------------------------------------------

/**
 * 这一页所有列表行的底座。
 *
 * 和 miuix 的 `BasicComponent(title=, summary=)` 排版完全一致（字号、字重、
 * 两档颜色都照抄自 `BasicComponentDefaults`），区别只有两点，而这两点正是这一页
 * 需要的：
 *
 *  * 说明走 [AnimatedLabel]，换文案是过渡而不是跳字；
 *  * enabled 切换时颜色是渐变的，不是直接替。
 */
@Composable
private fun DetailRow(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null,
    summaryColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val disabled = MiuixTheme.colorScheme.disabledOnSecondaryVariant
    val titleTint by animateColorAsState(
        targetValue = if (enabled) MiuixTheme.colorScheme.onBackground else disabled,
        animationSpec = Motion.tint,
        label = "rowTitle",
    )
    val summaryTint by animateColorAsState(
        targetValue = if (enabled) {
            summaryColor.takeOrElse { MiuixTheme.colorScheme.onSurfaceVariantSummary }
        } else {
            disabled
        },
        animationSpec = Motion.tint,
        label = "rowSummary",
    )

    BasicComponent(
        modifier = modifier,
        startAction = glyph,
        endActions = trailing,
        onClick = onClick,
        role = role,
        enabled = enabled,
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = titleTint,
        )
        AnimatedLabel(
            text = summary,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = summaryTint,
            label = "rowSummaryText",
        )
    }
}

/** [DetailRow] + 一个 miuix Switch。等价于 `SwitchPreference`，但说明会动。 */
@Composable
private fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null,
    summaryColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    DetailRow(
        title = title,
        summary = summary,
        modifier = modifier,
        glyph = glyph,
        summaryColor = summaryColor,
        enabled = enabled,
        role = Role.Switch,
        onClick = { onCheckedChange(!checked) },
    ) {
        trailing()
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 行首的图标章：一块淡色平滑圆角底 + 一个 miuix 图标。 */
@Composable
private fun GlyphBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(GLYPH_SIZE)
            .squircleBackground(color.copy(alpha = GLYPH_TINT_ALPHA), GLYPH_CORNER),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(GLYPH_ICON),
            tint = color,
        )
    }
}

/** 行尾的执行中指示。从右侧挤进来，不是凭空占一格。 */
@Composable
private fun RowScope.InlineSpinner(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.CenterVertically),
        enter = Motion.enterInline,
        exit = Motion.exitInline,
        label = "inlineSpinner",
    ) {
        InfiniteProgressIndicator(
            modifier = Modifier.padding(end = 8.dp),
            color = MiuixTheme.colorScheme.primary,
            size = 20.dp,
        )
    }
}

// 手感与尺寸集中放这儿。
/** 预设列表末尾那一项：表达「现在不等于任何一套预设」，不可选中套用。 */
private const val CUSTOM_PRESET = "自定义"

/** 档位列表末尾那一项：选中它弹出精确输入。 */
private const val CUSTOM_VALUE = "自定义…"

/** 成功的播报在屏幕上停多久。够读完一句话，又不至于赖着不走。 */
private const val NOTICE_LINGER_MILLIS = 3_200L

/** 刷新图标转一圈的时间。 */
private const val SPIN_MILLIS = 900

/** 关掉的模块淡到这个程度。再淡就读不清了。 */
private const val DIMMED_ALPHA = 0.45f

/** 图标章底色的浓度。 */
private const val GLYPH_TINT_ALPHA = 0.14f

private val GLYPH_SIZE: Dp = 36.dp
private val GLYPH_CORNER: Dp = 12.dp
private val GLYPH_ICON: Dp = 18.dp
private val DOT_SIZE: Dp = 10.dp
