package com.chuanyi.hooker.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.chuanyi.hooker.core.HookerRegistry
import com.chuanyi.hooker.core.ModuleStatus
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.component.AppIcon
import com.chuanyi.hooker.ui.component.InfoCard
import com.chuanyi.hooker.ui.component.rememberAppIconLoader
import com.chuanyi.hooker.ui.model.HookerOverview
import com.chuanyi.hooker.ui.model.rememberHookerOverviews
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import com.chuanyi.hooker.ui.rememberAppListPermissionState
import com.chuanyi.hooker.ui.request
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 首页回答三个问题，别的都不放这儿：模块在不在工作、哪些应用正被改、怎么一键关掉。
 *
 * 框架版本、原生层、日志开关这些排查用的东西挪去了「关于」页。
 *
 * ## 关于这一屏的过渡
 *
 * 状态卡会因为两类事件重画：状态跨档（未启用 ↔ 运行中 ↔ 已暂停 ↔ 空闲），和同一档
 * 内部的数字变化（生效的应用数）。这两类的分量差很多，用同一种动画就会显得生硬 ——
 * 跨档时该有方向感，数字变了只该轻轻淡一下。[StatusCard] 的 `transitionSpec` 按
 * [StatusVisual.state] 是否相同来分流，就是为这个。
 *
 * 列表项一律带稳定 key 并挂 `animateItem()`：拨一下总开关会让「正在生效」整段
 * 出现或消失，没有进出动画的话下面的内容是瞬间被顶走的。
 */
