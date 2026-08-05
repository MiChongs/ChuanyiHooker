package com.chuanyi.hooker.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.nativehook.NativeHook
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 二级页面：原生层状态与已注册的 C++ hooker。排查用。
 */
@Composable
fun NativeLayerScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()

    val available = remember { NativeHook.isAvailable }
    val hookers = remember { NativeHook.available() }

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = "原生层",
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
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicComponent(
                        title = if (available) "已加载" else "不可用",
                        summary = if (available) null else NativeHook.lastError,
                    )
                }
            }

            if (hookers.isEmpty()) return@LazyColumn

            item { SmallTitle("C++ hooker  ${hookers.size}") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    hookers.forEach { info ->
                        BasicComponent(title = info.id, summary = info.description)
                    }
                }
            }

            // 这里读的是模块自己的进程；C++ hooker 是各功能在目标进程里装的，
            // 所以不在这一页显示安装与否 —— 显示了也永远是「未安装」。
            item { FooterNote("列表来自模块自身进程，实际安装发生在目标应用内。") }
        }
    }
}
