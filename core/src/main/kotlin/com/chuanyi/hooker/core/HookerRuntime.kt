package com.chuanyi.hooker.core

import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Modifier

/**
 * Framework side of the module: works out which hookers apply to the process we
 * landed in, then installs their enabled features.
 *
 * Ordering, per libxposed 102 + EzHookTool:
 *
 *  1. `onModuleLoaded`  -> [attach], then `EzXposed.onTargetReady { install() }`
 *  2. `onPackageLoaded` -> [onPackageLoaded] says whether this process matters
 *     and which EzXposed init the entry should call
 *  3. `onPackageReady`  -> [onPackageReady], same idea for the normal stage
 *  4. EzXposed fires target-ready -> [install] runs inside the hot-reload
 *     transaction, so every hook registered from it gets a stable physical id
 *     and is atomically replaced on the next module update
 *
 * The entry class in `:app` is the only caller.
 */
object HookerRuntime {

    /** What the entry should do with the callback it is currently in. */
    enum class Decision {
        /** Nothing here for us — detach the entry. */
        IGNORE,

        /** Relevant, but install later; just point EzXposed at the class loader. */
        PREPARE,

        /** Install now, at this stage. */
        INSTALL,
    }

    /**
     * 跨代数组的魔数。热重载的 saved state 是一个不透明的 `Array<Any?>`，
     * framework 只保证原样回传 —— 拿到手要自己确认这份东西确实是自己写的。
     */
    private const val CLAIM_MAGIC = "com.chuanyi.hooker.claim/1"

    private var xposed: XposedInterface? = null
    private var settings: HookerSettings = HookerSettings.AllDefaults
    private var log: HookerLog = HookerLog.root(null, false)
    private var modulePackage: String? = null

    /** Target claimed for this process, or null while we are just passing through. */
    @Volatile
    private var claimed: Claim? = null

    private class Claim(
        val packageName: String,
        val processName: String,
        val appInfo: ApplicationInfo,
        val classLoader: ClassLoader,
        val isFirstPackage: Boolean,
        val stage: AppHooker.Stage,
        val hookers: List<AppHooker>,
        val isSelf: Boolean,
    )

    fun attach(base: XposedInterface, param: ModuleLoadedParam) {
        xposed = base
        val remote = RemoteHookerSettings(base)
        settings = remote
        log = HookerLog.root(base, remote.isVerbose())
        modulePackage = runCatching { base.moduleApplicationInfo.packageName }.getOrNull()
        if (!remote.isBacked) {
            log.d("no remote preferences (embedded framework or module never opened); using defaults")
        }
        log.d("module loaded in ${param.processName} (systemServer=${param.isSystemServer})")
    }

    fun onPackageLoaded(param: PackageLoadedParam): Decision = claim(
        packageName = param.packageName,
        appInfo = param.applicationInfo,
        classLoader = param.defaultClassLoader,
        isFirstPackage = param.isFirstPackage,
        stage = AppHooker.Stage.PACKAGE_LOADED,
    )

    fun onPackageReady(param: PackageReadyParam): Decision = claim(
        packageName = param.packageName,
        appInfo = param.applicationInfo,
        classLoader = param.classLoader,
        isFirstPackage = param.isFirstPackage,
        stage = AppHooker.Stage.PACKAGE_READY,
    )

