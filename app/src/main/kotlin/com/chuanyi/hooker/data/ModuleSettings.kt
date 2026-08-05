package com.chuanyi.hooker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.core.HookPreset
import com.chuanyi.hooker.core.SettingsKeys
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Write side of the settings the hooks read back through
 * `XposedInterface.getRemotePreferences`.
 *
 * The framework hands the module app a binder asynchronously (via the
 * `XposedProvider` the service AAR declares for us), so this starts on a local
 * fallback and swaps to the remote store once it arrives, copying anything the
 * user changed in the meantime. [isSynced] drives the warning in the UI: while
 * it is false, edits are not visible to hooked apps.
 *
 * ## 为什么标 [Stable]
 *
 * 这个类是几乎每个 composable 的参数（导航宿主、四个页面全都收它）。Compose 编译器
 * 按字段推断稳定性，看到 `SharedPreferences` 这个平台接口就判定整个类不稳定 ——
 * 于是所有收它的 composable 都不可跳过，切个页签就要把整棵树重跑一遍。
 *
 * 实际上它满足 [Stable] 的契约：
 *
 *  * `equals` 是身份比较，恒定 —— 本来就是进程级单例；
 *  * 对 UI 有影响的状态全部是 snapshot state：几个 `mutableStateOf` 属性，
 *    以及 [revision]；
 *  * 存在 SharedPreferences 里的开关值本身不是 snapshot state，所以
 *    [getBoolean] 里显式读一次 [revision]，把它们挂到快照系统上（见那里的注释）。
 *    这条是契约成立的关键，不能省。
 */
