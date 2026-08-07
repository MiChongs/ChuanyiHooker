package com.chuanyi.hooker.ui.component

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 唯一的对外入口：Telegram 机器人 [CommunityBotHandle]。
 *
 * 入群申请和适配提交都从它走，界面上不再列频道、群链接和需求表 —— 那三个入口做的是
 * 同一件事的三种起点，用户得先猜自己该去哪个，而三边的记录也对不上。收成一个之后
 * 「找谁」这个问题就没有了。
 *
 * 机器人用户名是公开 handle，`https://t.me/<handle>` 这种写法在没装 Telegram 的设备上
 * 会落到网页版，比 `tg://` 深链稳 —— 打不开的兜底见 [openExternalLink]。
 */
const val CommunityBotHandle = "@chuanyi_hooker_bot"

private const val CommunityBotUrl = "https://t.me/chuanyi_hooker_bot"

/**
 * 机器人入口那一行。放进 [top.yukonga.miuix.kmp.basic.Card] 里用。
 *
 * 关于页和启动时的邀请弹窗共用这一份，两处长得一样是有意的：弹窗里点过一次，之后在
 * 关于页就知道该找哪一块。
 */
@Composable
fun CommunityBotRow() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    ArrowPreference(
        title = CommunityBotHandle,
        summary = "入群申请、适配提交",
        onClick = { openExternalLink(context, uriHandler, CommunityBotUrl) },
    )
}

/**
 * 打开外部链接。
 *
 * `openUri` 在找不到能处理这个 Intent 的应用时会抛（AndroidUriHandler 把
 * ActivityNotFoundException 包成 IllegalArgumentException），这里不止是接住它 ——
 * 点了毫无反应是最难受的失败形态，所以退到「复制到剪贴板」并提示一声：链接贴到别处
 * 仍然有用，很多人本来就想发到别的设备上打开。
 */
fun openExternalLink(context: Context, uriHandler: UriHandler, url: String) {
    if (runCatching { uriHandler.openUri(url) }.isSuccess) return

    // 这句不走 copyToClipboard 的 confirmation：要讲的是「打不开」这件事本身，
    // 在 Android 13+ 上也得说，不能被系统那个「已复制」浮层顶掉。
    context.copyToClipboard(label = "链接", text = url)
    Toast.makeText(context, "没有能打开链接的应用，已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
