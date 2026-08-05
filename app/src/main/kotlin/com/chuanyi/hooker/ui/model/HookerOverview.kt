package com.chuanyi.hooker.ui.model

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookerRegistry
import com.chuanyi.hooker.data.ModuleSettings

/**
 * 一个目标应用在界面上要展示的全部信息。
 *
 * 首页和应用页都要这份数据，算法只在这里写一遍。
 */
@Immutable
data class HookerOverview(
    val hooker: AppHooker,
    val packageName: String,
    val versionName: String?,
    val installed: Boolean,
    val enabled: Boolean,
    val activeFeatures: Int,
    val totalFeatures: Int,
) {
    val id: String get() = hooker.id
    val displayName: String get() = hooker.displayName

    /**
     * 真正会在目标进程里装上东西：应用装了、这个应用没被关掉、至少开了一项功能。
     * 总开关不在这里判断，由调用方传入。
     */
    fun isLive(masterEnabled: Boolean): Boolean =
        masterEnabled && installed && enabled && activeFeatures > 0
}

/**
 * 汇总所有 hooker 的展示信息。
 *
 * 分成两层 `remember`，因为两部分数据的变化频率差了几个数量级：
 *
 *  * **包信息**（装没装、版本号）—— 每个 hooker 一次 `getPackageInfo` binder 调用，
 *    只有权限状态变化时才需要重查。合成一层是很容易犯的错：`revision` 一进 key，
 *    用户每拨一次开关就会在主线程上把所有目标应用重查一遍，开关动画肉眼可见地卡。
 *  * **开关派生量**（启用与否、开了几项）—— 纯内存读取，跟着 `revision` 重算，
 *    每次写设置都要刷新。
 *
 * [permissionGranted] 必须参与包信息那层的 key：拿不到应用列表权限时
 * `getPackageInfo` 抛的异常和真没装一模一样，授权后必须整份重查。
 */
@Composable
fun rememberHookerOverviews(
    settings: ModuleSettings,
    permissionGranted: Boolean,
): List<HookerOverview> {
    val context = LocalContext.current
    // 产出激活凭据的那个 hooker 不进列表：它不改目标任何行为，没有可开关的功能，
    // 而它的开关一旦被关掉整个模块就会停摆 —— 摆出来只会让人以为那是个普通目标。
    // 它的状态在首页的激活卡片上单独说。
    val hookers = remember { HookerRegistry.all().filterNot { it.bypassesActivation } }

    // 贵的那一半：只跟权限走。
    val targets = remember(hookers, context, permissionGranted) {
        hookers.map { resolveTarget(context.packageManager, it) }
    }

    // 便宜的那一半：跟着设置变。
    val revision = settings.revision
    return remember(revision, targets) {
        hookers.mapIndexed { index, hooker ->
            val target = targets[index]
            HookerOverview(
                hooker = hooker,
                packageName = target.packageName,
                versionName = target.versionName,
                installed = target.installed,
                enabled = settings.isHookerEnabled(hooker.id),
                activeFeatures = hooker.features.count {
                    settings.isFeatureEnabled(hooker.id, it.id, it.defaultEnabled)
                },
                totalFeatures = hooker.features.size,
            )
        }.sortedWith(
            compareByDescending<HookerOverview> { it.installed }
                .thenByDescending { it.enabled }
                .thenBy { it.displayName },
        )
    }
}

private class TargetInfo(
    val packageName: String,
    val versionName: String?,
    val installed: Boolean,
)

/**
 * 挑一个用来展示的目标包：优先已装上的那个，都没装就用第一个。
 *
 * 包不存在会抛 NameNotFoundException；API 30+ 上被包可见性过滤掉时同样如此。
 * 可见性由各 hooker 模块自己的 manifest 声明 `<queries>` 提供，合并进 :app。
 *
 * 判定装没装看的是 getPackageInfo 成没成功，不是 versionName 非空 ——
 * versionName 允许为 null，拿它当判据会把装着的应用显示成未安装。
 */
private fun resolveTarget(pm: PackageManager, hooker: AppHooker): TargetInfo {
    hooker.targetPackages.forEach { pkg ->
        val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        if (info != null) return TargetInfo(pkg, info.versionName, installed = true)
    }
    val fallback = hooker.targetPackages.firstOrNull() ?: hooker.id
    return TargetInfo(fallback, null, installed = false)
}
