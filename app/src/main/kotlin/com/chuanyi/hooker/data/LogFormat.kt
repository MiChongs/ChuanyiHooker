package com.chuanyi.hooker.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日志时间的两种写法。
 *
 * 用 [DateTimeFormatter] 而不是 `SimpleDateFormat`：后者不是线程安全的，而这两个
 * 函数会被列表里成百上千行同时调。
 *
 * 时区跟系统走（和版本号那边写死 `Asia/Shanghai` 不同）—— 那边要的是「同一个提交
 * 在任何机器上编出同一个号」，这边要的是「用户看到的时间和他手机上的时钟一致」。
 */
object LogFormat {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** `14:03:41.220`，列表里的那一列。同一天里看日志，年月日是纯噪音。 */
    private val clockFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)

    /** `08-07 14:03:41.220`，复制和导出时用 —— 那份会离开这一屏，得能对上日期。 */
    private val stampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(zone)

    /** `20260807-140341`，导出文件名用。不带分隔符里的冒号，Windows 上存不下带冒号的名字。 */
    private val fileFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone)

    fun clock(time: Long): String = clockFormat.format(Instant.ofEpochMilli(time))

    fun stamp(time: Long): String = stampFormat.format(Instant.ofEpochMilli(time))

    fun fileStamp(time: Long): String = fileFormat.format(Instant.ofEpochMilli(time))
}
