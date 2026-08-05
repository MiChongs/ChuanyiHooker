package com.chuanyi.hooker.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个收款地址。地址只写在 [DonationAddresses] 里，别处一律引用：复制成第二份就多一处
 * 抄错的可能，而抄错的后果是收款失败且不可追回。
 *
 * @param asset 币种，同时用作列表 key 与剪贴板标签
 * @param network 链和网络。要显示：同一个币在不同链上是不同的地址空间
 * @param address 收款地址原文
 * @param walletUri 钱包 deep link，没有事实标准的链留 null（见 [DonationAddresses]）
 */
@Immutable
data class DonationAddress(
    val asset: String,
    val network: String,
    val address: String,
    val walletUri: String? = null,
) {
    /**
     * 头 8 尾 8 的缩写，形如 `TDy7rpRn…x4uzASCW`。
     *
     * 给 [DonationCopyRows] 那种一行高的列表用：地址是 34 / 48 字符，整条塞进 summary 会
     * 折成两行，且第二行只有几个字符。留头尾是因为核对时看的就是这两段 —— 中间那截靠肉眼
     * 比对本来也不现实，真要核对应该在赞赏页看全长。
     */
    val abbreviated: String
        get() = if (address.length <= 20) address else {
            address.take(8) + "…" + address.takeLast(8)
        }
}

/**
 * 收款地址表。
 *
 * 两条地址都验过校验位，不是照着聊天记录粘的：
 *
 * - TRON 是 base58check，前缀字节 `0x41`、双 SHA-256 的后 4 字节对得上；
 * - TON 是 48 字符的 base64url，tag `0x51`（`UQ` 开头 = non-bounceable）、
 *   工作链 0、末两字节的 CRC-16/XMODEM 对得上。
 *
 * 改这里的任何一个字符都要重新验一遍。
 */
val DonationAddresses = listOf(
    DonationAddress(
        asset = "USDT",
        network = "TRC20 · 波场",
        address = "TDy7rpRnpAdSNpAQtx6i8WF8GVx4uzASCW",
        // TRON 侧没有事实标准的 scheme：`tron:` 无人实现，`tronlinkoutside://` 是
        // TronLink 私有的。不提供大概打不开的按钮，扫码与复制已经够用。
    ),
    DonationAddress(
        asset = "TON",
        // 不写「TON 主网」：标题里已经有 asset，连起来会读成「TON · TON 主网」。
        network = "主网",
        address = "UQCIDB8GYabDJOiVmvkt8PPBBgvSDc7NwaEETHN5VlRtlS20",
        // ton:// 是 TON Connect 之前就有的老 scheme，Tonkeeper / MyTonWallet /
        // Telegram 钱包都还认它，比 tonkeeper:// 这种单家私有的通用。
        walletUri = "ton://transfer/UQCIDB8GYabDJOiVmvkt8PPBBgvSDc7NwaEETHN5VlRtlS20",
    ),
)

/**
 * 每条地址一行，点按即复制；末尾一行进赞赏页看二维码。
 *
 * 放进 [top.yukonga.miuix.kmp.basic.Card] 里用。给 [DonationPromptDialog] 准备的：弹窗里
 * 直接把地址取走是最短的路径，不必先跳一页；真要扫码再走 [onOpenDetail]。
 *
 * @param onOpenDetail 「查看二维码」的动作。调用方负责先关掉弹窗再导航。
 */
@Composable
fun DonationCopyRows(onOpenDetail: () -> Unit) {
    val context = LocalContext.current

    DonationAddresses.forEach { donation ->
        BasicComponent(
            title = "${donation.asset} · ${donation.network}",
            summary = donation.abbreviated,
            endActions = {
                Text(
                    text = "复制",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            },
            onClick = {
                context.copyToClipboard(
                    label = "${donation.asset} 地址",
                    text = donation.address,
                    confirmation = "已复制 ${donation.asset} 地址",
                )
            },
        )
    }

    ArrowPreference(
        title = "查看二维码",
        summary = "另一台设备扫码转账",
        onClick = onOpenDetail,
    )
}
