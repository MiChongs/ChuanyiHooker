package com.chuanyi.hooker.core

/**
 * One hooker == one target app.
 *
 * Implementations are discovered with [java.util.ServiceLoader]; drop a file at
 * `src/main/resources/META-INF/services/com.chuanyi.hooker.core.AppHooker`
 * containing the implementation's fully-qualified name and it gets picked up.
 * Nothing in this module or in [com.chuanyi.hooker.core] knows about any
 * concrete target app.
 *
 * Implementations must have a public no-arg constructor and must not touch
 * target classes from the constructor — it runs before the target class loader
 * is known.
 */
interface AppHooker {

    /** Stable identifier, used as the settings key prefix. Never rename it. */
    val id: String

    /** Shown in the module UI. */
    val displayName: String

    /** One line shown under [displayName] in the UI. */
    val description: String
        get() = ""

    /** Packages this hooker claims. Must match the module's `scope.list`. */
    val targetPackages: Set<String>

    /**
     * 这个 hooker 不受激活闸门约束，且不出现在应用列表里。
     *
     * 只有一种东西该返回 true：**产出激活凭据的那个 hooker 自己**（`:hookers:tgguard`）。
     * 它要是也被闸门挡住，就永远拿不到令牌，闸门自己把自己锁死了。
     *
     * 同理它也不受「单个应用的开关」影响 —— 见 [HookerRuntime.selectHookers]。
     * 一个关掉之后会让整个模块停止工作的开关，摆在界面上只会制造误解。
     */
    val bypassesActivation: Boolean
        get() = false

    /** Everything the user can toggle. Order is preserved in the UI. */
    val features: List<HookFeature>

    /**
     * 用户能填具体值的设置项，例如条数上限、有效期。顺序即界面顺序。
     *
     * 和 [features] 分开是因为二者在界面上是两段：开关决定「做不做」，
     * 取值决定「做到什么程度」。
     */
    val options: List<HookOption>
        get() = emptyList()

    /**
     * 几套调好的配置，用户点一下就套用全部开关与取值。
     *
     * 项目多的 hooker 才需要 —— 只有一两个开关时，预设比逐项开还麻烦。
     */
    val presets: List<HookPreset>
        get() = emptyList()

    /**
     * When [onHook] runs.
     *
     * [Stage.PACKAGE_READY] is the normal choice: the real class loader exists
     * and a custom `AppComponentFactory` has already been created.
     *
     * [Stage.PACKAGE_LOADED] runs earlier — before the target's
     * `AppComponentFactory` is instantiated — for targets that must be patched
     * before any application code runs. It only sees `defaultClassLoader`.
     */
    val stage: Stage
        get() = Stage.PACKAGE_READY

    /**
     * Optional narrowing beyond the package name, e.g. a version range.
     * Returning false makes the whole hooker sit out for this process.
     */
    fun isCompatible(scope: HookScope): Boolean = true

    /**
     * Called once per target process, at [stage], before any feature installs.
     * Use it for shared lookups the features depend on. Throwing here aborts
     * this hooker only; other hookers still run.
     */
    fun onHook(scope: HookScope) {}

    /**
     * Called after every enabled feature has been installed. Use it for
     * bookkeeping that must see the final state.
     */
    fun onHooked(scope: HookScope, installed: List<HookFeature>) {}

    enum class Stage { PACKAGE_LOADED, PACKAGE_READY }
}
