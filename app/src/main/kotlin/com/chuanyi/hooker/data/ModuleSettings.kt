package com.chuanyi.hooker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chuanyi.hooker.BuildConfig
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookOption
import com.chuanyi.hooker.core.HookPreset
import com.chuanyi.hooker.core.LogLevel
import com.chuanyi.hooker.core.SettingsKeys
import com.chuanyi.hooker.nativehook.NativeHook
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
 * ## ⚠️ 写入一律走 [write]，永远不要用 apply()
 *
 * 远端那份 `SharedPreferences` 是 libxposed 的 `RemotePreferences`，它的 `apply()`
 * **只把新值写进进程内的内存副本，真正送去框架的那一步丢给自己的后台线程池**，
 * 而那个线程池不在 `QueuedWork` 里 —— 系统在 Activity 暂停和进程退出前的自动 flush
 * 覆盖不到它。用户划掉后台就丢，而且界面全程显示成功（读的正是那份内存副本），
 * 直到重进才「变回去」。完整说明见 [write]。
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
                    // 故意**不**把 remote 置空：那个对象手里还攥着最后一次已知配置的
                    // 内存副本，界面继续读它是对的。置空的话 reader() 会退回 local，
                    // 而 local 在迁移成功之后是被清空的 —— 界面会整片显示成默认值，
                    // 看起来就像「配置全没了」。写入会因为 commit 失败落回 local，
                    // 等服务回来再迁移，见 [write]。
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

    /**
     * 把还没送到框架的那些改动补过去。
     *
     * [local] 装的是「写的时候框架没接上」的改动（见 [write]），所以这里是补写而不是
     * 覆盖：送成功了才清空，没成功就留着等下一次绑定。
     */
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
        if (editor.commit()) local.edit().clear().commit()
    }

    private fun reader(): SharedPreferences = remote ?: local

    /**
     * 写一次配置。**所有写入都必须走这里。**
     *
     * ## 为什么不能用 apply()
     *
     * libxposed 的 `RemotePreferences` 不是普通的 SharedPreferences：
     *
     * ```java
     * public void apply() {
     *     var bundle = buildCommitBundle();
     *     doUpdate();                                // ① 只更新进程内那份内存 map
     *     EXECUTOR.execute(() -> doCommit(bundle));  // ② 真正送去框架，丢给后台线程
     * }
     * ```
     *
     * ① 让界面立刻显示成新值，② 才是真正写进框架。而那个 `EXECUTOR` 是它自己的
     * 线程池，**不在 `QueuedWork` 里** —— 系统在 Activity 暂停、以及进程退出前
     * 会自动 flush 的只有普通 SharedPreferences，覆盖不到它。用户划掉后台、
     * 或者 HyperOS 主动清理内存，② 就随进程一起没了。
     *
     * 症状非常具有迷惑性：**界面上一切正常**（读的是 ① 更新过的那份内存 map），
     * 被 hook 的应用拿到的却还是旧值；直到杀掉模块进程重进，界面才「变回去」——
     * 于是看起来像「杀后台导致配置丢失」，实际上那次写入从一开始就没送到框架。
     *
     * `commit()` 走同一条 binder 调用，但是同步的：返回时框架已经收下了。配置写入
     * 是用户点一下才发生一次、且只有几个字节的事，同步完全付得起。
     *
     * ## 没送到怎么办
     *
     * 送不到（服务还没绑上、或者刚断开）就存进 [local]，等 binder 到达时由
     * [migrateLocalInto] 补上，同时把 [isSynced] 打回 false 让界面如实提示 ——
     * 这比假装写成功要好得多。
     */
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val target = remote
        val landed = target != null && runCatching { target.edit().apply(block).commit() }
            .onFailure { serviceError = it.message ?: it.javaClass.simpleName }
            .getOrDefault(false)

        if (!landed) {
            runCatching { local.edit().apply(block).commit() }
            isSynced = false
        }
        revision++
    }

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

    fun setBoolean(key: String, value: Boolean) = write { putBoolean(key, value) }

    fun toggle(key: String, default: Boolean) = setBoolean(key, !getBoolean(key, default))

    /** 同 [getBoolean]：实参那次 [revision] 读取才是重组的依据，函数体不用它。 */
    fun getInt(key: String, default: Int): Int = readInt(revision, key, default)

    private fun readInt(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
        default: Int,
    ): Int = runCatching { reader().getInt(key, default) }.getOrDefault(default)

    fun setInt(key: String, value: Int) = write { putInt(key, value) }

    fun getString(key: String, default: String): String = readString(revision, key, default)

    private fun readString(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
        default: String,
    ): String = runCatching { reader().getString(key, default) ?: default }.getOrDefault(default)

    fun setString(key: String, value: String) = write { putString(key, value) }

    // --- 激活状态 -----------------------------------------------------------
    //
    // 令牌由 TG 客户端进程里的探测签发（见 :hookers:tgguard），广播到这里落盘，
    // 再由每个被注入的进程读走校验。这一侧只负责存和显示，判定全在原生的壳内代码里。

    /** 当前令牌，没有就是 null。 */
    val activationToken: String?
        get() = readNullableString(revision, SettingsKeys.ACTIVATION_TOKEN)

    /** 最后一次拿到令牌的时刻，0 表示从来没有过。 */
    val activationUpdatedAt: Long
        get() = readLong(revision, SettingsKeys.ACTIVATION_UPDATED_AT)

    /** 最后一次签发令牌的 TG 客户端包名，空串表示没有。 */
    val activationSource: String
        get() = readNullableString(revision, SettingsKeys.ACTIVATION_SOURCE).orEmpty()

    /**
     * 模块当前能不能用。
     *
     * 每次读都重新走一遍原生校验，不缓存 —— 令牌带有效期，缓存下来就等于把过期检查
     * 废掉，界面会在令牌早已失效之后还显示「已激活」，而被注入的进程那边已经停了。
     * 一次校验是一次 mmap 加几微秒的 MAC 计算，重组时读它付得起。
     */
    val isActivated: Boolean
        get() = readActivated(revision)

    private fun readActivated(@Suppress("UNUSED_PARAMETER") at: Int): Boolean =
        NativeHook.activationVerify(
            runCatching { reader().getString(SettingsKeys.ACTIVATION_TOKEN, null) }.getOrNull(),
            BuildConfig.VERSION_CODE,
        )

    /**
     * 收下一枚新令牌。只有 [ActivationReceiver] 会调 —— 它在写之前已经用原生层验过，
     * 所以这里不再验一遍。
     */
    fun acceptActivation(token: String, source: String) = write {
        putString(SettingsKeys.ACTIVATION_TOKEN, token)
        putString(SettingsKeys.ACTIVATION_SOURCE, source)
        putLong(SettingsKeys.ACTIVATION_UPDATED_AT, System.currentTimeMillis())
    }

    /**
     * 撤销当前令牌。
     *
     * 三条路会走到这里，都要求**立刻**生效而不是等过期：签发方在库里确认已经退群、
     * 签发方被卸载、签发方被移出了框架作用域（后两条见 [ActivationAudit]）。
     *
     * 令牌本身被删掉，签发方和时间戳留着 —— 界面要能说清「是谁、什么时候失效的」，
     * 而这两个值不参与任何判定。
     */
    fun revokeActivation() = write {
        remove(SettingsKeys.ACTIVATION_TOKEN)
        putLong(SettingsKeys.ACTIVATION_UPDATED_AT, System.currentTimeMillis())
    }

    private fun readNullableString(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
    ): String? = runCatching { reader().getString(key, null) }.getOrNull()

    private fun readLong(
        @Suppress("UNUSED_PARAMETER") at: Int,
        key: String,
    ): Long = runCatching { reader().getLong(key, 0L) }.getOrDefault(0L)

    // --- typed helpers used by the screens ---------------------------------

    var masterEnabled: Boolean
        get() = getBoolean(SettingsKeys.MASTER_ENABLED, true)
        set(value) = setBoolean(SettingsKeys.MASTER_ENABLED, value)

    /**
     * 日志门槛。低于它的那些行在被注入的进程里就被丢掉了，不会产生任何开销。
     *
     * 读的时候要兼容旧键：只有「详细日志」开关的版本升上来时 [SettingsKeys.LOG_LEVEL]
     * 还不存在，此时按那个开关折算 —— 和读侧 [com.chuanyi.hooker.core.RemoteHookerSettings.logLevel]
     * 必须是同一套折算，否则界面显示的档和实际生效的档会对不上。
     *
     * 写的时候只写新键，旧键留在原地不管：万一用户装回旧版本，那边读到的仍是他
     * 当初设的值，而不是一个被新版本改过的、他不知情的状态。
     */
    var logLevel: LogLevel
        get() = readLogLevel(revision)
        set(value) = setInt(SettingsKeys.LOG_LEVEL, value.id)

    private fun readLogLevel(@Suppress("UNUSED_PARAMETER") at: Int): LogLevel {
        val stored = runCatching { reader().getInt(SettingsKeys.LOG_LEVEL, -1) }.getOrDefault(-1)
        if (stored >= 0) return LogLevel.byId(stored)
        val legacy = runCatching { reader().getBoolean(SettingsKeys.VERBOSE_LOG, false) }
            .getOrDefault(false)
        return if (legacy) LogLevel.Debug else LogLevel.Default
    }

    /** 被注入的进程要不要把日志递回来。关掉之后「日志」那一页只剩模块自己的行。 */
    var logRelay: Boolean
        get() = getBoolean(SettingsKeys.LOG_RELAY, true)
        set(value) = setBoolean(SettingsKeys.LOG_RELAY, value)

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
     * 整套一次性写完（[write] 里是一个 editor 一次 commit），界面只重组一次。
     */
    fun applyPreset(hooker: AppHooker, preset: HookPreset) = write {
        hooker.features.forEach { feature ->
            val on = preset.features[feature.id] ?: feature.defaultEnabled
            putBoolean(SettingsKeys.feature(hooker.id, feature.id), on)
        }
        hooker.options.forEach { option ->
            val key = SettingsKeys.value(hooker.id, option.key)
            when (val given = preset.options[option.key]) {
                is Int -> putInt(key, given)
                is String -> putString(key, given)
                else -> when (option) {
                    is HookOption.Number -> putInt(key, option.default)
                    is HookOption.Choice -> putInt(key, option.default)
                    is HookOption.Text -> putString(key, option.default)
                    is HookOption.KeyMap -> putString(key, option.default)
                    is HookOption.AppList -> putString(key, option.default)
                }
            }
        }
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
