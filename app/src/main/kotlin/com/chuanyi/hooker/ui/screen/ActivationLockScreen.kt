package com.chuanyi.hooker.ui.screen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chuanyi.hooker.data.ActivationAudit
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.data.rememberActivationStatus
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 未通过群组校验时**整个应用**看到的那一屏。
 *
 * 不是一张提示卡，是把界面整个换掉：没有底栏、没有导航、进不去任何设置页。这样处理
 * 而不是把功能逐个灰掉，是因为在未激活状态下那些开关全都是假的 —— 被注入的进程里一个
 * hook 都不会装（见 `HookerRuntime.install`），让人对着一屏能拨但不生效的开关，比
 * 直接说清楚要糟糕得多。
 *
 * ## 只说设备侧还差什么，不提供加入群组的途径
 *
 * 这一屏**不放**频道、群组、邀请链接中的任何一个 —— 它回答的是「为什么现在用不了」
 * 和「这台设备还差哪一步」，不承担招徕的职责。已经在群里的人从这里拿到的是一条可
 * 执行的指令；不在群里的人不会从这里拿到入口。
 *
 * ## 为什么用三步清单而不是一段说明文字
 *
 * 卡住的原因有三种，而且是**有先后的**：没有客户端 → 客户端没进作用域 → 没进群。
 * 一段话说不清「我现在到底卡在第几步」，而清单一眼就能看出前面哪些已经过了。
 * 第三步永远显示成未完成 —— 能看到这一屏，本身就说明它没过。
 *
 * ## 配色全部走 miuix 的语义 token
 *
 * 徽标用 `errorContainer`/`onErrorContainer` 而不是「error 加个透明度」：那一对
 * 是主题为「容器化的错误强调」定义好的，深浅色下的对比度由主题保证，自己调 alpha
 * 在深色主题上会糊成一块。同理，卡片里的文字用 `onSurfaceContainer`（标题）和
 * `onSurfaceContainerVariant`（次要），而不是页面级的 `onBackground` —— 卡片的底色
 * 是 `surfaceContainer`，配对关系是成套的。
 *
 * ## 回前台时重查
 *
 * 用户是**离开这个界面**去解决问题的：去 LSPosed 勾作用域、去 TG 启动一次。回来时
 * [ActivationAudit] 手里那份作用域还是旧的，所以每次回到前台都要求框架重拉一遍。
 * 令牌那边不用管 —— 广播落盘会改 `revision`，重组自然发生。
 */
