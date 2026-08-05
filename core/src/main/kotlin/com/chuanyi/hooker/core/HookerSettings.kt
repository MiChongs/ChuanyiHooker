package com.chuanyi.hooker.core

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Key layout shared by the module app (writer) and the hooked process (reader).
 */
object SettingsKeys {
    /** Group name passed to `XposedInterface.getRemotePreferences`. */
    const val PREFS = "hooker_settings"

    const val MASTER_ENABLED = "module.enabled"
    const val VERBOSE_LOG = "module.verbose_log"

    /**
     * 激活令牌（hex）。由 TG 客户端进程里的探测签发、模块应用落盘、每个被注入的
     * 进程读取校验，见 [ActivationGuard]。
     *
     * 存在这里而不是模块自己的本地存储：被注入的进程只够得到框架这份配置。
     * 值本身带 MAC，改它只会让校验失败，所以放在用户改得到的地方是安全的。
     */
    const val ACTIVATION_TOKEN = "activation.token"

    /** 令牌最后一次更新的时刻（毫秒）。纯粹给界面显示用，不参与判定。 */
    const val ACTIVATION_UPDATED_AT = "activation.updated_at"

    /** 最后一次签发令牌的 TG 客户端包名。同样只用于界面显示。 */
    const val ACTIVATION_SOURCE = "activation.source"

    fun hookerEnabled(hookerId: String) = "hooker.$hookerId.enabled"
    fun feature(hookerId: String, featureId: String) = "feature.$hookerId.$featureId"
    fun value(hookerId: String, key: String) = "value.$hookerId.$key"
}

/**
 * Read-only view of the module's settings, as seen from a hooked process.
 */
interface HookerSettings {
    fun isModuleEnabled(): Boolean
    fun isVerbose(): Boolean
    fun isHookerEnabled(hookerId: String): Boolean
    fun isFeatureEnabled(hookerId: String, featureId: String, default: Boolean): Boolean
    fun getString(hookerId: String, key: String, default: String?): String?
    fun getInt(hookerId: String, key: String, default: Int): Int

    /** 激活令牌，没有就是 null。校验在 [ActivationGuard]，这里只负责取出来。 */
    fun activationToken(): String?

    companion object {
        /** Used when the framework refuses to hand out preferences. */
        val AllDefaults: HookerSettings = object : HookerSettings {
            override fun isModuleEnabled() = true
            override fun isVerbose() = false
            override fun isHookerEnabled(hookerId: String) = true
            override fun isFeatureEnabled(hookerId: String, featureId: String, default: Boolean) = default
            override fun getString(hookerId: String, key: String, default: String?) = default
            override fun getInt(hookerId: String, key: String, default: Int) = default

            // 其余默认值都往「放行」的方向倒（拿不到配置时功能照常），只有这一个
            // 相反：拿不到配置就是拿不到令牌，也就没有任何东西证明这台机器该被放行。
            override fun activationToken(): String? = null
        }
    }
}

/**
 * Backed by `XposedInterface.getRemotePreferences`, which the framework snapshots
 * from the module app's world-readable preferences. Read-only here by design.
 *
 * Every accessor degrades to the supplied default instead of throwing:
 * an embedded framework has no remote preferences at all, and a first run
 * before the module app has ever been opened has no file yet.
 */
class RemoteHookerSettings(xposed: XposedInterface) : HookerSettings {

    private val prefs: SharedPreferences? = runCatching {
        xposed.getRemotePreferences(SettingsKeys.PREFS)
    }.getOrNull()

    private fun bool(key: String, default: Boolean): Boolean =
        runCatching { prefs?.getBoolean(key, default) ?: default }.getOrDefault(default)

    override fun isModuleEnabled(): Boolean = bool(SettingsKeys.MASTER_ENABLED, true)

    override fun isVerbose(): Boolean = bool(SettingsKeys.VERBOSE_LOG, false)

    override fun isHookerEnabled(hookerId: String): Boolean =
        bool(SettingsKeys.hookerEnabled(hookerId), true)

    override fun isFeatureEnabled(hookerId: String, featureId: String, default: Boolean): Boolean =
        bool(SettingsKeys.feature(hookerId, featureId), default)

    override fun getString(hookerId: String, key: String, default: String?): String? =
        runCatching { prefs?.getString(SettingsKeys.value(hookerId, key), default) ?: default }
            .getOrDefault(default)

    override fun getInt(hookerId: String, key: String, default: Int): Int =
        runCatching { prefs?.getInt(SettingsKeys.value(hookerId, key), default) ?: default }
            .getOrDefault(default)

    override fun activationToken(): String? =
        runCatching { prefs?.getString(SettingsKeys.ACTIVATION_TOKEN, null) }.getOrNull()

    /** False when the framework gave us nothing — the UI defaults are in effect. */
    val isBacked: Boolean get() = prefs != null
}
