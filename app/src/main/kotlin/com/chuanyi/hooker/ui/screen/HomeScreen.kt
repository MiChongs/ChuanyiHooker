package com.chuanyi.hooker.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info

/**
 * 首页宿主：底部导航栏 + 三个平级页签。
 *
 * 页签**不进返回栈**。返回栈表达的是深度，而三个页签是平级关系，把它们压进
 * 栈里会得到返回键在页签之间来回跳的行为。它们只是这一个
 * 目的地内部的状态，由 pager 保存。
 *
 * 有深度关系的页面（hooker 详情、原生层）才走 [Route][com.chuanyi.hooker.ui.navigation.Route]
 * 压栈，那里才有转场动画和滑动返回。
 */
@Composable
fun HomeScreen(settings: ModuleSettings) {
    val pagerState = rememberPagerState(pageCount = { HomeTab.entries.size })
    val scope = rememberCoroutineScope()
    val currentTab by remember { derivedStateOf { HomeTab.entries[pagerState.currentPage] } }

    // 不在首个页签时，返回键先回到首个页签，而不是直接退出应用。
    // NavDisplay 只在栈深 > 1 时拦截返回，首页这一层是空着的，正好接管。
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(title = currentTab.title)
        },
        bottomBar = {
            NavigationBar(
                // 底色与分割线都交给渐进式蒙层，栏本身保持透明。
                color = Color.Transparent,
                showDivider = false,
            ) {
                HomeTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = tab.icon(),
                        label = tab.title,
                    )
                }
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            end = padding.calculateEndPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 12.dp,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier,
            // 每页各自滚动，不做整页嵌套滚动联动。
            beyondViewportPageCount = 1,
        ) { page ->
            when (HomeTab.entries[page]) {
                HomeTab.Status -> StatusScreen(
                    settings = settings,
                    contentPadding = contentPadding,
                    onOpenApps = {
                        scope.launch { pagerState.animateScrollToPage(HomeTab.Apps.ordinal) }
                    },
                )
                HomeTab.Apps -> AppsScreen(settings = settings, contentPadding = contentPadding)
                HomeTab.About -> AboutScreen(
                    settings = settings,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

enum class HomeTab(val title: String) {
    Status("状态"),
    Apps("应用"),
    About("关于"),
    ;

    @Composable
    fun icon() = when (this) {
        Status -> MiuixIcons.Home
        Apps -> MiuixIcons.GridView
        About -> MiuixIcons.Info
    }
}
