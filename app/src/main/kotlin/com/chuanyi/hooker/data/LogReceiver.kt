package com.chuanyi.hooker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chuanyi.hooker.core.LogRelay

/**
 * 收下被注入的进程送回来的那一批日志，交给 [LogStore]。
 *
 * ## 为什么需要这一跳
 *
 * hook 跑在目标应用的进程里，它的日志落在 logcat 和 LSPosed 的模块日志里，两处模块
 * 应用自己都读不到（前者要 `READ_LOGS` 或 root，后者在管理器的私有目录里）。所以
 * 只能让产生日志的那一侧主动递过来 —— 和激活令牌那一跳（[ActivationReceiver]）是
 * 同一个形状。攒批与丢弃策略在发送侧，见 [LogRelay]。
 *
 * ## 导出它是安全的，但内容不可信
 *
 * 必须导出：发送方是别的 uid。于是任何应用都能往这里发一条伪造的日志，而这里
 * **无法**分辨 —— 广播接收器拿不到可信的发送方身份，而目标进程也不可能持有本模块的
 * 签名权限（它是别人的 uid）。
 *
 * 这个风险是可以接受的，因为收进来的东西**只会被显示**：不参与任何判定，不写进配置，
 * 也不会被执行。最坏情况是日志页里多出几行假的。真正要紧的那条链路（激活令牌）走的
 * 是另一个接收器，那边每一条都验 MAC。
 *
 * 所以这里只做形状检查：进程名缺失、数组长度对不上就丢掉，不报错、不弹提示 ——
 * 为一条垃圾广播打扰用户是不合算的。
 *
 * ## 不用 goAsync
 *
 * [LogStore.appendBatch] 只往内存列表里塞，落盘由它自己丢给后台线程。整个 `onReceive`
 * 是几十微秒的事，没有等待任何东西的必要。
 */
class LogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LogRelay.Wire.ACTION) return

        val process = intent.getStringExtra(LogRelay.Wire.EXTRA_PROCESS)
            ?.takeIf { it.isNotEmpty() } ?: return
        val times = intent.getLongArrayExtra(LogRelay.Wire.EXTRA_TIMES) ?: return
        val levels = intent.getIntArrayExtra(LogRelay.Wire.EXTRA_LEVELS) ?: return
        val tags = intent.getStringArrayExtra(LogRelay.Wire.EXTRA_TAGS) ?: return
        val messages = intent.getStringArrayExtra(LogRelay.Wire.EXTRA_MESSAGES) ?: return
        val dropped = intent.getIntExtra(LogRelay.Wire.EXTRA_DROPPED, 0)

        // 界面可能还没被打开过，那时 LogStore 还没接上磁盘。先接上，否则这一批只
        // 存在于内存里，用户等会儿打开界面就看不到它了。
        LogStore.restore(context)

        LogStore.appendBatch(
            process = process,
            times = times,
            levels = levels,
            // getStringArrayExtra 的元素在理论上可以是 null（Bundle 里存的是
            // Object[]），空串比让下游到处判空好。
            tags = Array(tags.size) { tags[it].orEmpty() },
            messages = Array(messages.size) { messages[it].orEmpty() },
            droppedBySender = dropped.coerceAtLeast(0),
        )
    }
}
