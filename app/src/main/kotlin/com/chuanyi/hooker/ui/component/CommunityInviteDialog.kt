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
import com.chuanyi.hooker.data.CommunityInvite
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 启动时的「加入我们」弹窗。什么时候弹由 [CommunityInvite] 定，这里只管长相。
 *
 * 用 miuix 的 [WindowDialog] 而不是同包的 `OverlayDialog`：后者把自己挂进 miuix
 * `Scaffold` 的弹层里（靠 `LocalDialogStates`），而这个弹窗要盖在首页之上，首页的
 * body 正是毛玻璃的采样层（见 [BlurScaffold]）—— 挂进去会被那层一起录进去参与模糊。
 * [WindowDialog] 自己开一个窗口，跟 Scaffold 的层级无关，也就没有这个问题。
 *
 * 内容刻意跟关于页的「社区」一节长得一模一样（同一个 [CommunityBotRow]）：在弹窗里
 * 见过一次，之后想再找就知道该在关于页里看哪一块。
 */
@Composable
fun CommunityInviteDialog(invite: CommunityInvite) {
    WindowDialog(
        show = invite.isShowing,
        title = "加入我们",
        // 一行说完，不要写成并列短语堆起来的那种句子。长度也是按弹窗宽度调过的：
        // 多一句就会多出一行只有两三个字的尾巴。
        summary = if (invite.isFirstLaunch) {
            "入群和提适配都走这个机器人。"
        } else {
            "用了有一阵了，想加哪个应用直接发给它。"
        },
        // 点外面和返回键都走这里。跟「以后再说」同一个行为：这一档在弹出时就记过账，
        // 不会因为没点按钮就重来。
        onDismissRequest = invite::dismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Card { CommunityBotRow() }

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "不再提示",
                    onClick = invite::optOut,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "知道了",
                    onClick = invite::dismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
