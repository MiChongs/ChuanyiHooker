package com.chuanyi.hooker.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 模块的日志器。一条日志同时走三个去处：
 *
 * ```
 * log.i("…")
 *   ├─ 框架日志器（XposedInterface.log）  -> LSPosed 的模块日志，拿不到就退 logcat
 *   ├─ logcat                            -> 上一条失败时的兜底
 *   └─ LogRelay                          -> 广播回模块应用，界面上的「日志」那一页
 * ```
 *
 * 前两个是原来就有的；[LogRelay] 那一路是为了让日志在模块界面里看得到 —— 被注入的
 * 进程写进 logcat 的行，模块应用自己是读不到的（需要 `READ_LOGS` 或 root），
 * 理由见那个文件。
 *
 * ## 等级
 *
 * [minLevel] 以下的直接丢，**在做任何字符串拼接之前**。所以 `log.d("… $expensive")`
 * 这种写法在门槛之上时仍然要付出拼接代价 —— 真的很贵的诊断信息，用 [isEnabled]
 * 先问一句再拼。
 *
 * 门槛来自设置里的「日志等级」（[SettingsKeys.LOG_LEVEL]），每一代模块在
 * [HookerRuntime.attach] 里读一次定下来。改了等级要重启目标或热重载才生效，这和
 * 功能开关是同一套规则。
 */
class HookerLog(
    private val xposed: XposedInterface?,
    private val tag: String,
    private val minLevel: LogLevel,
) {
    /**
     * 派生一个子日志器，tag 变成 `父/后缀`。
     *
     * 每个 hooker 拿到的都是 `ChuanyiHooker/<hookerId>` 这一档，界面上按 tag 就能
     * 分辨是谁打的。
     */
    fun child(suffix: String): HookerLog = HookerLog(xposed, "$tag/$suffix", minLevel)

    /** 这一档会不会被打出来。拼一条昂贵的诊断信息之前先问它。 */
    fun isEnabled(level: LogLevel): Boolean = level.passes(minLevel)

    fun v(msg: String) = write(LogLevel.Verbose, msg, null)

    fun d(msg: String) = write(LogLevel.Debug, msg, null)

    fun i(msg: String) = write(LogLevel.Info, msg, null)

    fun w(msg: String, t: Throwable? = null) = write(LogLevel.Warn, msg, t)

    fun e(msg: String, t: Throwable? = null) = write(LogLevel.Error, msg, t)

    private fun write(level: LogLevel, msg: String, t: Throwable?) {
        if (!level.passes(minLevel)) return

        // 先递给中继：它只做一次入队，不碰 IO。放在前面是为了让「框架日志器抛异常」
        // 这种情况也能在界面里看到 —— 那正是最需要日志的时候。
        LogRelay.offer(level, tag, msg, t)

        val priority = level.priority
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

        fun root(xposed: XposedInterface?, minLevel: LogLevel) =
            HookerLog(xposed, ROOT_TAG, minLevel)
    }
}
