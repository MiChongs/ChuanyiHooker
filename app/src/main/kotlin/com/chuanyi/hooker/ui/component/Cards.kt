package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 列表末尾的一行浅色说明。
 *
 * 用来放「需要长期在场、但不值得占一张卡片」的那种话。同样的内容包成 [InfoCard]
 * 会读起来像一条待处理的提示，而它其实只是脚注。
 */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** 标题 + 说明的提示卡片。留给真正需要用户注意的情况。 */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            Text(
                text = body,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
