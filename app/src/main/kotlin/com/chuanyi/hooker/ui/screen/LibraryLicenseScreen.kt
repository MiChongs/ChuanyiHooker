package com.chuanyi.hooker.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.component.InfoCard
import com.chuanyi.hooker.ui.model.rememberOpenSourceLibraries
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.mikepenz.aboutlibraries.entity.Library
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 三级页面：一个依赖的元数据与许可全文。
 *
 * 许可正文动辄一万多字（Apache-2.0），用弹层装会挤成一条缝、还要跟外层列表抢
 * 嵌套滚动，所以老老实实做成一页 —— 顺带白拿返回手势和转场。
 *
 * 清单已经在进程里缓存，从列表页跳进来不会再解析一次。
 */
@Composable
fun LibraryLicenseScreen(uniqueId: String) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val uriHandler = LocalUriHandler.current

    val libraries by rememberOpenSourceLibraries()
    val library = remember(libraries, uniqueId) {
        libraries?.firstOrNull { it.uniqueId == uniqueId }
    }

    // 依赖增删后旧返回栈可能指向已经不存在的库，标题退回坐标本身，不要空白。
    val homepage = library?.homepage()

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = library?.name ?: uniqueId,
                subtitle = library?.artifactVersion.orEmpty(),
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
            if (libraries == null) {
                item { FooterNote("正在读取依赖清单…") }
                return@LazyColumn
            }

            if (library == null) {
                item {
                    InfoCard(
                        title = "找不到这个依赖",
                        body = "$uniqueId 已经不在依赖清单里了，多半是模块更新后换掉了它。",
                    )
                }
                return@LazyColumn
            }

            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicComponent(title = "坐标", summary = library.artifactId)
                    library.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { BasicComponent(title = "简介", summary = it) }
                    library.contributor()
                        ?.let { BasicComponent(title = "作者", summary = it) }
                }
            }

            if (homepage != null) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = "项目主页",
                            summary = homepage,
                            // 没装浏览器时 startActivity 会抛，这一页不该因此崩掉。
                            onClick = { runCatching { uriHandler.openUri(homepage) } },
                        )
                    }
                }
            }

            if (library.licenses.isEmpty()) {
                item { FooterNote("这个依赖的 pom 里没有声明许可。") }
                return@LazyColumn
            }

            // 一个 artifact 可以同时挂多个许可，逐个铺开，各自带正文。
            library.licenses.forEach { license ->
                item(key = "title-${license.hash}") {
                    SmallTitle(license.spdxId?.takeIf { it.isNotBlank() } ?: license.name)
                }
                item(key = "body-${license.hash}") {
                    val content = license.licenseContent?.takeIf { it.isNotBlank() }
                    if (content != null) {
                        LicenseText(content)
                    } else {
                        // 构建时联网失败或者是非 SPDX 的自定义许可，只剩一个链接。
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            val url = license.url?.takeIf { it.isNotBlank() }
                            if (url != null) {
                                ArrowPreference(
                                    title = license.name,
                                    summary = url,
                                    onClick = { runCatching { uriHandler.openUri(url) } },
                                )
                            } else {
                                BasicComponent(title = license.name, summary = "未附许可正文")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 许可正文。可选中，方便直接复制出去存档。 */
@Composable
private fun LicenseText(content: String) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = content,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 主页链接：pom 的 `url` 优先，没有就退回 scm 的仓库地址。 */
private fun Library.homepage(): String? =
    website?.takeIf { it.isNotBlank() } ?: scm?.url?.takeIf { it.isNotBlank() }

/** 署名：pom 里的 developers 优先，只有 organization 时用组织名。 */
private fun Library.contributor(): String? {
    val names = developers.mapNotNull { it.name?.takeIf(String::isNotBlank) }
    if (names.isNotEmpty()) return names.joinToString("、")
    return organization?.name?.takeIf { it.isNotBlank() }
}
