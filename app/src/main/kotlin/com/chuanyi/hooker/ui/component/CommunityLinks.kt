package com.chuanyi.hooker.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 社区入口。关于页的「社区」一节和启动时的邀请弹窗共用这一份，链接只写在这里。
 */
@Immutable
data class CommunityLink(val title: String, val summary: String, val url: String)

val CommunityLinks = listOf(
    CommunityLink(
        title = "Telegram 频道",
        summary = "更新发这里",
        url = "https://t.me/chuanyi_hooker",
    ),
    CommunityLink(
        title = "讨论群",
        summary = "有问题直接问",
        // `+` 开头的是邀请链接而不是公开用户名，不能改写成 t.me/xxx 的形式。
        url = "https://t.me/+7VEAJBoyOzgxZDU1",
    ),
    CommunityLink(
        title = "提需求",
        summary = "想加哪个应用，写这里",
        url = "https://app.notion.com/p/3b376f30c0a4802f9238cb1533c5bf6a" +
            "?v=3b376f30c0a48027b62b000c053c6d31&source=copy_link",
    ),
)

/**
 * 三行入口。放进 [top.yukonga.miuix.kmp.basic.Card] 里用 —— 关于页和邀请弹窗都是这么摆的，
 * 两处长得一样是有意的：弹窗里点过一次，之后在关于页就知道该找哪一块。
 */
@Composable
fun CommunityLinkRows() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    CommunityLinks.forEach { link ->
        ArrowPreference(
            title = link.title,
            summary = link.summary,
            onClick = { openExternalLink(context, uriHandler, link.url) },
        )
    }
}

/**
 * 打开外部链接。
 *
 * `openUri` 在找不到能处理这个 Intent 的应用时会抛（AndroidUriHandler 把
 * ActivityNotFoundException 包成 IllegalArgumentException），这里不止是接住它 ——
 * 点了毫无反应是最难受的失败形态，所以退到「复制到剪贴板」并提示一声：链接贴到别处
 * 仍然有用，尤其那两条 Telegram，很多人本来就想发到别的设备上打开。
 */
fun openExternalLink(context: Context, uriHandler: UriHandler, url: String) {
    if (runCatching { uriHandler.openUri(url) }.isSuccess) return

    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("链接", url))
    Toast.makeText(context, "没有能打开链接的应用，已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
