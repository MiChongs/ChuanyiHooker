package com.chuanyi.hooker.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * 赞赏弹窗什么时候弹。
 *
 * 判据是**启动次数**：首次一次，之后随机。
 *
 * | 弹第几次 | 触发条件 |
 * |---|---|
 * | 1 | 第一次能开口的启动（见下方「让位」） |
 * | 2–[MAX_SHOWN_TIMES] | 满足间隔后，每次启动按 1/[RANDOM_ONE_IN] 抽中 |
 *
 * 首次是确定的，之后是随机的 —— 固定在「第 N 次启动」上会让常用的人摸出规律，而随机
 * 分布在满足间隔的那些启动里，观感上更像偶尔提一句，不像定时任务。
 *
 * ## 让位给「加入我们」
 *
 * 全新安装的第 1 次启动是 [CommunityInvite] 的欢迎弹窗。两个弹窗各自开窗口，同时弹会
 * 叠在一起。所以这里接受一个 deferred 开关：本次启动已经有别的弹窗要弹，就整档顺延到
 * 下次启动 —— 判据是「已经弹过几次」而不是「启动次数正好等于某个数」，顺延不作废。
 *
 * 顺延的效果是：全新安装第 1 次弹欢迎，第 2 次弹赞赏。第一次启动就要钱本来也不合适 ——
 * 那时用户还没用上任何功能。
 *
 * ## 三道防打扰的闸
 *
 *  * **启动间隔 [MIN_LAUNCH_GAP] 次。** 随机抽签只在攒够启动次数之后才开始，否则连开
 *    两次就可能连弹两次。
 *  * **冷却 [COOLDOWN_DAYS] 天。** 一天里来回开几十次，光靠启动间隔也能烧掉好几档。
 *  * **总量 [MAX_SHOWN_TIMES] 次。** 弹满就再也不弹，不依赖用户主动点「不再提示」。
 *
 * 点「不再提示」立即永久关闭。关于页的「赞赏」一节始终在，这个弹窗只负责让人知道有这么
 * 回事，不负责当唯一入口。
 *
 * 状态存在**本地** SharedPreferences，不走 [ModuleSettings] 那份远程配置：这是模块自己
 * 界面的事，被 hook 的应用不该看见。计数也不跟 [CommunityInvite] 共用一份 —— 两个类各自
 * 记账，互不影响，改一个的策略不会动到另一个已经在用户机器上跑着的进度。
 */
@Stable
class DonationPrompt private constructor(context: Context, deferred: Boolean) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本次是第几次启动（含这一次）。 */
    val launchCount: Int = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1

    /** 弹窗是否正在显示。 */
    var isShowing by mutableStateOf(false)
        private set

    /** 到目前为止弹过几次。 */
    private val shownTimes: Int get() = prefs.getInt(KEY_SHOWN_TIMES, 0)

    /** 首次那一次的措辞跟后面几次不一样。 */
    val isFirstTime: Boolean = shownTimes == 0

    init {
        prefs.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()

        if (!deferred && shouldShow()) {
            // 决定要弹的当下就记账，而不是等用户关掉时再记：进程在弹窗停留期间被杀
            // （Xposed 模块的宿主经常被系统清理），这一档不该重来一遍。
            prefs.edit()
                .putInt(KEY_SHOWN_TIMES, shownTimes + 1)
                .putInt(KEY_LAST_SHOWN_LAUNCH, launchCount)
                .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
                .apply()
            isShowing = true
        }
    }

    private fun shouldShow(): Boolean {
        if (prefs.getBoolean(KEY_OPTED_OUT, false)) return false

        val shown = shownTimes
        if (shown >= MAX_SHOWN_TIMES) return false
        // 首次不抽签也不看间隔：那时候还没有「上一次」。
        if (shown == 0) return true

        if (launchCount - prefs.getInt(KEY_LAST_SHOWN_LAUNCH, 0) < MIN_LAUNCH_GAP) return false

        val sinceLast = System.currentTimeMillis() - prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        // 负数意味着系统时间被往前调过。这时候放行而不是继续等 —— 否则一次改表能把弹窗
        // 永久卡死在冷却里。
        if (sinceLast in 0..<COOLDOWN_MILLIS) return false

        return Random.nextInt(RANDOM_ONE_IN) == 0
    }

    /** 「知道了」，或者点弹窗外面 / 返回键关掉。这一档在弹出时就已经记过账。 */
    fun dismiss() {
        isShowing = false
    }

    /** 「不再提示」。 */
    fun optOut() {
        prefs.edit().putBoolean(KEY_OPTED_OUT, true).apply()
        isShowing = false
    }

    companion object {
        private const val PREFS = "hooker_donate"
        private const val KEY_LAUNCH_COUNT = "launch_count"
        private const val KEY_SHOWN_TIMES = "shown_times"
        private const val KEY_LAST_SHOWN_LAUNCH = "last_shown_launch"
        private const val KEY_LAST_SHOWN_AT = "last_shown_at"
        private const val KEY_OPTED_OUT = "opted_out"

        /** 总共最多弹几次。 */
        private const val MAX_SHOWN_TIMES = 4

        /** 首次之后，两次之间至少隔这么多次启动。 */
        private const val MIN_LAUNCH_GAP = 15

        /** 首次之后，攒够启动次数的每一次启动按 1/N 的概率抽中。期望再等 N 次启动。 */
        private const val RANDOM_ONE_IN = 6

        private const val COOLDOWN_DAYS = 7L
        private const val COOLDOWN_MILLIS = COOLDOWN_DAYS * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: DonationPrompt? = null

        /**
         * 进程级单例。
         *
         * Activity 重建（旋转、深色模式切换、字体缩放、分屏）会重跑 `setContent`；每次
         * 新建一个实例的话，一次真实使用会被记成好几次启动，抽签也会重来一遍 —— 那等于
         * 把「随机」变成「转屏几次就能摇出来」。计数、抽签结果都只在进程第一次取用时算。
         *
         * @param deferred 本次启动已经有别的弹窗要显示，整档顺延到下次启动。只有进程内
         *   第一次调用时有效，之后同一个进程沿用同一个答案。
         */
        fun get(context: Context, deferred: Boolean = false): DonationPrompt =
            instance ?: synchronized(this) {
                instance ?: DonationPrompt(context, deferred).also { instance = it }
            }
    }
}
