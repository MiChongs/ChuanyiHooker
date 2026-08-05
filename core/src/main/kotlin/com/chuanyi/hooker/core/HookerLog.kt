package com.chuanyi.hooker.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Routes through the framework logger when there is one (so lines land in the
 * LSPosed module log), otherwise through logcat.
 *
 * [d] is suppressed unless verbose logging is on in the module UI.
 */
class HookerLog(
    private val xposed: XposedInterface?,
    private val tag: String,
    private val verbose: Boolean,
) {
    fun child(suffix: String): HookerLog = HookerLog(xposed, "$tag/$suffix", verbose)

    fun d(msg: String) {
        if (verbose) write(Log.DEBUG, msg, null)
    }

    fun i(msg: String) = write(Log.INFO, msg, null)

    fun w(msg: String, t: Throwable? = null) = write(Log.WARN, msg, t)

    fun e(msg: String, t: Throwable? = null) = write(Log.ERROR, msg, t)

    private fun write(priority: Int, msg: String, t: Throwable?) {
        val x = xposed
        if (x != null) {
            runCatching {
                if (t != null) x.log(priority, tag, msg, t) else x.log(priority, tag, msg)
            }.onSuccess { return }
        }
        if (t != null) Log.println(priority, tag, "$msg\n${Log.getStackTraceString(t)}")
        else Log.println(priority, tag, msg)
    }

    companion object {
        const val ROOT_TAG = "ChuanyiHooker"

        fun root(xposed: XposedInterface?, verbose: Boolean) = HookerLog(xposed, ROOT_TAG, verbose)
    }
}
