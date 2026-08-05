package com.chuanyi.hooker.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import top.yukonga.miuix.kmp.squircle.squircleClip
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 二维码。zxing 负责编码，画图是这里自己的 [Canvas]。
 *
 * ## 为什么不生成 Bitmap
 *
 * 常见写法是 `QRCodeWriter().encode(…, 512, 512)` 出一张位图再交给 Image。那样要为一段
 * 几十字节的文本申请一兆多的像素、按目标 dp 再缩一次（缩放会把模块边缘糊掉，正是扫码器
 * 最不想看到的东西），而且换个屏幕密度就得换一个尺寸重新编。
 *
 * 这里让 zxing 出 **1 像素 1 模块** 的网格（宽高传 0，见下），把 41×41 这种量级的矩阵
 * 留在内存里，缩放交给 Canvas —— 矩形是矢量的，多大都是实边。
 *
 * ## 为什么固定黑白，不跟主题走
 *
 * 二维码规范要求深色模块在浅色底上。zxing 自己的解码器能处理反色，但钱包 App 用的扫码
 * 实现五花八门，深色模式下把码反过来就是在赌对方的容错。所以浅色底写死成白 —— 深色模式
 * 下这块白确实显眼，但收款地址扫不出来是更糟的结果。
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    moduleColor: Color = Color.Black,
    quietZoneColor: Color = Color.White,
) {
    // 编码一次就够：内容不变，矩阵不变。几十字节的输入耗时在微秒级，不值得挪去后台。
    val matrix = remember(content) { encodeQr(content) } ?: return

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .squircleClip(cornerRadius),
    ) {
        // 矩阵自带 4 个模块的静区（见 encodeQr 的 MARGIN），所以整块底色铺满即是静区，
        // 不用另外留 padding —— 留了反而会让静区变成「padding + 静区」两层，白白变小码。
        drawRect(color = quietZoneColor)

        val modules = matrix.width
        val cell = size.width / modules

        for (row in 0 until modules) {
            // 边界一律取整到像素：cell 是小数，逐个模块按浮点坐标画的话相邻矩形之间会留
            // 半像素的缝，抗锯齿把缝染成灰线，密度低的屏幕上肉眼可见，也会拉低识别率。
            // floor/ceil 让相邻模块多重叠不到一个像素，宁可粘住也不要断开。
            val top = floor(row * cell)
            val bottom = ceil((row + 1) * cell)

            var col = 0
            while (col < modules) {
                if (!matrix.get(col, row)) {
                    col++
                    continue
                }
                // 一行里连续的深色模块并成一个矩形。定位图形那种成片的区域能省掉大半
                // draw 调用，顺带把横向的接缝一起消掉。
                var last = col
                while (last + 1 < modules && matrix.get(last + 1, row)) last++

                val left = floor(col * cell)
                val right = ceil((last + 1) * cell)
                drawRect(
                    color = moduleColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                )
                col = last + 2
            }
        }
    }
}

/**
 * 编成模块网格。
 *
 * 宽高传 0 是有意的：`QRCodeWriter` 会把输出尺寸取成 `max(请求值, 模块数 + 静区)`，
 * 于是放大倍数算出来正好是 1，返回的 [BitMatrix] 就是模块网格本身（含静区）。传一个
 * 具体像素数反而会先按整数倍放大一次，白白多占内存还得处理除不尽的余量。
 *
 * 编码失败返回 null：能失败的只有「内容超出 40 版容量」（约 2900 字节）这一种，收款
 * 地址离得远，但让它降级成「不画码」也比抛到组合里崩掉整页强 —— 地址文本和复制按钮
 * 还在，功能不缺。
 */
private fun encodeQr(content: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            // 默认是 L（7%）。地址错一位就是打给别人，用 M（15%）换一点冗余，
            // 这个长度下也就多一两个版本的密度。
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 规范要求的 4 模块静区。设成 0 让 Canvas 去补是行的，但那样静区宽度就
            // 变成了「某个 dp 值」而不是「4 个模块」，码一密就不够。
            EncodeHintType.MARGIN to 4,
        ),
    )
}.getOrNull()
