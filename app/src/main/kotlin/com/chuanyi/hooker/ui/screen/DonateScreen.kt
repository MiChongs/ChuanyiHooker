package com.chuanyi.hooker.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chuanyi.hooker.ui.component.BlurScaffold
import com.chuanyi.hooker.ui.component.DonationAddress
import com.chuanyi.hooker.ui.component.DonationAddresses
import com.chuanyi.hooker.ui.component.FooterNote
import com.chuanyi.hooker.ui.component.HookerTopAppBar
import com.chuanyi.hooker.ui.component.InfoCard
import com.chuanyi.hooker.ui.component.QrCode
import com.chuanyi.hooker.ui.component.copyToClipboard
import com.chuanyi.hooker.ui.navigation.LocalNavigator
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 二维码边长。TON 那条 48 字符的地址编出来是 33 模块 + 8 静区，这个尺寸下一格约 5 dp。 */
private val QrSize = 200.dp

/**
 * 二级页面：赞赏。
 *
 * 每条地址一张卡片：二维码在上（供另一台设备扫描），地址原文居中（可选中核对），
 * 复制与钱包按钮在下（同设备上最短的路径）。三种取地址的方式在同一屏内。
 *
 * 单独一页而不是并入关于页的一节：一张卡片就是 200 dp 高的二维码加两行文字，两条地址
 * 铺开会把「运行环境」那几行状态挤出首屏。
 */
@Composable
fun DonateScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()

    BlurScaffold(
        topBar = {
            HookerTopAppBar(
                title = "赞赏",
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
                InfoCard(
                    title = "自愿赞赏",
                    body = "本模块免费提供，全部功能默认可用。赞赏与功能无关。",
                )
            }

            DonationAddresses.forEach { donation ->
                item(key = "title-${donation.asset}") {
                    SmallTitle("${donation.asset} · ${donation.network}")
                }
                item(key = "card-${donation.asset}") {
                    DonationCard(donation)
                }
            }

            item { FooterNote("感谢支持。") }
        }
    }
}

/** 一条地址：二维码、原文、按钮。 */
@Composable
private fun DonationCard(donation: DonationAddress) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrCode(content = donation.address, modifier = Modifier.width(QrSize))

            // 可选中：用于核对末几位，或用系统文本选择自行复制。等宽字体是为了核对本身，
            // 比例字体下 base58 里的 l/I、O/0 相邻时难以区分。
            SelectionContainer {
                Text(
                    text = donation.address,
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                TextButton(
                    text = "复制地址",
                    onClick = {
                        context.copyToClipboard(
                            label = "${donation.asset} 地址",
                            text = donation.address,
                            confirmation = "已复制 ${donation.asset} 地址",
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )

                val walletUri = donation.walletUri
                if (walletUri != null) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "打开钱包",
                        onClick = { openWallet(context, uriHandler, donation, walletUri) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 跳转钱包。
 *
 * 失败时**不**退回复制那条 uri（[openExternalLink][com.chuanyi.hooker.ui.component.openExternalLink]
 * 的默认行为）：`ton://transfer/…` 复制出去没有用处，粘进钱包的地址框也是错的。这里退回
 * 复制**地址本身**，那正是用户接下来要做的事。
 */
private fun openWallet(
    context: Context,
    uriHandler: UriHandler,
    donation: DonationAddress,
    walletUri: String,
) {
    if (runCatching { uriHandler.openUri(walletUri) }.isSuccess) return

    context.copyToClipboard(label = "${donation.asset} 地址", text = donation.address)
    Toast.makeText(
        context,
        "未找到可处理 ${donation.asset} 链接的钱包应用，已复制地址",
        Toast.LENGTH_SHORT,
    ).show()
}
