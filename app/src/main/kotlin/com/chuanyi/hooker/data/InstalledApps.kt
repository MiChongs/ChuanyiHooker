package com.chuanyi.hooker.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** 一个已安装的应用，只留列表要用的那几样。 */
@Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** 有启动图标 —— 用户认得出来的那些应用。 */
    val launchable: Boolean,
)

/**
 * 本机装了哪些应用。
 *
 * ## 为什么要缓存
 *
 * `loadLabel` 每个包都要去解析那个 APK 的资源，两三百个包加起来是几百毫秒的磁盘
 * IO。这份列表在一次会话里不会变（装/卸应用会重启模块界面之前的进程也无所谓，
 * 下拉刷新给了显式的重来入口），所以整个进程只算一次。
 *
 * 缓存放在 object 里而不是 Compose 的 remember：应用选择器是弹层，关掉再打开
 * 会重新组合，但列表不该重新算一遍。
 *
 * ## 权限
 *
 * 需要 `QUERY_ALL_PACKAGES`。没有它 `getInstalledApplications` 只返回
 * `<queries>` 里声明过的那几个包 —— 界面上表现为「应用列表几乎是空的」，
 * 而不是报错。MIUI 系还额外要 `GET_INSTALLED_APPS`，见 [AppListPermission]。
 */
object InstalledApps {

    @Volatile
    private var cache: List<InstalledApp>? = null

    suspend fun load(context: Context, refresh: Boolean = false): List<InstalledApp> {
        if (!refresh) cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) { read(context.applicationContext) }
        cache = loaded
        return loaded
    }

    fun invalidate() {
        cache = null
    }

    private fun read(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        val installed = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }.getOrDefault(emptyList())

        // 有启动入口的包一次问清楚。逐个 getLaunchIntentForPackage 是两三百次
        // binder 往返，查询一次 LAUNCHER 再取交集要快一个数量级。
        val launchable = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).mapTo(HashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())

        // 中文按拼音排，不然「微信」会跟在所有拉丁字母后面按码点乱排。
        val collator = Collator.getInstance(Locale.getDefault())

        return installed.asSequence()
            .filter { it.packageName != self }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: info.packageName,
                    isSystem = info.isSystemApp,
                    launchable = info.packageName in launchable,
                )
            }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    /**
     * 系统应用的判定要带上 [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP]：预装后又从
     * 商店更新过的应用（多数机器上的浏览器、输入法）只有这个位，光看 FLAG_SYSTEM
     * 会把它们当成普通应用。
     */
    private val ApplicationInfo.isSystemApp: Boolean
        get() = (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
