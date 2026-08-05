package com.chuanyi.hooker.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission

/**
 * The one runtime permission this module needs: reading the installed app list.
 *
 * On stock Android the per-hooker `<queries>` entries are enough and this is a
 * no-op — XXPermissions reports the permission as granted on ROMs that do not
 * implement it. On MIUI / HyperOS / ColorOS / OneUI the app list is behind
 * `com.android.permission.GET_INSTALLED_APPS`, which those ROMs deny silently:
 * `getPackageInfo` throws `NameNotFoundException` for an app that is plainly
 * installed, and the Apps screen ends up claiming every target is missing.
 *
 * Nothing here is load-bearing for hooking — a denied permission only degrades
 * the Apps screen's install/version column.
 */
object AppListPermission {

    val permission: IPermission
        get() = PermissionLists.getGetInstalledAppsPermission()

    fun isGranted(context: Context): Boolean =
        runCatching { XXPermissions.isGrantedPermission(context, permission) }.getOrDefault(false)

    /**
     * Asks for it. [onResult] always runs, on the main thread.
     *
     * Falls back to reporting the current state when there is no Activity to
     * attach the request fragment to.
     */
    fun request(context: Context, onResult: (Boolean) -> Unit) {
        val activity = context.findActivity()
        if (activity == null) {
            onResult(isGranted(context))
            return
        }
        runCatching {
            XXPermissions.with(activity)
                .permission(permission)
                .request { _, denied -> onResult(denied.isEmpty()) }
        }.onFailure { onResult(isGranted(context)) }
    }

    /** Opens the system page for this permission, for a permanent denial. */
    fun openSettings(context: Context) {
        val activity = context.findActivity() ?: return
        runCatching { XXPermissions.startPermissionActivity(activity, permission) }
    }
}

/** Compose hands out the Activity as a ContextWrapper chain often enough to matter. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
