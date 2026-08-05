package com.chuanyi.hooker.core

/**
 * A single user-toggleable behaviour inside an [AppHooker].
 *
 * [install] runs inside EzHookTool's `onTargetReady` transaction, so every hook
 * registered from it participates in libxposed 102 hot reload: on the next
 * module update the framework atomically replaces the old physical hooks
 * instead of unhooking and reinstalling.
 *
 * Because of that, read the switch state *outside* the hook callback (as this
 * framework does — disabled features are simply never installed) unless the
 * feature must react without a reload, in which case keep the hook installed
 * and check the flag inside the callback.
 */
class HookFeature(
    val id: String,
    val title: String,
    val summary: String = "",
    val defaultEnabled: Boolean = true,
    /** true = flipping the switch cannot take effect until the app restarts. */
    val requiresRestart: Boolean = true,
    val install: HookScope.() -> Unit,
) {
    override fun toString(): String = "HookFeature($id)"
}
