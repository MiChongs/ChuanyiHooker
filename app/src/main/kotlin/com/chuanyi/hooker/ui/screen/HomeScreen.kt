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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.data.CommunityInvite
import com.chuanyi.hooker.data.DonationPrompt
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.LocalUiPreferences
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.CommunityInviteDialog
import com.chuanyi.hooker.ui.component.DonationPromptDialog
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val navigator = LocalNavigator.current
    val ui = LocalUiPreferences.current

    // 落在哪一页由设置定。initialPage 只在第一次组合时读，之后改设置不会把当前页拽走。
    val startPage = remember { ui.startTab.coerceIn(HomeTab.entries.indices) }
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { HomeTab.entries.size },
    )
    val scope = rememberCoroutineScope()
    val currentTab by remember { derivedStateOf { HomeTab.entries[pagerState.currentPage] } }

    // 弹不弹在进程第一次取到这两个单例时就已经定了，这里只是把答案接过来。
    //
    // 顺序有意义：两个弹窗各自开窗口，同时弹会叠在一起。先问「加入我们」，它要弹的话
    // 赞赏这一档就顺延到下次启动（不作废，见 DonationPrompt）。全新安装的第 1 次启动
    // 因此只有欢迎弹窗，赞赏落在第 2 次。
    val context = LocalContext.current
    val invite = remember(context) { CommunityInvite.get(context) }
    val donation = remember(context, invite) {
        DonationPrompt.get(context, deferred = invite.isShowing)
    }

    // 不在启动页签时，返回键先回到启动页签，而不是直接退出应用。回的是「打开应用时看到的
    // 那一页」而不是写死的第一页 —— 否则把启动页签设成「应用」的人，返回键会把他送到一个
    // 他没主动去过的地方。NavDisplay 只在栈深 > 1 时拦截返回，首页这一层空着，正好接管。
    BackHandler(enabled = pagerState.currentPage != startPage) {
        scope.launch { pagerState.animateScrollToPage(startPage) }
    }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = currentTab.title,
                actions = {
                    IconButton(onClick = { navigator.push(Route.Settings) }) {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = "设置",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
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

    // 挂在 BlurScaffold **外面**：它们自己开窗口，不占布局，也就不会被 body 那层毛玻璃
    // 采样层录进去。不显示时一个布局节点都不产生。
    CommunityInviteDialog(invite)
    DonationPromptDialog(donation)
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
