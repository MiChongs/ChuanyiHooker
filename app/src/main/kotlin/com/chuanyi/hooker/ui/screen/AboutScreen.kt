package com.chuanyi.hooker.ui.screen

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.BuildConfig
import com.chuanyi.hooker.core.ModuleStatus
import com.chuanyi.hooker.data.FrameworkService
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.nativehook.NativeHook
import com.chuanyi.hooker.ui.component.AppIcon
import com.chuanyi.hooker.ui.component.CommunityBotRow
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.copyToClipboard
import com.chuanyi.hooker.ui.component.rememberAppIconLoader
import com.chuanyi.hooker.ui.model.rememberOpenSourceLibraries
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页：模块自己的版本、跑在什么框架上、原生层活没活、社区入口、赞赏、开源许可。
 *
 * 只读信息 + 外部链接 + 两个二级页入口，没有开关 —— 开关都在设置页
 * （[Route.Settings]，右上角齿轮）。这一页原来兼作「高级」页放着详细日志开关，
 * 有了设置页之后那条搬过去了。
 *
 * 赞赏（[Route.Donate]）和开源许可（[Route.Licenses]）都走二级页：前者一条地址就是
 * 一张 200 dp 高的二维码卡片，后者是上百条的长列表，任何一个塞进本页都会把上面那几行
 * 运行状态挤出首屏，而那几行才是这一页天天要看的东西。
 */
@Composable
fun AboutScreen(
    settings: ModuleSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    val probedFramework = remember { ModuleStatus.frameworkName() }
    val nativeReady = remember { NativeHook.isAvailable }

    val framework = probedFramework.ifEmpty { settings.frameworkLabel }
    val service = settings.framework

    // 首次进来时是 null（还在后台解析），显示成「正在读取」而不是「0 个」。
    val libraries by rememberOpenSourceLibraries()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { ModuleHeader() }

        item { SmallTitle("运行环境") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = "框架",
                    summary = framework.ifEmpty { "未检测到" },
                )
                // 服务 API 级别决定了哪些能力可用：102 起才有运行中进程查询和热重载。
                // 详情页的那两段能不能出现，判据就是这里。
                BasicComponent(
                    title = "服务 API",
                    summary = when {
                        !service.isBound -> "未连接"
                        service.supportsRunningTargets -> "${service.apiVersion}（支持进程查询与热重载）"
                        else -> "${service.apiVersion}（仅作用域与远程配置）"
                    },
                )
                BasicComponent(
                    title = "框架能力",
                    summary = if (!service.isBound) "未连接" else buildList {
                        if (service.hasRemoteStorage) add("远程配置与文件")
                        if (service.canHookSystem) add("系统进程注入")
                        if (service.enforcesApiProtection) add("API 反射保护")
                    }.ifEmpty { listOf("未声明") }.joinToString("、"),
                )
                BasicComponent(
                    title = "作用域",
                    summary = if (!service.isBound) {
                        "未连接"
                    } else {
                        "框架当前把本模块应用到 ${service.scope.size} 个应用"
                    },
                )
                // 系统版本和机型：反馈里问得最多的两项，而用户往手机设置里翻一趟才能答。
                BasicComponent(title = "系统", summary = systemLabel())
                ArrowPreference(
                    title = "原生层",
                    summary = if (nativeReady) "已加载" else NativeHook.lastError ?: "未加载",
                    onClick = { navigator.push(Route.NativeLayer) },
                )
                // 上面这一整张卡的内容打包成几行文本。反馈渠道在 Telegram，那边只能贴
                // 文字，逐项截图或手打是最容易漏和打错的一步。
                BasicComponent(
                    title = "复制环境信息",
                    summary = "反馈问题时连这段一起发",
                    endActions = {
                        Text(
                            text = "复制",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    },
                    onClick = {
                        context.copyToClipboard(
                            label = "环境信息",
                            text = environmentReport(framework, service, nativeReady),
                            confirmation = "已复制环境信息",
                        )
                    },
                )
            }
        }

        // 跟启动时那个邀请弹窗是同一份内容（见 CommunityBotRow）：那边点过一次，
        // 回到这里就知道该找哪一块。
        item { SmallTitle("社区") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                CommunityBotRow()
            }
        }

        item { SmallTitle("赞赏") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "收款地址与二维码",
                    // 币种写在副标题里：进去之前就知道有没有自己在用的链。
                    summary = "USDT（TRC20）、TON",
                    onClick = { navigator.push(Route.Donate) },
                )
            }
        }

        item { SmallTitle("开源") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "开源许可",
                    summary = libraries
                        ?.let { "${it.size} 个第三方依赖" }
                        ?: "正在读取…",
                    onClick = { navigator.push(Route.Licenses) },
                )
            }
        }

        item { FooterNote("版本号由提交时间和提交号组成，同一个提交在任何机器上编出来都一样。") }
    }
}

/**
 * 顶部名片：图标 + 名字 + 版本。
 *
 * 图标直接问 PackageManager 要自己的，而不是 painterResource(R.mipmap.ic_launcher)
 * —— 那是一个 `<adaptive-icon>` XML，painterResource 只认 bitmap 和 vector，
 * 会在运行期抛 IllegalArgumentException。走 [AppIcon] 则是让系统把前景/背景/遮罩
 * 合成好再交过来，顺带跟「应用」页里的图标是同一条管线、同一个圆角。
 */
@Composable
private fun ModuleHeader() {
    val context = LocalContext.current
    val iconLoader = rememberAppIconLoader()

    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                packageName = context.packageName,
                fallbackLabel = "C",
                imageLoader = iconLoader,
                size = 56.dp,
                cornerRadius = 17.dp,
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = "Chuanyi Hooker",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Medium,
                )
                // 版本号形如 20260805.153045-a1b2c3d4，日期时间和提交号都在里面了；
                // versionCode 就是同一时刻的秒数，再显示一遍是同一个信息的第二种写法。
                // 规则见 app/build.gradle.kts 的「版本号」那节。
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * 系统与机型，形如 `Android 16（API 37） · Xiaomi 24031PN0DC`。
 *
 * `MANUFACTURER` 与 `MODEL` 在不少设备上是重复的（MODEL 本身就带厂商名），重复时只留
 * MODEL，否则拼在一起。
 */
private fun systemLabel(): String {
    val model = Build.MODEL.trim()
    val brand = Build.MANUFACTURER.trim()
    val device = if (brand.isEmpty() || model.startsWith(brand, ignoreCase = true)) {
        model
    } else {
        "$brand $model"
    }
    return "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}） · $device"
}

/**
 * 「复制环境信息」那一段的正文。
 *
 * 顺序按「先是什么，再跑在哪」：模块版本 → 框架与服务 API → 原生层 → 系统与机型。
 * 这四项就是反馈里最常被追问的内容，一次给全可以省掉一轮来回。
 */
private fun environmentReport(
    framework: String,
    service: FrameworkService,
    nativeReady: Boolean,
): String = buildString {
    appendLine("Chuanyi Hooker ${BuildConfig.VERSION_NAME}")
    appendLine("框架：${framework.ifEmpty { "未检测到" }}")
    appendLine(
        "服务 API：" + if (service.isBound) service.apiVersion.toString() else "未连接",
    )
    appendLine("原生层：" + if (nativeReady) "已加载" else NativeHook.lastError ?: "未加载")
    append("系统：${systemLabel()}")
}
