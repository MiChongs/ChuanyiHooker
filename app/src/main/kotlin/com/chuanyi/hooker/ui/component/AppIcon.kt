package com.chuanyi.hooker.ui.component

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
import coil3.request.Options
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

        return ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<AppIconModel> {
        override fun create(data: AppIconModel, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(data, options)
    }
}

/** 缓存键。同一个包名复用同一份位图。 */
private class AppIconKeyer : Keyer<AppIconModel> {
    override fun key(data: AppIconModel, options: Options): String = "app-icon:${data.packageName}"
}

/**
 * 专用于应用图标的 ImageLoader。
 *
 * 不注册全局单例：显式传给 [AsyncImage] 即可，Xposed 模块不适合在 Application
 * 里挂东西。
 */
@Composable
fun rememberAppIconLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory())
            }
            .build()
    }
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
