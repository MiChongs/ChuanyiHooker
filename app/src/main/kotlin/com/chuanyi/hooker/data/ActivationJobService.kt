package com.chuanyi.hooker.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock

/**
 * 每天在后台跑一次 [ActivationAudit]。
 *
 * ## 它补的是哪个洞
 *
 * 「签发方被移出框架作用域」和「签发方被卸载」这两件事，只有模块应用这一侧看得见 ——
 * 被移出作用域之后，TG 进程里那个探测再也不会被执行，它连自己已经失联都不知道。
 *
 * 而模块应用的前台稽核只在用户**打开界面**时才跑。真实情况是：用户在 LSPosed 里
 * 把勾去掉之后，根本没有理由再打开模块界面。少了这个作业，那种情况只能等令牌过期，
 * 空窗期就是整整一个有效期。
 *
 * ## 它不是唯一防线，也不该是
 *
 * 国产 ROM 上后台作业被杀是常态，所以令牌的有效期仍然是最终兜底
 * （[com.chuanyi.hooker.nativehook.NativeHook.ACTIVATION_TTL_DAYS]）。这个作业只是
 * 把「作业活着」这条常见路径上的空窗从两天压到一天。
 *
 * ## 为什么要等 binder
 *
 * 稽核要读框架当前的作用域，而那要等 `XposedService` 的 binder 送达（异步，见
 * [ModuleSettings]）。作业进程往往是被这次调度**新拉起来**的，binder 还在路上；
 * 不等就跑，[FrameworkService.scope] 是空表，稽核会因为「数据不可信」直接放弃 ——
 * 等于作业白跑。
 */
class ActivationJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        Thread({
            try {
                val settings = ModuleSettings.get(context)
                awaitBinder(settings)
                // 拉完再稽核。作业没有组合可以等重组，必须同步。
                settings.framework.refreshBlocking()
                ActivationAudit.run(context, settings)
            } finally {
                // needsReschedule = false：这是周期作业，系统自己会排下一次。
                jobFinished(params, false)
            }
        }, "chuanyi-activation-job").start()
        // true = 还在后台线程上干活，别急着回收。
        return true
    }

    /**
     * 系统要收回资源（进入 Doze、约束不再满足）。返回 true 让它按周期重排 ——
     * 这一次没跑完不要紧，稽核本来就是幂等的。
     */
    override fun onStopJob(params: JobParameters): Boolean = true

    private fun awaitBinder(settings: ModuleSettings) {
        val deadline = SystemClock.uptimeMillis() + BINDER_TIMEOUT_MILLIS
        while (!settings.isFrameworkBound && SystemClock.uptimeMillis() < deadline) {
            runCatching { Thread.sleep(100) }.onFailure { return }
        }
    }

    private companion object {
        const val BINDER_TIMEOUT_MILLIS = 10_000L
    }
}

/** 排期。装上之后只要有任意一处入口跑过一次，作业就常驻了。 */
object ActivationJob {

    /**
     * 任意固定值，只要在本应用内唯一且不变。变了会留下一个再也没人认领的旧作业，
     * 直到重装才清掉。
     */
    private const val JOB_ID = 0x43594731

    private const val PERIOD_MILLIS = 24L * 60 * 60 * 1000

    /**
     * 确保作业已排期。重复调用是廉价的 —— 已经排着就直接返回，不会把周期重置。
     *
     * 两处调用：模块界面启动时，以及收到令牌广播时（[ActivationReceiver]）。后者是
     * 关键 —— 有的人装完模块、勾好作用域、开一次 TG 就再也不打开模块界面了，只有那
     * 条路径能保证作业被排上。
     */
    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull() != null) return

        val info = JobInfo.Builder(JOB_ID, ComponentName(context, ActivationJobService::class.java))
            .setPeriodic(PERIOD_MILLIS)
            // 重启后继续 —— 需要 RECEIVE_BOOT_COMPLETED，已在清单里声明。
            .setPersisted(true)
            // 稽核是纯本地的（读框架 binder + PackageManager），不碰网。
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .build()
        runCatching { scheduler.schedule(info) }
    }
}
