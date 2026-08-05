package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.data.DonationPrompt
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import com.chuanyi.hooker.ui.navigation.Route
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 启动时的赞赏弹窗。什么时候弹由 [DonationPrompt] 定，这里只管长相。
 *
 * 用 miuix 的 [WindowDialog] 而不是同包的 `OverlayDialog`，理由与 [CommunityInviteDialog]
 * 相同：后者挂进 miuix `Scaffold` 的弹层，会被首页 body 那层毛玻璃采样层一起录进去参与模糊。
 *
 * 内容与关于页的「赞赏」一节指向同一处（[Route.Donate]），并且在弹窗里就能直接复制地址 ——
 * 想给的人不用为了拿一串地址多跳一页，只想看看的人点「知道了」即可。
 */
@Composable
fun DonationPromptDialog(prompt: DonationPrompt) {
    val navigator = LocalNavigator.current

    WindowDialog(
        show = prompt.isShowing,
        title = "赞赏",
        // 一行说完。首次强调「免费」这件事本身，之后就不必再重复一遍前提。
        summary = if (prompt.isFirstTime) {
            "本模块免费提供，全部功能默认可用。赞赏完全自愿。"
        } else {
            "如果这个模块对你有用，可以考虑赞赏。"
        },
        // 点外面和返回键都走这里。跟「知道了」同一个行为：这一档在弹出时就记过账。
        onDismissRequest = prompt::dismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Card {
                DonationCopyRows(
                    // 先关弹窗再导航：弹窗自己开了一个窗口，留着它压在新页面上面会挡住
                    // 返回手势。
                    onOpenDetail = {
                        prompt.dismiss()
                        navigator.push(Route.Donate)
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "不再提示",
                    onClick = prompt::optOut,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "知道了",
                    onClick = prompt::dismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