    /**
     * 认领一个进程。
     *
     * **「当前不装东西」和「这个进程与我们无关」是两回事**，这里必须分开 ——
     * 早先把两者都归到 [Decision.IGNORE] 是这个模块热重载不完整的第二个原因：
     *
     * [Decision.IGNORE] 会让 entry 调 `detachCurrentEntry()`，此后 framework 不再向它
     * 分发任何回调，**包括 `onHotReloading`**。于是「总开关关着」或「这个应用被单独
     * 关掉」的进程会永久失去热重载能力：用户在界面上重新打开之后只能强停目标才生效，
     * 而那恰恰是热重载最该覆盖的场景。日志里成片的
     * `status=1, The old module has already detached its entry` 就是这么来的。
     *
     * 现在只有一种情况才 detach：**这个包我们根本没有 hooker**。其余一律认领下来，
     * 装什么则留到 [install] 按当时的设置决定 —— 一个都不装也要认领，因为
     * EzHookTool 需要一个已建立的 target snapshot 才允许后续热重载
     * （没有 snapshot 时它直接拒绝，就是日志里的 `status=3`）。
     */
    private fun claim(
        packageName: String,
        appInfo: ApplicationInfo,
        classLoader: ClassLoader,
        isFirstPackage: Boolean,
        stage: AppHooker.Stage,
    ): Decision {
        val isSelf = packageName == modulePackage
        val known = if (isSelf) emptyList() else HookerRegistry.forPackage(packageName)

        if (!isSelf && known.isEmpty()) {
            log.d("没有适配 $packageName 的 hooker，放开这个进程")
            return Decision.IGNORE
        }

        val selected = selectHookers(isSelf, known, stage)

        // 早装阶段只为「明确要求早装」的 hooker 停一次；其余（含一个都没启用的情况）
        // 一律等到 PACKAGE_READY —— 那时才建立 snapshot。
        if (stage == AppHooker.Stage.PACKAGE_LOADED && selected.isEmpty()) {
            return Decision.PREPARE
        }

        claimed = Claim(
            packageName = packageName,
            processName = currentProcessName(packageName),
            appInfo = appInfo,
            classLoader = classLoader,
            isFirstPackage = isFirstPackage,
            stage = stage,
            hookers = selected,
            isSelf = isSelf,
        )
        return Decision.INSTALL
    }

    /**
     * 这一阶段要装哪些 hooker。可能是空的 —— 空不代表放弃这个进程，见 [claim]。
     *
     * 总开关关着时返回空表：进程照样认领、snapshot 照样建立，用户把开关打回去之后
     * 一次热重载就能生效。
     */
    private fun selectHookers(
        isSelf: Boolean,
        known: List<AppHooker>,
        stage: AppHooker.Stage,
    ): List<AppHooker> = when {
        !settings.isModuleEnabled() -> emptyList()
        isSelf -> emptyList()
        else -> known.filter { settings.isHookerEnabled(it.id) && it.stage == stage }
    }

    // -----------------------------------------------------------------------
    // 热重载
    //
    // 热重载给模块的是一个**全新的 classloader**，所以新一代里这个 object 是全新的：
    // [xposed] 和 [claimed] 都还是 null。而 framework 只重放 `onHotReloaded`，
    // **不会**重放 `onModuleLoaded` / `onPackageLoaded` / `onPackageReady` ——
    // 也就是说原本设置这两个字段的三个回调一个都不会再来。
    //
    // 不在这里重建的后果不是「少装了几个 hook」而是更糟：[install] 会在第一行直接
    // return，EzHookTool 看到新一代一个 hook 都没声明，就按约定把上一代的 handle
    // 全部撤掉 —— 模块在这个进程里彻底失效，且只能靠强停目标恢复。
    // -----------------------------------------------------------------------

    /**
     * 把当前认领结果拍平成能跨代传递的数组，交给 `HotReloadingParam.setSavedInstanceState`。
     *
     * libxposed 102 对 saved state 有一条硬约束：**只能放 boot / system classloader
     * 加载的对象**。模块 classloader 创建的东西（[AppHooker.Stage] 这个枚举、[AppHooker]
     * 实例、[Claim] 本身）放进去会让 framework 直接拒掉这次热重载。所以这里全部拆成
     * `String` / `Boolean` / [ClassLoader] / [ApplicationInfo] 这几种 bootclasspath 类型。
     *
     * **不传 hooker 列表是有意的**：新一代要按新的注册表和新的设置重新挑一遍，
     * 那正是热重载的意义 —— 改了代码、改了开关，重载后就该按新的来。
     *
     * 返回 null 表示这个进程没有认领任何东西，没有可恢复的状态。
     */
    fun crossGenerationState(): Array<Any?>? {
        val target = claimed ?: return null
        return arrayOf(
            CLAIM_MAGIC,
            target.packageName,
            target.processName,
            target.classLoader,
            target.appInfo,
            target.isFirstPackage,
            target.stage.name,
            target.isSelf,
        )
    }