@Composable
fun ActivationLockScreen(settings: ModuleSettings) {
    val context = LocalContext.current
    val status = rememberActivationStatus(settings)
    val lifecycleOwner = LocalLifecycleOwner.current

    // 申请作用域走的是框架的系统弹窗，用户是在别的界面上点的同意；回来时不重拉，
    // 这一屏会一直显示「还没进作用域」，而实际上已经进了。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            settings.framework.refresh()
        }
    }

    var requestError by remember { mutableStateOf<String?>(null) }
    val steps = remember(status) { stepsOf(status) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                // 大屏上不让内容横着摊平：这一屏是一句话加一张清单，铺满 10 寸平板
                // 会读不成行。
                .widthIn(max = CONTENT_MAX_WIDTH)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            LockBadge()

            Spacer(Modifier.height(28.dp))
            Text(
                text = "模块未激活",
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = headline(status.verdict),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(32.dp))

            SmallTitle(text = "解锁条件", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth()) {
                steps.forEachIndexed { index, step ->
                    StepRow(number = index + 1, step = step)
                }
            }

            // 差作用域是这一屏唯一「点一下就能推进」的一步，所以给它一个真正的按钮。
            // 其余两步的动作在别处（装客户端、进群），这里只能说清楚。
            if (status.missingScope.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ScopeRequestCard(
                    settings = settings,
                    packages = status.missingScope,
                    error = requestError,
                    onResult = { requestError = it },
                )
            }

            if (status.clients.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SmallTitle(
                    text = if (status.missingScope.isEmpty()) "启动其中一个即可重新校验" else "已检测到的客户端",
                    modifier = Modifier.fillMaxWidth(),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    status.clients.forEach { client ->
                        ArrowPreference(
                            title = client.label,
                            summary = if (client.inScope) {
                                "已在作用域内，启动一次即可校验"
                            } else {
                                "还没被加进模块作用域"
                            },
                            onClick = { launch(context, client.packageName) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = "校验只读取本机 TG 客户端的会话记录，判断指定群组在不在里面。" +
                    "全程不联网、不上传、不读取任何聊天内容。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 三步清单
// ---------------------------------------------------------------------------

@Immutable
private data class Step(val title: String, val summary: String, val done: Boolean)

/**
 * 三步是**有先后**的，所以第二、三步的说明取决于前面过没过 —— 在一个客户端都没装的
 * 时候说「去 LSPosed 勾一下」只会让人白跑一趟。
 */
private fun stepsOf(status: ActivationAudit.Status): List<Step> {
    val hasClient = status.clients.isNotEmpty()
    val inScope = status.clients.any { it.inScope }
    return listOf(
        Step(
            title = "安装受支持的 TG 客户端",
            summary = if (hasClient) {
                "已检测到 ${status.clients.size} 个"
            } else {
                "官方 Telegram 或它的分支都可以"
            },
            done = hasClient,
        ),
        Step(
            title = "把模块加进它的作用域",
            summary = when {
                !hasClient -> "装好客户端后再做这一步"
                inScope -> "已应用到 ${status.clients.count { it.inScope }} 个客户端"
                else -> "校验要在客户端自己的进程里做，缺了这步跑不起来"
            },
            done = inScope,
        ),
        // 永远是未完成：能看到这一屏，就说明它没过。
        Step(
            title = "该客户端已加入指定群组",
            summary = if (hasClient && inScope) {
                "启动一次客户端即可自动校验"
            } else {
                "前两步完成后自动进行"
            },
            done = false,
        ),
    )
}

@Composable
private fun StepRow(number: Int, step: Step) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepIndicator(number = number, done = step.done)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = step.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                // 卡片底色是 surfaceContainer，配对的前景就是 onSurfaceContainer；
                // 用页面级的 onBackground 在深色主题下对比度会不对。
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = step.summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 完成用「实心 + 勾」，未完成用「浅底 + 序号」。
 *
 * 两者的语义色是成对取的（`primary`/`onPrimary`、`secondaryContainer`/`onSecondaryContainer`），
 * 不自己调透明度 —— 那样在深色主题下会糊成一块看不出层次。
 */
@Composable
private fun StepIndicator(number: Int, done: Boolean) {
    val background = if (done) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
    val foreground = if (done) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier
            .size(28.dp)
            .squircleBackground(color = background, cornerRadius = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = number.toString(),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = foreground,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 头部与动作
// ---------------------------------------------------------------------------

/**
 * 顶上那个锁徽标。
 *
 * `errorContainer`/`onErrorContainer` 是主题为「容器化的错误强调」备好的一对，
 * 深浅色下的对比度由主题负责。写成 `error.copy(alpha = 0.12f)` 在浅色下看着没问题，
 * 深色主题上会直接糊掉。
 */
@Composable
private fun LockBadge() {
    Box(
        modifier = Modifier
            .size(96.dp)
            .squircleBackground(
                color = MiuixTheme.colorScheme.errorContainer,
                cornerRadius = 30.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Lock,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(44.dp),
        )
    }
}

/** 一句话说清现在卡在哪。措辞不往「去哪加群」引导，理由见类文档。 */
private fun headline(verdict: ActivationAudit.Verdict): String = when (verdict) {
    // 稽核跑完就会离开这一屏，这个分支只在极短的中间态出现。
    ActivationAudit.Verdict.ACTIVE -> "正在确认…"

    ActivationAudit.Verdict.NO_CLIENT ->
        "校验需要读取 TG 客户端本地的会话记录，而这台设备上没有找到受支持的客户端。"

    ActivationAudit.Verdict.SCOPE_MISSING ->
        "校验要在 TG 客户端自己的进程里做，而本模块还没有被应用到任何一个客户端上。"

    ActivationAudit.Verdict.WAITING ->
        "这台设备当前不在指定群组内，或者刚刚退出过。启动一次 TG 客户端即可重新校验。"
}

@Composable
private fun ScopeRequestCard(
    settings: ModuleSettings,
    packages: List<String>,
    error: String?,
    onResult: (String?) -> Unit,
) {
    val pending = settings.framework.pendingScope.isNotEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "申请作用域",
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = error ?: "框架会弹一个确认框，同意后启动一次客户端即可。也可以自己去 LSPosed 里勾。",
                style = MiuixTheme.textStyles.body2,
                // 失败原因用 error 前景色：它是需要被读到的东西，跟旁边的说明文字
                // 不是一个分量。
                color = if (error != null) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = if (pending) "申请中…" else "去申请",
                    enabled = !pending,
                    onClick = {
                        onResult(null)
                        settings.framework.requestScope(packages) { outcome ->
                            onResult(outcome.message)
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 拉起一个 TG 客户端。
 *
 * 这一下才是解锁的关键动作：模块被注入进那个进程，探测才会跑。所以不能只写一句
 * 「请启动客户端」——能点的就直接点。
 */
private fun launch(context: Context, packageName: String) {
    val intent = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
        .getOrNull() ?: return
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 平板上内容的最大宽度。再宽就读不成行了。 */
private val CONTENT_MAX_WIDTH = 480.dp
