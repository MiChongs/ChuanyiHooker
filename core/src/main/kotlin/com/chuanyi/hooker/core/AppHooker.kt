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

    /** Everything the user can toggle. Order is preserved in the UI. */
    val features: List<HookFeature>

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
