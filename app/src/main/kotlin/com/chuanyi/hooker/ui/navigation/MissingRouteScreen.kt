package com.chuanyi.hooker.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 恢复出来的路由指向了已经不存在的东西时显示。
 *
 * 返回栈会跨进程死亡保存，而 hooker 列表是运行期 ServiceLoader 决定的。模块更新
 * 后少了一个 hooker，旧栈里那条路由就悬空了。这里给一个能退回去的页面，而不是
 * 让 entryProvider 抛异常。
 */
@Composable
fun MissingRouteScreen(title: String, message: String) {
    val navigator = LocalNavigator.current
    BlurScaffold(
        topBar = {
            HookerTopAppBar(title = title, onBack = { navigator.pop() })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "找不到内容", style = MiuixTheme.textStyles.body1)
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
