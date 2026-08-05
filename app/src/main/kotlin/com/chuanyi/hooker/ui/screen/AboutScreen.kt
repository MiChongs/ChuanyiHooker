package com.chuanyi.hooker.ui.screen

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
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.nativehook.NativeHook
import com.chuanyi.hooker.ui.component.AppIcon
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.rememberAppIconLoader
import com.chuanyi.hooker.ui.model.rememberOpenSourceLibraries
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页同时兼作「高级」：框架版本、原生层、日志开关这些排查用的东西都在这儿，
 * 首页不放。
 *
 * 开源许可单独走二级页（[Route.Licenses]）：那是上百条的长列表，塞进页签里会把
 * 上面三节挤没。
 */
@Composable
fun AboutScreen(
    settings: ModuleSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current

    val probedFramework = remember { ModuleStatus.frameworkName() }
    val nativeReady = remember { NativeHook.isAvailable }

    val revision = settings.revision
    val verboseLog = remember(revision) { settings.verboseLog }

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
                ArrowPreference(
                    title = "原生层",
                    summary = if (nativeReady) "已加载" else NativeHook.lastError ?: "未加载",
                    onClick = { navigator.push(Route.NativeLayer) },
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

        item { SmallTitle("排查") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = verboseLog,
                    onCheckedChange = { settings.verboseLog = it },
                    title = "详细日志",
                    summary = "把每次判断都写进日志，排查完建议关掉",
                )
            }
        }

        item { FooterNote("日志用 logcat 看，标签 ChuanyiHooker。") }
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
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
