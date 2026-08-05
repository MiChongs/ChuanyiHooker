package com.chuanyi.hooker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chuanyi.hooker.data.AppListPermission

/**
 * Grant state for [AppListPermission], re-checked whenever the screen comes back
 * to the foreground — the user may have granted it from the system settings page
 * rather than from the in-app dialog.
 */
class AppListPermissionState internal constructor(granted: Boolean) {

    var isGranted by mutableStateOf(granted)
        internal set
}

@Composable
fun rememberAppListPermissionState(): AppListPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context) { AppListPermissionState(AppListPermission.isGranted(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.isGranted = AppListPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Requests the permission and folds the outcome back into [state]. */
fun AppListPermissionState.request(context: android.content.Context) {
    AppListPermission.request(context) { granted -> isGranted = granted }
}
