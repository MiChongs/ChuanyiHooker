package com.chuanyi.hooker.core

/**
 * Self-activation probe.
 *
 * These methods return the "not hooked" answer as compiled. When the module is
 * actually active, it hooks its own package and replaces them, which is how the
 * UI knows the Xposed framework picked the module up. Do not make them
 * `const`/inline — they must stay real, hookable methods.
 */
object ModuleStatus {

    @JvmStatic
    fun isActivated(): Boolean = false

    /** e.g. "LSPosed 1.10.3 (7132)". Empty when not activated. */
    @JvmStatic
    fun frameworkName(): String = ""

    /** libxposed API level reported by the framework, 0 when not activated. */
    @JvmStatic
    fun frameworkApiVersion(): Int = 0

    /** How many hooker services the module process can see. -1 when unknown. */
    @JvmStatic
    fun loadedHookerCount(): Int = -1
}
