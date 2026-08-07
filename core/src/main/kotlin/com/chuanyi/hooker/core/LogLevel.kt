package com.chuanyi.hooker.core

import android.util.Log

/**
 * 日志等级。模块自己的一套，不直接用 [Log] 的整数常量。
 *
 * 为什么要自己定义：[Log] 的常量是 2..7 的裸 int，既没有顺序语义（`ASSERT` 排在
 * `ERROR` 后面，但模块用不上它），也没有可以直接落进配置和界面的稳定编号。这里的
 * [id] 是 0..4 连续的，可以当**下标**用 —— 设置里存它、界面里的档位按它排、
 * 落盘的日志行里写它，三处都不必再各自维护一张映射表。
 *
 * [priority] 是往 logcat / 框架日志器写的时候要用的那一份，只在 [HookerLog] 里出现。
 *
 * 等级的用法约定（写 hooker 时按这个来，界面上的筛选才有意义）：
 *
 * | 等级 | 什么时候用 |
 * |---|---|
 * | [Verbose] | 每次回调都会打的量级，排一个具体问题时才需要看 |
 * | [Debug] | 定位过程、命中了哪个锚点、跳过了哪个功能 |
 * | [Info] | 装上了什么、认领了哪个进程 —— 一次注入应该只有几条 |
 * | [Warn] | 降级了但还能用（某个可选定位没找到） |
 * | [Error] | 这个功能没装上 |
 */
enum class LogLevel(
    /** 落盘、存配置、当下标用的稳定编号。**不要改已有的值。** */
    val id: Int,
    /** 界面上的中文档名。 */
    val label: String,
    /** 日志行里那一个字母，等宽字体下正好占一格。 */
    val mark: String,
    /** 写 logcat / 框架日志器时用的 [Log] 常量。 */
    val priority: Int,
) {
    Verbose(0, "详细", "V", Log.VERBOSE),
    Debug(1, "调试", "D", Log.DEBUG),
    Info(2, "信息", "I", Log.INFO),
    Warn(3, "警告", "W", Log.WARN),
    Error(4, "错误", "E", Log.ERROR),
    ;

    /** 这一条够不够 [floor] 这道门槛。 */
    fun passes(floor: LogLevel): Boolean = id >= floor.id

    companion object {
        /**
         * 没设置过时的门槛。
         *
         * 取 [Info] 而不是 [Debug]：一次正常注入在 Info 上只有个位数条，而 Debug 会把
         * 每个功能的开关判定、每次定位的中间结果全都打出来 —— 那是排查时才要的量，
         * 默认开着只会把真正要紧的那几条淹掉。
         */
        val Default = Info

        fun byId(id: Int): LogLevel = entries.firstOrNull { it.id == id } ?: Default

        /** 从 [Log] 的常量反查。认不出来的（比如 `ASSERT`）一律当 [Error]。 */
        fun byPriority(priority: Int): LogLevel =
            entries.firstOrNull { it.priority == priority }
                ?: if (priority > Log.ERROR) Error else Default
    }
}
