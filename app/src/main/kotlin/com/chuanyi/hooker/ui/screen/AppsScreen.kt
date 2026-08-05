package com.chuanyi.hooker.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.ImageLoader
import com.chuanyi.hooker.data.FrameworkService
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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppsScreen(
    settings: ModuleSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val iconLoader = rememberAppIconLoader()
    val framework = settings.framework

    // 授权后要重查一遍包信息：被系统拦掉时 getPackageInfo 抛的是
    // NameNotFoundException，和真的没装长得一模一样。
    val permission = rememberAppListPermissionState()
    val overviews = rememberHookerOverviews(settings, permission.isGranted)

    // 申请作用域走的是框架的系统弹窗，用户是在别的界面上点的同意 —— 回到前台时
    // 手里这份一定是旧的，必须重拉。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { framework.refresh() }

    // 只对**装了的**目标谈作用域：给没装的应用申请作用域没有意义，框架也会拒。
    // 用 overview.packageName 而不是 hooker.targetPackages —— 前者是 resolveTarget
    // 已经挑出来的那个真正装着的包。
    val installedTargets = remember(overviews) {
        overviews.filter { it.installed }.map { it.packageName }.distinct()
    }
    val missingScope = framework.missingScope(installedTargets)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (overviews.isEmpty()) {
            item {
                InfoCard(
                    title = "还没有支持的应用",
                    body = "这个版本没有内置任何应用的适配。",
                )
            }
            return@LazyColumn
        }

        // 只在真的有目标被判成未安装时才提示，别在原生 Android 上白占一张卡片。
        if (!permission.isGranted && overviews.any { !it.installed }) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = "允许读取应用列表",
                        summary = "部分系统会拦掉这项查询，装着的应用也会显示成未安装",
                        onClick = { permission.request(context) },
                    )
                }
            }
        }

        item { SmallTitle("作用域") }
        item {
            ScopeSummary(
                framework = framework,
                installedTargets = installedTargets,
                missing = missingScope,
            )
        }

        val installed = overviews.filter { it.installed }
        val missing = overviews.filterNot { it.installed }

        if (installed.isNotEmpty()) {
            item { SmallTitle("已安装") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    installed.forEach { overview ->
                        AppRowItem(
                            overview = overview,
                            iconLoader = iconLoader,
                            inScope = framework.isInScope(overview.packageName),
                            scopeKnown = framework.isBound,
                            onClick = { navigator.push(Route.HookerDetail(overview.id)) },
                        )
                    }
                }
            }
        }

        if (missing.isNotEmpty()) {
            item { SmallTitle("未安装") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    missing.forEach { overview ->
                        AppRowItem(
                            overview = overview,
                            iconLoader = iconLoader,
                            inScope = false,
                            scopeKnown = false,
                            onClick = { navigator.push(Route.HookerDetail(overview.id)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 作用域总览 + 一键补齐。
 *
 * 作用域是「模块开着但目标没反应」最常见的原因，而它和模块自己的开关是两套东西：
 * 开关在模块这边，作用域在框架那边。这张卡把框架那边的实际状态摊开，并提供
 * 直接申请的入口，不用切去 LSPosed 管理器里勾。
 */
@Composable
private fun ScopeSummary(
    framework: FrameworkService,
    installedTargets: List<String>,
    missing: List<String>,
) {
    if (!framework.isBound) {
        InfoCard(
            title = "Xposed 服务未连接",
            body = "作用域信息来自框架。模块未被启用，或框架不支持模块服务。",
        )
        return
    }

    val granted = installedTargets.size - missing.size
    val pendingCount = framework.pendingScope.size

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = "框架作用域",
            summary = buildString {
                append("已装的目标 $granted/${installedTargets.size} 在作用域内")
                if (framework.scope.size != granted) {
                    // 框架的作用域里可能还有本模块不认识的包（用户手动勾的、
                    // 或者曾经支持过的旧目标），说清楚免得数字对不上引起误会。
                    append("；框架侧共 ${framework.scope.size} 个")
                }
            },
        )

        when {
            pendingCount > 0 -> BasicComponent(
                title = "正在申请…",
                summary = "$pendingCount 个应用等待框架确认",
            )

            missing.isEmpty() -> BasicComponent(
                title = "全部已授权",
                summary = "所有装着的目标应用都在作用域内",
            )

            else -> ArrowPreference(
                title = "申请缺失的作用域",
                summary = "${missing.size} 个：${missing.joinToString("、")}",
                onClick = { framework.requestScope(missing) },
            )
        }

        framework.lastError?.let { error ->
            BasicComponent(title = "上一次操作失败", summary = error)
        }
    }
}

@Composable
private fun AppRowItem(
    overview: HookerOverview,
    iconLoader: ImageLoader,
    inScope: Boolean,
    scopeKnown: Boolean,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = overview.displayName,
        summary = overview.packageName,
        startAction = {
            AppIcon(
                packageName = overview.packageName,
                fallbackLabel = overview.displayName,
                imageLoader = iconLoader,
                installed = overview.installed,
                modifier = Modifier.padding(end = 14.dp),
            )
        },
        endActions = { StatusLabel(overview, inScope, scopeKnown) },
        onClick = onClick,
    )
}

@Composable
private fun RowScope.StatusLabel(
    overview: HookerOverview,
    inScope: Boolean,
    scopeKnown: Boolean,
) {
    // 顺序即优先级：先说不可能生效的原因，再说开了几项。
    // 「开着但不在作用域」是最值得点进去看的一种状态，排在功能计数前面。
    val (label, color) = when {
        !overview.installed -> "未安装" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        scopeKnown && !inScope -> "不在作用域" to MiuixTheme.colorScheme.primary
        overview.enabled ->
            "${overview.activeFeatures}/${overview.totalFeatures}" to MiuixTheme.colorScheme.primary

        else -> "已关闭" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2,
        color = color,
        modifier = Modifier
            .align(Alignment.CenterVertically)
            .padding(end = 8.dp),
    )
}