    /**
     * 在新一代里重建认领结果，由 `onHotReloaded` 的 `onExtra` 回调驱动。
     *
     * EzHookTool 保证这个回调跑在 target-ready 分发**之前**，所以 [install] 拿到的
     * 一定是重建好的 [claimed]。
     *
     * hooker 列表按**当前**的注册表和设置重新挑：
     *
     * - 总开关关掉了、这个应用被单独关掉了、或者启用的功能一个不剩 → 返回 false 并把
     *   认领清空。这不是失败，是正确结果：新一代什么都不装，EzHookTool 随后把上一代的
     *   hook 撤干净，等于「关掉模块并立即生效」，不必强停目标。
     *
     * @return 是否有东西要装
     */
    fun restoreClaim(extra: Array<Any?>?): Boolean {
        val fields = extra
        if (fields == null || fields.size < 8 || fields[0] != CLAIM_MAGIC) {
            log.w("热重载没有带回上一代的认领信息，这个进程本次不会重新安装")
            claimed = null
            return false
        }

        val packageName = fields[1] as? String
        val processName = fields[2] as? String
        val classLoader = fields[3] as? ClassLoader
        val appInfo = fields[4] as? ApplicationInfo
        val isFirstPackage = fields[5] as? Boolean
        val stage = (fields[6] as? String)?.let { name ->
            AppHooker.Stage.entries.firstOrNull { it.name == name }
        }
        val isSelf = fields[7] as? Boolean

        if (packageName == null || processName == null || classLoader == null ||
            appInfo == null || isFirstPackage == null || stage == null || isSelf == null
        ) {
            log.w("热重载带回的认领信息不完整，这个进程本次不会重新安装")
            claimed = null
            return false
        }

        // 按**当前**的注册表和设置重新挑一遍，可能挑出空表 —— 那不是失败：
        // 用户刚把这个应用（或总开关）关掉，正确结果就是「新一代什么都不装，
        // EzHookTool 把上一代的 hook 撤干净」，等于关掉即时生效，不必强停目标。
        // 认领本身仍然保留，否则下一次热重载（比如用户又打开了）就没有落脚点。
        val known = if (isSelf) emptyList() else HookerRegistry.forPackage(packageName)
        val hookers = selectHookers(isSelf, known, stage)

        claimed = Claim(
            packageName = packageName,
            processName = processName,
            appInfo = appInfo,
            classLoader = classLoader,
            isFirstPackage = isFirstPackage,
            stage = stage,
            hookers = hookers,
            isSelf = isSelf,
        )
        log.d(
            "热重载：已重建 $packageName@$processName 的认领" +
                "（stage=$stage，${if (isSelf) "自检探针" else "${hookers.size} 个 hooker"}）",
        )
        return true
    }

