package com.chuanyi.hooker.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「加入我们」弹窗什么时候弹。
 *
 * 两条判据：**首次使用**和**使用频率**。
 *
 * | 弹第几次 | 触发点 | 说的事 |
 * |---|---|---|
 * | 1 | 第 1 次启动 | 刚装上，先说清楚有频道、有群、能提需求 |
 * | 2 | 第 5 次启动 | 还在用，不是装完看一眼就删 —— 这时候邀请才有意义 |
 * | 3 | 第 20 次启动 | 已经是常用工具了 |
 * | 4 | 第 50 次启动 | 最后一次，之后永远不弹 |
 *
 * 里程碑数的是**启动次数**而不是天数：一个每天开三次的人，和一个装了半年只开过两次的
 * 人，在日历上一样长，但只有前者值得被拉进群。
 *
 * 两道防打扰的闸：
 *
 *  * **冷却 3 天。** 里程碑之间只差几次启动，一下午来回开二十次能把两三档一次性烧完。
 *    冷却把它们摊到不同的日子上。首次使用不受冷却限制 —— 那时候还没有「上一次」。
 *  * **错过不作废。** 命中里程碑但正在冷却里，不是把这一档丢掉，而是留到下次启动再弹。
 *    判据是「已经弹过几次」而不是「启动次数正好等于某个数」，所以永远补得回来。
 *
 * 点「不再提示」就永久关闭。关于页的「社区」一节始终在，随时能自己进去 —— 这个弹窗只
 * 负责让人**知道**有这么个地方，不负责当唯一入口。
 *
 * 状态存在**本地** SharedPreferences，不走 [ModuleSettings] 那份远程配置：这是模块自己
 * 界面的事，被 hook 的应用不该看见，也不该占用框架的存储。
 */
@Stable
class CommunityInvite private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本次是第几次启动（含这一次）。 */
    val launchCount: Int = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1

    /** 首次使用。弹窗的措辞跟之后几次不一样：那次是「欢迎」，之后是「加入我们」。 */
    val isFirstLaunch: Boolean = launchCount == 1

    /** 弹窗是否正在显示。 */
    var isShowing by mutableStateOf(false)
        private set

    /** 到目前为止弹过几次 —— 它同时也是下一个里程碑在 [LAUNCH_MILESTONES] 里的下标。 */
    private val shownTimes: Int get() = prefs.getInt(KEY_SHOWN_TIMES, 0)

    init {
        prefs.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()

        if (shouldShow()) {
            // 决定要弹的当下就记账，而不是等用户关掉时再记：进程在弹窗停留期间被杀
            // （Xposed 模块的宿主经常被系统清理），这一档不该重来一遍。
            prefs.edit()
                .putInt(KEY_SHOWN_TIMES, shownTimes + 1)
                .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
                .apply()
            isShowing = true
        }
    }

    private fun shouldShow(): Boolean {
        if (prefs.getBoolean(KEY_OPTED_OUT, false)) return false

        val shown = shownTimes
        // 弹满了，之后再也不打扰。
        val milestone = LAUNCH_MILESTONES.getOrNull(shown) ?: return false
        if (launchCount < milestone) return false
        if (shown == 0) return true

        val sinceLast = System.currentTimeMillis() - prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        // 负数意味着系统时间被往前调过。这时候放行而不是继续等 —— 否则一次改表能把
        // 弹窗永久卡死在冷却里。
        return sinceLast !in 0..<COOLDOWN_MILLIS
    }

    /** 「以后再说」，或者点弹窗外面 / 返回键关掉。这一档在弹出时就已经记过账。 */
    fun dismiss() {
        isShowing = false
    }

    /** 「不再提示」。 */
    fun optOut() {
        prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
        isShowing = false
    }

    companion object {
        private const val PREFS = "hooker_invite"
        private const val KEY_LAUNCH_COUNT = "launch_count"
        private const val KEY_SHOWN_TIMES = "shown_times"
        private const val KEY_LAST_SHOWN_AT = "last_shown_at"
        private const val KEY_OPTED_OUT = "opted_out"

        /** 第几次启动弹一次。下标即「已经弹过几次」，列表走完就不再弹。 */
        private val LAUNCH_MILESTONES = intArrayOf(1, 5, 20, 50)

        private const val COOLDOWN_MILLIS = 3L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: CommunityInvite? = null

        /**
         * 进程级单例。
         *
         * Activity 重建（旋转、深色模式切换、字体缩放、分屏）会重跑 `setContent`；每次
         * 新建一个实例的话，一次真实使用会被记成好几次启动，弹窗也会当场再弹一遍。
         * 计数和「这次要不要弹」都只在进程第一次取用时算，之后整个进程沿用同一个答案。
         */
        fun get(context: Context): CommunityInvite = instance ?: synchronized(this) {
            instance ?: CommunityInvite(context).also { instance = it }
        }
    }
}
