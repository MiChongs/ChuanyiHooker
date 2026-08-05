package com.chuanyi.hooker.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedInterface

/**
 * Everything a hooker is handed about the process it landed in.
 *
 * EzHookTool's global state (`EzReflect` / `EzXposed`) is already pointed at
 * [classLoader] by the time a hooker sees this, so `findClassIf { }`,
 * `Class.findMethod { }` and `createHook { }` work without further setup.
 */
class HookScope internal constructor(
    /** The live libxposed interface for this module generation. */
    val xposed: XposedInterface,
    val packageName: String,
    val processName: String,
    val appInfo: ApplicationInfo,
    val classLoader: ClassLoader,
    val isFirstPackage: Boolean,
    val stage: AppHooker.Stage,
    val settings: HookerSettings,
    val log: HookerLog,
    private val hookerId: String,
) {
    /**
     * Target's versionCode, or -1 when it cannot be determined.
     *
     * [ApplicationInfo] carries the value in a hidden `longVersionCode` field —
     * there is no public getter — and at [AppHooker.Stage.PACKAGE_READY] there
     * is no Context yet to ask the PackageManager. Try the field first, fall
     * back to the PackageManager once an Application exists.
     */
    val versionCode: Long by lazy {
        runCatching {
            ApplicationInfo::class.java.getDeclaredField("longVersionCode")
                .apply { isAccessible = true }
                .getLong(appInfo)
        }.getOrNull()
            ?: runCatching {
                appContextOrNull()?.packageManager
                    ?.getPackageInfo(packageName, 0)
                    ?.longVersionCode
            }.getOrNull()
            ?: -1L
    }

    /** Path of the target's base APK, useful for static inspection at runtime. */
    val apkPath: String? get() = appInfo.sourceDir

    val isMainProcess: Boolean get() = processName == packageName

    fun isEnabled(feature: HookFeature): Boolean =
        settings.isFeatureEnabled(hookerId, feature.id, feature.defaultEnabled)

    fun isEnabled(featureId: String, default: Boolean = true): Boolean =
        settings.isFeatureEnabled(hookerId, featureId, default)

    fun string(key: String, default: String? = null): String? =
        settings.getString(hookerId, key, default)

    fun int(key: String, default: Int): Int = settings.getInt(hookerId, key, default)

    /** Loads a class from the target, or null. Never throws. */
    fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    /** First of [names] that resolves, or null. */
    fun classOrNull(vararg names: String): Class<*>? = names.firstNotNullOfOrNull(::classOrNull)

    /**
     * The target's [Application] once it exists. Null when called from
     * [AppHooker.Stage.PACKAGE_LOADED] or very early in `PACKAGE_READY`.
     */
    fun appContextOrNull(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    override fun toString(): String =
        "HookScope($packageName@$processName, vc=$versionCode, stage=$stage)"
}
