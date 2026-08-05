package com.chuanyi.hooker.ui.model

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.chuanyi.hooker.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 第三方依赖清单。
 *
 * 数据不是手写的：AboutLibraries 的 Gradle 插件在构建期遍历本 variant 的运行时
 * 依赖图，把每个 artifact 的 pom 元数据和 SPDX 许可全文写进
 * `res/raw/aboutlibraries.json`（配置见 app/build.gradle.kts 的 `aboutLibraries`
 * 块）。所以这份名单跟真正打进包里的东西天然一致，改了依赖不需要来这里补一笔。
 *
 * 解析结果按进程缓存：json 有几十 KB，而「关于」页要个数、许可列表页要全表、
 * 详情页要正文，三处各解析一遍没有意义。
 */
object OpenSourceLibraries {

    @Volatile
    private var cached: List<Library>? = null

    /** 并发进入时只解析一次：列表页和详情页在转场期间是同时活着的。 */
    private val loading = Mutex()

    /** 已经解析好的清单；还没解析过时为 null。 */
    val snapshot: List<Library>? get() = cached

    /**
     * 读出清单，必要时解析。列表已经按名字（忽略大小写）排好序，是
     * `Libs.Builder().build()` 做的，这里不再排。
     */
    suspend fun load(context: Context): List<Library> {
        cached?.let { return it }
        return loading.withLock {
            cached ?: withContext(Dispatchers.IO) {
                val json = context.resources
                    .openRawResource(R.raw.aboutlibraries)
                    .use { it.readBytes().decodeToString() }
                Libs.Builder().withJson(json).build().libraries
            }.also { cached = it }
        }
    }
}

/**
 * 依赖清单的 Compose 入口。
 *
 * 已经解析过就在首帧直接给出结果（返回栈里前进后退不会闪一下"正在读取"）；
 * 没有就先给 null，解析完再重组。null 表示"还没读到"，不是"没有依赖"。
 */
@Composable
fun rememberOpenSourceLibraries(): State<List<Library>?> {
    // 缓存活得比任何一个页面都久，别把 Activity 的 context 递进去。
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = OpenSourceLibraries.snapshot, context) {
        if (value == null) value = OpenSourceLibraries.load(context)
    }
}