@Composable
fun StatusScreen(
    settings: ModuleSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenApps: (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val iconLoader = rememberAppIconLoader()
    val permission = rememberAppListPermissionState()

    // 这两个值被本进程的 self-probe hook 掉，一个进程内固定不变，读一次即可。
    val probed = remember { ModuleStatus.isActivated() }
    val registryErrors = remember { HookerRegistry.errors() }

    val revision = settings.revision
    val masterEnabled = remember(revision) { settings.masterEnabled }
    val overviews = rememberHookerOverviews(settings, permission.isGranted)

    // 两个独立信号，任一成立就是激活了：
    //  * self-probe —— 需要模块自己的包也在作用域内，且 hook 要早于 UI 读取
    //  * 服务绑定   —— 框架只把 binder 交给已启用的模块，是更直接的判据
    // 只认前者会在「模块明明生效、状态卡却写着未激活」这种情况下误报。
    val activated = probed || settings.isFrameworkBound

    val live = overviews.filter { it.isLive(masterEnabled) }
    val state = when {
        !activated -> ModuleState.Inactive
        !masterEnabled -> ModuleState.Paused
        live.isEmpty() -> ModuleState.Idle
        else -> ModuleState.Running
    }

    val visual = remember(state, live) {
        StatusVisual(
            state = state,
            title = when (state) {
                ModuleState.Running -> "运行中"
                ModuleState.Paused -> "已暂停"
                ModuleState.Idle -> "没有应用在生效"
                ModuleState.Inactive -> "未启用"
            },
            detail = when (state) {
                ModuleState.Running ->
                    "${live.size} 个应用，${live.sumOf { it.activeFeatures }} 项功能"

                ModuleState.Paused -> "总开关已关闭"
                ModuleState.Idle -> "去「应用」里挑一个开启"
                ModuleState.Inactive -> "在 LSPosed 里勾选本模块，然后重启目标应用"
            },
        )
    }

    val showPermissionAlert = !permission.isGranted && overviews.any { !it.installed }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "status") {
            StatusCard(
                visual = visual,
                // 空着的时候整张卡就是「去挑一个」的入口，别让用户自己找底栏。
                onClick = onOpenApps.takeIf { state == ModuleState.Idle },
                modifier = Modifier.animateItem(),
            )
        }

        // 未激活是最要紧的一条：它一成立，下面所有「已开启 N 项」都是没生效的。
        // 所以排在其余提示之前。
        // 需要用户处理的问题。没问题时这一段完全不出现，不占版面 ——
        // 但出现和消失要有过渡，否则是「凭空插进来一张卡」。
        if (showPermissionAlert) {
            item(key = "alert-applist") {
                AlertRow(
                    title = "无法读取应用列表",
                    summary = "部分系统会拦掉这项查询，装着的应用也会显示成未安装",
                    action = "去授权",
                    onClick = { permission.request(context) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (!settings.isSynced) {
            item(key = "alert-sync") {
                InfoCard(
                    title = "设置还没生效",
                    body = settings.serviceError ?: "正在等待框架连接，改动暂时只存在本机。",
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (registryErrors.isNotEmpty()) {
            item(key = "alert-registry") {
                InfoCard(
                    title = "有功能没能加载",
                    body = registryErrors.joinToString("\n"),
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (live.isNotEmpty()) {
            item(key = "live-title") {
                SmallTitle("正在生效", modifier = Modifier.animateItem())
            }
            item(key = "live-card") {
                Card(
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 12.dp),
                ) {
                    live.forEach { overview ->
                        LiveAppRow(
                            overview = overview,
                            iconLoader = iconLoader,
                            onClick = { navigator.push(Route.HookerDetail(overview.id)) },
                        )
                    }
                }
            }
        }

        item(key = "master-title") {
            SmallTitle("总开关", modifier = Modifier.animateItem())
        }
        item(key = "master") {
            Card(
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = 12.dp),
            ) {
                SwitchPreference(
                    checked = masterEnabled,
                    onCheckedChange = { settings.masterEnabled = it },
                    title = "启用模块",
                    summary = "关闭后所有应用都不会被修改",
                )
            }
        }
    }
}

// 未激活（没通过群组校验）不在这一屏出现：那种情况下整个界面都不会被渲染，
// 由 MainActivity 直接换成全屏的 ActivationLockScreen。
private enum class ModuleState { Inactive, Paused, Idle, Running }

/**
 * 状态卡要显示的全部内容。
 *
 * 打包成一个值是为了让 [AnimatedContent] 只有一个 targetState —— 文字、方向、
 * 分量判定都从它推出来，不会出现「标题换了但说明还是旧的」这种半截过渡。
 */
@Immutable
private data class StatusVisual(
    val state: ModuleState,
    val title: String,
    val detail: String,
)

/** 状态卡：一眼看出能不能用，以及当前正在改什么。 */
@Composable
private fun StatusCard(
    visual: StatusVisual,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Inactive 是真故障（框架根本没加载本模块），用 error 色；其余三档都不是错误，
    // 靠指示器形态而不是颜色来区分 —— 四个状态全用同一个灰，是原来「看起来没变」
    // 的根源。
    val accent = when (visual.state) {
        ModuleState.Running -> MiuixTheme.colorScheme.primary
        ModuleState.Inactive -> MiuixTheme.colorScheme.error
        ModuleState.Paused, ModuleState.Idle -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val color by animateColorAsState(accent, tween(COLOR_MILLIS), label = "statusAccent")

    Card(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
        // 可点的时候按下要有回弹，不然「这张卡有时能点」完全没有提示。
        pressFeedbackType = if (onClick != null) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(state = visual.state, color = color)

            AnimatedContent(
                targetState = visual,
                transitionSpec = {
                    if (initialState.state == targetState.state) {
                        // 同一档里只是数字变了（生效的应用数）。这时候整行文字上下滑
                        // 会显得小题大做，轻轻换一下就够。
                        fadeIn(tween(FADE_IN_MILLIS))
                            .togetherWith(fadeOut(tween(FADE_OUT_MILLIS)))
                    } else {
                        // 跨档。按枚举顺序判方向：状态"变好"时从下往上推，"变差"时
                        // 反过来，让过渡带方向感而不是原地闪一下。
                        val forward = targetState.state.ordinal > initialState.state.ordinal
                        val enterOffset = { height: Int -> if (forward) height / 3 else -height / 3 }
                        val exitOffset = { height: Int -> if (forward) -height / 3 else height / 3 }
                        (
                            fadeIn(tween(SLIDE_MILLIS, delayMillis = FADE_OUT_MILLIS)) +
                                slideInVertically(
                                    tween(SLIDE_MILLIS, delayMillis = FADE_OUT_MILLIS),
                                    enterOffset,
                                )
                            ).togetherWith(
                            fadeOut(tween(FADE_OUT_MILLIS)) +
                                slideOutVertically(tween(FADE_OUT_MILLIS), exitOffset),
                        )
                    }
                        // 四种状态的说明文字长度差很多（"总开关已关闭" 对
                        // "在 LSPosed 里勾选本模块，然后重启目标应用"），换行数一变
                        // 卡片高度就会跳。SizeTransform 把这段高度变化也接管掉。
                        .using(SizeTransform(clip = false) { _, _ -> tween(SIZE_MILLIS) })
                },
                modifier = Modifier.padding(start = 16.dp),
                label = "statusText",
            ) { target ->
                Column {
                    Text(
                        text = target.title,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = target.detail,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * 状态指示器。
 *
 * 三种形态，对应三种语义：
 *
 * ```
 * Running          实心点 + 向外扩散的环   —— 正在做事
 * Idle             空心环                 —— 待命，但没事可做
 * Paused/Inactive  实心点                 —— 停着
 * ```
 *
 * 呼吸和实心度都只在 `drawBehind` 里读，所以动画只走绘制阶段，一次重组都不会触发。
 * 这一屏是常驻页面，无限动画必须这么写。
 */
@Composable
private fun StatusIndicator(
    state: ModuleState,
    color: Color,
) {
    // 空心 <-> 实心之间连续过渡，而不是两套图形直接换。
    val fill by animateFloatAsState(
        targetValue = if (state == ModuleState.Idle) 0f else 1f,
        animationSpec = tween(COLOR_MILLIS),
        label = "statusFill",
    )
    // 扩散环的强度。非 Running 时归零，环自然收掉，不需要额外的显隐分支。
    val haloStrength by animateFloatAsState(
        targetValue = if (state == ModuleState.Running) 1f else 0f,
        animationSpec = tween(COLOR_MILLIS),
        label = "statusHalo",
    )

    val breathing = rememberInfiniteTransition(label = "statusBreath")
    val breath by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "statusBreathValue",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .squircleBackground(color = color.copy(alpha = 0.14f), cornerRadius = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val center = this.center
                    val dotRadius = DOT_DIAMETER_DP.dp.toPx() / 2f

                    // 扩散环：从圆点边缘往外推，越远越淡。一圈走完立刻重来，
                    // 视觉上是持续的脉冲。
                    if (haloStrength > 0f) {
                        val radius = dotRadius * (1f + breath * HALO_MAX_GROWTH)
                        drawCircle(
                            color = color.copy(alpha = (1f - breath) * HALO_ALPHA * haloStrength),
                            radius = radius,
                            center = center,
                        )
                    }

                    if (fill > 0f) {
                        drawCircle(
                            color = color.copy(alpha = fill),
                            radius = dotRadius,
                            center = center,
                        )
                    }
                    // 空心环：fill 越小环越明显，两者叠加时正好是「实心点渐渐空掉」。
                    if (fill < 1f) {
                        val stroke = RING_STROKE_DP.dp.toPx()
                        drawCircle(
                            color = color.copy(alpha = 1f - fill),
                            radius = dotRadius - stroke / 2f,
                            center = center,
                            style = Stroke(width = stroke),
                        )
                    }
                },
        )
    }
}

@Composable
private fun LiveAppRow(
    overview: HookerOverview,
    iconLoader: ImageLoader,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = overview.displayName,
        summary = "已开启 ${overview.activeFeatures} 项",
        startAction = {
            AppIcon(
                packageName = overview.packageName,
                fallbackLabel = overview.displayName,
                imageLoader = iconLoader,
                installed = overview.installed,
                modifier = Modifier.padding(end = 14.dp),
            )
        },
        onClick = onClick,
    )
}

/** 需要用户动手处理的提示，右侧带一个动作。 */
@Composable
private fun AlertRow(
    title: String,
    summary: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        ArrowPreference(
            title = title,
            summary = summary,
            endActions = {
                Text(
                    text = action,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(end = 8.dp),
                )
            },
            onClick = onClick,
        )
    }
}

// 时长集中放这儿，改手感只动这一处。
/** 进入动画：要压过退出，读起来才是"新的顶上来"。 */
private const val SLIDE_MILLIS = 260
private const val FADE_IN_MILLIS = 200
/** 退出要快：它同时是进入动画的延迟，慢了整段就拖沓。 */
private const val FADE_OUT_MILLIS = 120
private const val SIZE_MILLIS = 300
private const val COLOR_MILLIS = 320
/** 一次呼吸的周期。太快像报错，太慢看不出在动。 */
private const val BREATH_MILLIS = 1800

private const val DOT_DIAMETER_DP = 12
private const val RING_STROKE_DP = 2
/** 扩散环最大扩到圆点半径的 1 + 这个值倍。 */
private const val HALO_MAX_GROWTH = 1.6f
private const val HALO_ALPHA = 0.45f
