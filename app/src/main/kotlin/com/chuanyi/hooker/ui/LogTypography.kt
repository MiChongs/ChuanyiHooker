package com.chuanyi.hooker.ui

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.chuanyi.hooker.R

/**
 * 日志页那套等宽字体。
 *
 * ## 为什么日志要等宽
 *
 * 每一行的开头是「时间 等级 tag」三段定长前缀。比例字体下这三段的宽度随内容浮动，
 * 上下两行的正文起点对不齐，扫一屏日志时眼睛得逐行重新找位置。等宽把前缀钉成一列，
 * 才能像看 logcat 那样一眼扫下去。数字尤其明显：`11:03:41` 和 `14:08:22` 在比例
 * 字体下是两个不同的宽度。
 *
 * ## 走 Downloadable Fonts，字体文件不进 APK
 *
 * [GoogleFont.Provider] 指向 Google Play 服务里的字体提供方：字体由它在运行期下发，
 * 缓存跨应用共享。所以这个模块的体积一个字节都没变 —— 一份 JetBrains Mono 的可变
 * 字体大约 200 KB，为一页日志把包撑大 200 KB 是不划算的。
 *
 * 认证靠 `res/values/font_certs.xml` 里那两份证书（AOSP 调试证书 + Google 发行证书），
 * 那是 Google 官方样例里的原文件，不能改也不必更新。少了它，`FontProvider` 会因为
 * 签名对不上而拒绝提供方，表现是「字体一直是兜底那份」，不报错。
 *
 * ## 兜底是列进同一个 FontFamily 里的，不是运行期判断
 *
 * [DeviceFontFamilyName] 那几行是系统自带的等宽字体，和 Google 字体列在同一个
 * [FontFamily] 里。Compose 解析字体列表时把「立刻可用的」和「要异步加载的」分开：
 * 前者先上屏，后者加载完再原地换掉。于是三种情况自然都对了：
 *
 *  * 没装 Google Play 服务（海外 ROM、精简包）→ 永远是系统等宽，不会退化成比例字体；
 *  * 装了但还没下完（首次进入、离线）→ 先系统等宽，下完自动换；
 *  * 正常情况 → JetBrains Mono。
 *
 * 顺序不能反：匹配是按列表顺序取第一个同字重的，Google 那几行排在前面才会赢。
 *
 * 三个字重都要各列一遍。少列一个的话，那个字重会被字体合成（把常规体拉粗）顶替，
 * 等宽字体被合成之后字形宽度不再一致 —— 整列就又对不齐了。
 */
private val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/**
 * JetBrains Mono：为读代码设计的等宽字体，字高大、0 带斜杠、`l` `1` `I` 分得开 ——
 * 日志里全是包名、类名和十六进制，这三点每一条都用得上。
 */
private val JetBrainsMono = GoogleFont("JetBrains Mono")

/** 日志正文、时间戳、等级标记都用它。见本文件顶部关于兜底顺序的说明。 */
val HookerMonoFamily = FontFamily(
    Font(googleFont = JetBrainsMono, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMono, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = JetBrainsMono, fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("monospace"), weight = FontWeight.Bold),
)
