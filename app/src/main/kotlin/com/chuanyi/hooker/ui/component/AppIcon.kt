package com.chuanyi.hooker.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.pxOrElse
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Coil 的加载目标：一个包名。 */
@Immutable
data class AppIconModel(val packageName: String)

/**
 * 从 PackageManager 取应用图标。
 *
 * `getApplicationIcon` 要解析目标 APK 的资源，是实打实的磁盘 IO，放进 Coil 的管线
 * 才不会卡住列表滚动，顺带拿到内存缓存。
 */
private class AppIconFetcher(
    private val model: AppIconModel,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val drawable = runCatching {
            options.context.packageManager.getApplicationIcon(model.packageName)
        }.getOrNull() ?: return null

        val px = maxOf(
            options.size.width.pxOrElse { 0 },
            options.size.height.pxOrElse { 0 },
        ).takeIf { it in 1..MAX_ICON_PX } ?: DEFAULT_ICON_PX

        return ImageFetchResult(
            image = drawable.rasterize(px).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * 画成位图再交出去。
     *
     * **这一步是应用列表能不能滑顺的关键。**直接把 Drawable 包成 Image 的话，
     * 它每一帧都要重画一遍 —— 而应用图标几乎都是 `AdaptiveIconDrawable`：前景背景
     * 两层，外加一次遮罩裁剪，一个就是几毫秒，一屏十来个必然掉帧。栅格化一次之后
     * 滚动只是贴图，而且位图能被 Coil 的内存缓存真正复用。
     */
    private fun Drawable.rasterize(size: Int): Bitmap {
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, size, size)
        draw(Canvas(bitmap))
        return bitmap
    }

    class Factory : Fetcher.Factory<AppIconModel> {
        override fun create(data: AppIconModel, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(data, options)
    }
}

/** 请求没给尺寸时按这个画。列表里的图标是 38–44dp，@3.5x 也就一百多像素。 */
private const val DEFAULT_ICON_PX = 160

/** 上限：某些请求会给到整屏宽，照着画就是一张大位图白占内存。 */
private const val MAX_ICON_PX = 384

/** 缓存键。同一个包名复用同一份位图。 */
private class AppIconKeyer : Keyer<AppIconModel> {
    override fun key(data: AppIconModel, options: Options): String = "app-icon:${data.packageName}"
}

/**
 * 专用于应用图标的 ImageLoader。
 *
 * 不注册 Coil 的全局单例（Xposed 模块不适合在 Application 里挂东西），但**进程内
 * 只建一个**：`ImageLoader` 各自带一份内存缓存，每个界面 `remember` 一个的话，
 * 应用列表和详情页各刷各的，同一个图标要解码好几次。
 *
 * 磁盘缓存关掉：图标本来就来自本机的 PackageManager，再落一份盘只是白白在滚动
 * 路径上多一次 IO 查找。
 */
@Composable
fun rememberAppIconLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { sharedIconLoader(context) }
}

@Volatile
private var sharedLoader: ImageLoader? = null

private fun sharedIconLoader(context: Context): ImageLoader =
    sharedLoader ?: synchronized(AppIconModel::class) {
        sharedLoader ?: ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory())
            }
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
            .also { sharedLoader = it }
    }

/**
 * 应用图标，平滑圆角。
 *
 * [installed] 为 false 时不去问 PackageManager，直接画首字母占位。
 */
@Composable
fun AppIcon(
    packageName: String,
    fallbackLabel: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    cornerRadius: Dp = 13.dp,
    installed: Boolean = true,
) {
    if (!installed) {
        AppIconPlaceholder(
            label = fallbackLabel,
            modifier = modifier,
            size = size,
            cornerRadius = cornerRadius,
        )
        return
    }

    AsyncImage(
        model = AppIconModel(packageName),
        contentDescription = null,
        imageLoader = imageLoader,
        modifier = modifier
            .size(size)
            .squircleClip(cornerRadius),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun AppIconPlaceholder(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    cornerRadius: Dp = 13.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .squircleBackground(
                color = MiuixTheme.colorScheme.secondaryContainer,
                cornerRadius = cornerRadius,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}