    /**
     * Installs everything for the claimed process. Called from EzHookTool's
     * target-ready callback — on the initial load *and* on every hot reload, so
     * it must be idempotent with respect to its own inputs (it is: every hook is
     * re-registered from scratch and the framework replaces the previous
     * generation atomically).
     */
    fun install() {
        // 这两条以前是静默 return，于是热重载失效时日志里什么都没有 —— 现在说清楚。
        // 正常情况下走不到：认领在 onPackageLoaded/Ready 里建立，热重载时由
        // [restoreClaim] 重建；两者都没发生就说明接线漏了一环。
        val target = claimed ?: run {
            log.d("没有认领任何目标，不安装")
            return
        }
        val base = xposed ?: run {
            log.e("XposedInterface 未接上（attach 没被调用），本次不安装")
            return
        }

        if (target.isSelf) {
            // 总开关的判断挪到了这里：[claim] 不再因为开关关着就放弃这个进程，
            // 否则关掉之后连热重载的资格一起丢了。
            if (settings.isModuleEnabled()) installSelfProbe(base, target)
            else log.d("总开关已关闭，自检探针不安装")
            return
        }

        if (target.hookers.isEmpty()) {
            log.d("${target.packageName} 当前没有启用的 hooker，不安装任何东西")
            return
        }

        for (hooker in target.hookers) {
            val scope = HookScope(
                xposed = base,
                packageName = target.packageName,
                processName = target.processName,
                appInfo = target.appInfo,
                classLoader = target.classLoader,
                isFirstPackage = target.isFirstPackage,
                stage = target.stage,
                settings = settings,
                log = log.child(hooker.id),
                hookerId = hooker.id,
            )

            val compatible = runCatching { hooker.isCompatible(scope) }
                .onFailure { scope.log.e("isCompatible() threw", it) }
                .getOrDefault(false)
            if (!compatible) {
                scope.log.i("skipped: not compatible with ${target.packageName} v${scope.versionCode}")
                continue
            }

            val prepared = runCatching { hooker.onHook(scope) }
                .onFailure { scope.log.e("onHook() failed, features skipped", it) }
                .isSuccess
            if (!prepared) continue

            val installed = ArrayList<HookFeature>()
            for (feature in hooker.features) {
                if (!scope.isEnabled(feature)) {
                    scope.log.d("feature '${feature.id}' disabled")
                    continue
                }
                // One broken feature must not cost the user the other ones.
                runCatching { feature.install(scope) }
                    .onSuccess { installed += feature; scope.log.i("feature '${feature.id}' installed") }
                    .onFailure { scope.log.e("feature '${feature.id}' failed", it) }
            }

            runCatching { hooker.onHooked(scope, installed) }
                .onFailure { scope.log.e("onHooked() failed", it) }

            scope.log.i("done: ${installed.size}/${hooker.features.size} feature(s) active")
        }
    }

    /**
     * Rewrites [ModuleStatus] inside the module's own process so the UI can tell
     * whether the framework actually loaded us, and which framework it is.
     */
    private fun installSelfProbe(base: XposedInterface, target: Claim) {
        val frameworkLabel = runCatching {
            "${base.frameworkName} ${base.frameworkVersion} (${base.frameworkVersionCode})"
        }.getOrDefault("unknown")
        val api = runCatching { base.apiVersion }.getOrDefault(0)
        val hookerCount = HookerRegistry.all().size

        val statusClass = runCatching {
            Class.forName(ModuleStatus::class.java.name, false, target.classLoader)
        }.getOrElse {
            log.e("self probe: ModuleStatus missing from module process", it)
            return
        }

        // A Kotlin object with @JvmStatic has both a static forwarder and an
        // instance method of the same name; which one a call site uses is not
        // worth guessing, so replace both.
        fun replace(name: String, value: Any?) {
            val methods = statusClass.declaredMethods.filter { it.name == name && it.parameterCount == 0 }
            if (methods.isEmpty()) {
                log.w("self probe: no method $name")
                return
            }
            methods.forEach { m ->
                val kind = if (Modifier.isStatic(m.modifiers)) "static" else "instance"
                runCatching {
                    base.hook(m).setId("self-probe:$name:$kind").intercept { value }
                }.onFailure { log.w("self probe: cannot hook $name ($kind)", it) }
            }
        }

        replace("isActivated", true)
        replace("frameworkName", frameworkLabel)
        replace("frameworkApiVersion", api)
        replace("loadedHookerCount", hookerCount)
        log.i("self probe installed ($frameworkLabel, api=$api, hookers=$hookerCount)")
    }

    /** Best-effort process name; libxposed only reports it at module-load time. */
    private fun currentProcessName(fallback: String): String = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentProcessName")
            .invoke(null) as? String
    }.getOrNull() ?: fallback

    /** Exposed for diagnostics. */
    fun currentSettings(): HookerSettings = settings

    /**
     * 本代的日志器，给 entry 记热重载过程用。
     *
     * [attach] 之前拿到的是那个往 logcat 写的兜底实现 —— 热重载失败的诊断信息不该
     * 因为「还没接上 framework」而丢掉。
     */
    fun log(): HookerLog = log
}