@Stable
class ModuleSettings private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val local: SharedPreferences =
        appContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var remote: SharedPreferences? = null

    /** Bumped on every write so Compose recomposes off a single key. */
    var revision by mutableStateOf(0)
        private set

    /** True once edits actually reach hooked processes. */
    var isSynced by mutableStateOf(false)
        private set

    var serviceError by mutableStateOf<String?>(null)
        private set

    /**
     * 框架服务的其余能力：作用域增删、运行中进程、热重载、能力位。
     *
     * 之所以挂在这里而不是自成单例：[XposedServiceHelper] 全局只有一个静态 listener
     * 槽位，而且 binder 到达后缓存会被清空 —— 第二个注册者什么都收不到。所以整个
     * 进程只能有一处 `registerListener`，就是下面的 [init]，由它转发给
     * [FrameworkService]。
     */
    val framework = FrameworkService()

    /**
     * True once the framework has handed this app its Xposed service binder.
     *
     * That binder only arrives for a module the framework has actually loaded and
     * enabled, which makes it a direct activation signal — unlike the self-probe
     * in [com.chuanyi.hooker.core.ModuleStatus], which additionally depends on the
     * module's own package being in scope and on the hook landing before the UI
     * reads it.
     */
    val isFrameworkBound: Boolean get() = framework.isBound

    /** e.g. "LSPosed 1.10.3 (7132)". Empty until the service binds. */
    val frameworkLabel: String get() = framework.label

    init {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) = onService(service)
                override fun onServiceDied(service: XposedService) {
                    remote = null
                    isSynced = false
                    framework.onDied()
                    serviceError = "Xposed service disconnected"
                    revision++
                }
            })
        }.onFailure {
            serviceError = it.message ?: it.javaClass.simpleName
        }
    }

    private fun onService(service: XposedService) {
        // Record activation first: a framework that cannot hand out remote
        // preferences (embedded builds) has still loaded and enabled the module,
        // and the status card should say so.
        framework.onBind(service)

        val prefs = runCatching { service.getRemotePreferences(SettingsKeys.PREFS) }
            .getOrElse {
                // Framework without remote capability (embedded builds).
                serviceError = it.message ?: it.javaClass.simpleName
                revision++
                return
            }
        migrateLocalInto(prefs)
        remote = prefs
        isSynced = true
        serviceError = null
        revision++
    }

    /** Carries pre-service edits over exactly once. */
    private fun migrateLocalInto(target: SharedPreferences) {
        val pending = local.all
        if (pending.isEmpty()) return
        val editor = target.edit()
        pending.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        if (editor.commit()) local.edit().clear().apply()
    }

    private fun reader(): SharedPreferences = remote ?: local

    /**
     * SharedPreferences 里的值不是 snapshot state，直接读它的 composable 不会被
     * invalidate。所以把 [revision] 作为实参传进去：求值这个实参就是一次 snapshot
     * 读取，会被当前正在组合的作用域记下来，之后任何 [setBoolean] 触发的
     * `revision++` 都会让读过这个开关的 composable 失效。
     *
     * 这一步是类文档里 [Stable] 契约成立的前提。调用方因此也不必再套
     * `remember(revision) { ... }`（套着不会错，只是多余）。
     */
    fun getBoolean(key: String, default: Boolean): Boolean = readBoolean(revision, key, default)

    /** [at] 只为在调用点触发一次 [revision] 读取，函数体不使用它。 */
    private fun readBoolean(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
        default: Boolean,
    ): Boolean = runCatching { reader().getBoolean(key, default) }.getOrDefault(default)

    fun setBoolean(key: String, value: Boolean) {
        runCatching { reader().edit().putBoolean(key, value).apply() }
        revision++
    }

    fun toggle(key: String, default: Boolean) = setBoolean(key, !getBoolean(key, default))

    /** 同 [getBoolean]：实参那次 [revision] 读取才是重组的依据，函数体不用它。 */
    fun getInt(key: String, default: Int): Int = readInt(revision, key, default)

    private fun readInt(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
        default: Int,
    ): Int = runCatching { reader().getInt(key, default) }.getOrDefault(default)

    fun setInt(key: String, value: Int) {
        runCatching { reader().edit().putInt(key, value).apply() }
        revision++
    }

    fun getString(key: String, default: String): String = readString(revision, key, default)

    private fun readString(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
        default: String,
    ): String = runCatching { reader().getString(key, default) ?: default }.getOrDefault(default)

    fun setString(key: String, value: String) {
        runCatching { reader().edit().putString(key, value).apply() }
        revision++
    }

    // --- typed helpers used by the screens ---------------------------------

    var masterEnabled: Boolean
        get() = getBoolean(SettingsKeys.MASTER_ENABLED, true)
        set(value) = setBoolean(SettingsKeys.MASTER_ENABLED, value)

    var verboseLog: Boolean
        get() = getBoolean(SettingsKeys.VERBOSE_LOG, false)
        set(value) = setBoolean(SettingsKeys.VERBOSE_LOG, value)

    fun isHookerEnabled(hookerId: String): Boolean =
        getBoolean(SettingsKeys.hookerEnabled(hookerId), true)

    fun setHookerEnabled(hookerId: String, value: Boolean) =
        setBoolean(SettingsKeys.hookerEnabled(hookerId), value)

    fun isFeatureEnabled(hookerId: String, featureId: String, default: Boolean): Boolean =
        getBoolean(SettingsKeys.feature(hookerId, featureId), default)

    fun setFeatureEnabled(hookerId: String, featureId: String, value: Boolean) =
        setBoolean(SettingsKeys.feature(hookerId, featureId), value)

    // --- hooker 的取值设置（HookOption）------------------------------------
    // 键的拼法必须和 hook 侧的 HookScope.int / string 完全一致，都走 SettingsKeys.value。

    fun optionInt(hookerId: String, key: String, default: Int): Int =
        getInt(SettingsKeys.value(hookerId, key), default)

    fun setOptionInt(hookerId: String, key: String, value: Int) =
        setInt(SettingsKeys.value(hookerId, key), value)

    fun optionText(hookerId: String, key: String, default: String): String =
        getString(SettingsKeys.value(hookerId, key), default)

    fun setOptionText(hookerId: String, key: String, value: String) =
        setString(SettingsKeys.value(hookerId, key), value)

    /** 当前取值（数值项）。 */
    fun valueOf(hookerId: String, option: HookOption): Int = when (option) {
        is HookOption.Number -> option.coerce(optionInt(hookerId, option.key, option.default))
        is HookOption.Choice -> optionInt(hookerId, option.key, option.default)
        is HookOption.Text, is HookOption.KeyMap, is HookOption.AppList -> 0
    }

    /** 渲染给界面看的当前值，例如 10080 -> "7 天"。 */
    fun displayOf(hookerId: String, option: HookOption): String = when (option) {
        is HookOption.Number -> option.render(valueOf(hookerId, option))
        is HookOption.Choice -> option.labelOf(valueOf(hookerId, option))
        is HookOption.Text -> option.render(optionText(hookerId, option.key, option.default))
        // 映射表的当前值是「设了几个键」，把整串铺开会把行撑爆。
        is HookOption.KeyMap -> option.parse(optionText(hookerId, option.key, option.default))
            .size
            .let { if (it == 0) "未设置" else "$it 个键" }
        // 同理：应用列表只报数量，一个的时候直接把它显示出来。
        is HookOption.AppList -> option.describe(optionText(hookerId, option.key, option.default))
    }

    /** 编辑框里的原始值。数字项不带单位，文本项原样。 */
    fun rawOf(hookerId: String, option: HookOption): String = when (option) {
        is HookOption.Text -> optionText(hookerId, option.key, option.default)
        is HookOption.KeyMap -> optionText(hookerId, option.key, option.default)
        is HookOption.AppList -> optionText(hookerId, option.key, option.default)
        else -> valueOf(hookerId, option).toString()
    }

    /**
     * 写回用户填的东西。
     *
     * 数字项**夹回上下界而不是拒收**：填 999 的意思是「要最大」，不是「填错了」。
     * 整个填空则回到默认值 —— 清空一个数字输入框是最自然的「我不要自定义了」。
     */
    fun writeOption(hookerId: String, option: HookOption, input: String) {
        when (option) {
            is HookOption.Number -> {
                val parsed = input.trim().toIntOrNull() ?: option.default
                setOptionInt(hookerId, option.key, option.coerce(parsed))
            }

            is HookOption.Choice -> {
                val parsed = input.trim().toIntOrNull() ?: option.default
                setOptionInt(hookerId, option.key, option.custom?.coerce(parsed) ?: parsed)
            }

            is HookOption.Text, is HookOption.KeyMap, is HookOption.AppList ->
                setOptionText(hookerId, option.key, input.trim())
        }
    }

    fun writeOption(hookerId: String, option: HookOption, value: Int) {
        setOptionInt(hookerId, option.key, (option as? HookOption.Number)?.coerce(value) ?: value)
    }

    // --- 预设 --------------------------------------------------------------

    /**
     * 套用一套预设。
     *
     * **全量覆盖**：预设没提到的项回到 hooker 声明的默认值，而不是保留现状 ——
     * 否则「换一个预设」的结果会取决于之前是什么状态，用户没法预期。
     *
     * 一次事务写完再 `revision++`，界面只重组一次。
     */
    fun applyPreset(hooker: AppHooker, preset: HookPreset) {
        runCatching {
            val editor = reader().edit()
            hooker.features.forEach { feature ->
                val on = preset.features[feature.id] ?: feature.defaultEnabled
                editor.putBoolean(SettingsKeys.feature(hooker.id, feature.id), on)
            }
            hooker.options.forEach { option ->
                val key = SettingsKeys.value(hooker.id, option.key)
                when (val given = preset.options[option.key]) {
                    is Int -> editor.putInt(key, given)
                    is String -> editor.putString(key, given)
                    else -> when (option) {
                        is HookOption.Number -> editor.putInt(key, option.default)
                        is HookOption.Choice -> editor.putInt(key, option.default)
                        is HookOption.Text -> editor.putString(key, option.default)
                        is HookOption.KeyMap -> editor.putString(key, option.default)
                        is HookOption.AppList -> editor.putString(key, option.default)
                    }
                }
            }
            editor.apply()
        }
        revision++
    }

    /** 当前配置正好等于哪一套预设；都不等于就是 null（「自定义」）。 */
    fun matchedPreset(hooker: AppHooker): HookPreset? = hooker.presets.firstOrNull { preset ->
        hooker.features.all { feature ->
            isFeatureEnabled(hooker.id, feature.id, feature.defaultEnabled) ==
                (preset.features[feature.id] ?: feature.defaultEnabled)
        } && hooker.options.all { option ->
            when (option) {
                is HookOption.Text -> optionText(hooker.id, option.key, option.default) ==
                    (preset.options[option.key] as? String ?: option.default)

                is HookOption.KeyMap -> optionText(hooker.id, option.key, option.default) ==
                    (preset.options[option.key] as? String ?: option.default)

                is HookOption.AppList -> optionText(hooker.id, option.key, option.default) ==
                    (preset.options[option.key] as? String ?: option.default)

                is HookOption.Number -> valueOf(hooker.id, option) ==
                    (preset.options[option.key] as? Int ?: option.default)

                is HookOption.Choice -> valueOf(hooker.id, option) ==
                    (preset.options[option.key] as? Int ?: option.default)
            }
        }
    }

    companion object {
        private const val LOCAL_PREFS = "hooker_settings_local"

        @Volatile
        private var instance: ModuleSettings? = null

        /**
         * Process-wide singleton. `XposedServiceHelper.registerListener` keeps a
         * single static listener and is documented as call-once, so a new
         * instance per Activity recreation would silently unhook the previous
         * one.
         */
        fun get(context: Context): ModuleSettings = instance ?: synchronized(this) {
            instance ?: ModuleSettings(context).also { instance = it }
        }
    }
}
