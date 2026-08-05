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

    companion object {
        /** Used when the framework refuses to hand out preferences. */
        val AllDefaults: HookerSettings = object : HookerSettings {
            override fun isModuleEnabled() = true
            override fun isVerbose() = false
            override fun isHookerEnabled(hookerId: String) = true
            override fun isFeatureEnabled(hookerId: String, featureId: String, default: Boolean) = default
            override fun getString(hookerId: String, key: String, default: String?) = default
            override fun getInt(hookerId: String, key: String, default: Int) = default
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

    /** False when the framework gave us nothing — the UI defaults are in effect. */
    val isBacked: Boolean get() = prefs != null
}
