package com.chuanyi.hooker.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.model.rememberOpenSourceLibraries
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import com.mikepenz.aboutlibraries.entity.Library
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页面：本应用用到的第三方依赖一览。
 *
 * 列表来自构建期生成的清单（见
 * [OpenSourceLibraries][com.chuanyi.hooker.ui.model.OpenSourceLibraries]），
 * 点任意一条进 [LibraryLicenseScreen] 看元数据和许可全文。
 */
@Composable
fun LicensesScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()

    val libraries by rememberOpenSourceLibraries()

    // 搜索词进 rememberSaveable：进详情页再退回来，输入框里的东西还在。
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    val matched = remember(libraries, query) { libraries.orEmpty().matching(query) }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = "开源许可",
                onBack = { navigator.pop() },
                scrollBehavior = scrollBehavior,
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
            item {
                LibrarySearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    onCancel = {
                        query = ""
                        searchExpanded = false
                    },
                )
            }

            when {
                // null 是「还在解析」，不是「一个都没有」，两者提示不能共用。
                libraries == null -> item { FooterNote("正在读取依赖清单…") }

                matched.isEmpty() -> item {
                    FooterNote(
                        if (query.isBlank()) "清单是空的，多半是构建时没生成 aboutlibraries.json。"
                        else "没有匹配「$query」的依赖。",
                    )
                }

                else -> {
                    item { SmallTitle("${matched.size} 个依赖") }
                    item {
                        // 整表放一张卡里（跟本应用其余列表一致）。上百行简单文本行
                        // 一次性组合是划算的：分组懒加载省下的那点时间，抵不过滚动
                        // 时反复创建卡片边框造成的接缝。
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            matched.forEach { library ->
                                LibraryRow(
                                    library = library,
                                    onClick = {
                                        navigator.push(Route.LibraryLicense(library.uniqueId))
                                    },
                                )
                            }
                        }
                    }
                    item {
                        FooterNote("清单在构建时由依赖图生成，和实际打进包里的一致。")
                    }
                }
            }
        }
    }
}

/**
 * 一行依赖：名字 + 许可，右侧带版本号。
 *
 * 副标题给许可而不是给坐标：这一页是拿来看许可的，坐标在详情页里。
 */
@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
    ArrowPreference(
        title = library.name,
        summary = library.licenseLabel(),
        endActions = {
            val version = library.artifactVersion
            if (!version.isNullOrBlank()) {
                Text(
                    text = version,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 搜索框。
 *
 * 展开态只是为了把「取消」露出来并让输入框吃到焦点；结果永远显示在下面那张卡里，
 * 不用 [SearchBar] 自带的展开内容槽 —— 那会变成两套并列的列表。
 */
@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    SearchBar(
        inputField = {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                // 软键盘上的搜索键：列表本来就在实时过滤，收起键盘即可。
                onSearch = { onExpandedChange(false) },
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                label = "搜索依赖或许可",
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
                    .clickable(onClick = onCancel)
                    .padding(end = 16.dp),
            )
        },
        modifier = Modifier.padding(horizontal = 12.dp),
        content = {},
    )
}

/**
 * 许可摘要：优先 SPDX id（"Apache-2.0"），没有就用 pom 里的原文名。
 *
 * 一个 artifact 可以同时声明多个许可（比如 GPL + Classpath 例外），所以是列表。
 */
internal fun Library.licenseLabel(): String =
    licenses
        .map { license -> license.spdxId?.takeIf { it.isNotBlank() } ?: license.name }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
        ?: "未声明许可"

/**
 * 过滤。同时匹配显示名、坐标和许可 —— 「apache」「miuix」「androidx.compose」
 * 这三种输入都是用户会敲的。
 */
private fun List<Library>.matching(query: String): List<Library> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { library ->
        library.name.contains(needle, ignoreCase = true) ||
            library.uniqueId.contains(needle, ignoreCase = true) ||
            library.licenseLabel().contains(needle, ignoreCase = true)
    }
}
