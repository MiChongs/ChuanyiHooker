package com.chuanyi.hooker.hookers.tgguard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.ActivationGuard
import com.chuanyi.hooker.core.ActivationToken
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 模块激活的**产出端**：在 TG 客户端自己的进程里确认当前账号在不在那个群，在就签发
 * 一枚令牌，不在就撤销。
 *
 * 这是唯一一个不改目标任何行为的 hooker —— 它一个 hook 都不装，只读两个文件。
 *
 * ## 为什么非得在 TG 进程里
 *
 * 判据是 TG 客户端的 `files/cache4.db`，那是应用私有目录，别的 uid 打不开，模块自己
 * 的进程也不行。要读它就只能在它自己的进程里读 —— 这也是为什么这个模块必须在
 * LSPosed 里被勾进 TG 的作用域。
 *
 * ## 只看**当前账号**
 *
 * 多账号是一定要处理的，而且不能「任意一个账号在群里就算过」：那样切到一个不在群的
 * 小号，老账号的库还躺在 `files/cache4.db` 里，照样通过 —— 等于多账号成了绕过手段。
 *
 * 所以每一轮都先读一次 Telegram 自己的 `mainconfig/selectedAccount`，只查那一个槽位
 * 的库。切账号 → 下一轮就查到新账号头上；新账号不在群 → 权威的「没有」→ 撤销。
 *
 * ## 循环而不是只跑一次
 *
 * 早先只在进程启动时探一次，有两个洞：
 *
 *  * **切账号不生效。**TG 是常驻应用，进程可以活好几天，切账号不会重启它。
 *  * **常驻进程不续签。**令牌有有效期，只在冷启动续签的话，一个从不被杀的 TG 进程
 *    反而会让模块在几天后自己锁上。
 *
 * 现在是一个后台守护线程，每 [PROBE_INTERVAL_MILLIS] 转一圈。线程只是 `sleep`，
 * 不持 wakelock、不唤醒 CPU，代价可以忽略。
 *
 * ## 三种结果，三种处理
 *
 * ```
 * 找到了        -> 距上次签发超过 [RENEW_INTERVAL_MILLIS] 就签一枚新的，广播出去
 * 权威地没找到  -> 只有当前那枚令牌是**我**签的，才广播撤销
 * 读不出来      -> 什么都不做
 * ```
 *
 * 中间那条的两个限定词都不能少：
 *
 *  * **权威地**。库打不开、表结构没认出来，都不算数（[NativeHook.ProbeOutcome.UNREADABLE]）。
 *    否则一次文件被占用就能把人锁在外面。
 *  * **我签的**。一台机器上可能同时装着好几个 TG 客户端：在 NekoX 里进了群、同时也
 *    装着官方 Telegram。后者查不到是正常的，它没有资格撤销前者签发的令牌。签发方
 *    签在令牌里（进 MAC，见 [ActivationToken]），所以这个判断改不了也伪造不了。
 *
 * ## 它不受开关约束
 *
 * [bypassesActivation] 为 true：激活闸门不挡它（挡了就永远拿不到令牌，闸门自己
 * 把自己锁死），单个应用的开关也不管它（关掉的后果是整个模块停摆，和开关的描述
 * 完全不相称）。总开关仍然管得住 —— 那是用户明确要求「什么都别做」。
 */
class TgGuardHooker : AppHooker {

    override val id: String = "tgguard"

    override val displayName: String = "群组校验"

    override val description: String = "读取 TG 客户端当前账号的本地会话库，确认模块的使用资格"

    override val targetPackages: Set<String> = TelegramClients.PACKAGES

    /** 不改目标任何行为，所以没有可开关的东西。 */
    override val features: List<HookFeature> = emptyList()

    override val bypassesActivation: Boolean = true

    /** 唤醒机制。切账号时由监听器发信号，探测线程随即醒来。 */
    private val lock = ReentrantLock()
    private val wakeUp = lock.newCondition()

    /** 有没有一次「还没被消费掉」的唤醒，见 [awaitNextRound]。 */
    private var pending = false

    /**
     * 账号变动监听器。**必须留强引用** —— SharedPreferences 用 WeakHashMap 存它们，
     * 只留局部变量的话下一次 GC 就没了，表现是「切账号有时生效有时不生效」。
     */
    @Suppress("unused")
    private var accountWatcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onHook(scope: HookScope) {
        // 挪去后台线程有两个原因，都不是「怕慢」：
        //  * 这里跑在 EzHookTool 的 target-ready 事务里，也就是目标 Application 起来
        //    之前。读它的配置、发广播都要 Context，那时候还没有。
        //  * 这是个长期循环，本来也不能待在启动路径上。
        Thread({ runCatching { loop(scope) } }, "chuanyi-tgguard").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(scope: HookScope) {
        val context = awaitAppContext(scope)
        if (context == null) {
            scope.log.w("等不到 Application，本进程放弃校验")
            return
        }

        // 切账号立刻生效靠这个，不靠轮询。监听器必须存成字段：SharedPreferences 用
        // WeakHashMap 存监听器，只留局部变量的话下一次 GC 就被回收，而且不会有任何提示。
        accountWatcher = TelegramClients.watchAccountChanges(context) {
            scope.log.d("检测到账号切换，立即重新校验")
            awaken()
        }
        if (accountWatcher == null) {
            scope.log.w("账号切换监听没装上，切账号要等下一轮周期才生效")
        }

        // 本进程上一次成功广播出去的签发时刻。**不读配置来判断要不要续签** ——
        // 被注入进程手里那份远端配置未必实时跟得上模块应用的写入，拿它当判据会出现
        // 「以为已经续过了，其实没有」。自己记账不会有这个问题。
        var lastIssuedAt = 0L

        while (true) {
            runCatching { lastIssuedAt = probeOnce(scope, context, lastIssuedAt) }
                .onFailure { scope.log.w("本轮校验出错，下一轮重来", it) }
            if (!awaitNextRound()) return
        }
    }

    /**
     * 等下一轮：要么被账号变动叫醒，要么等到周期到点。
     *
     * `pending` 标志不能省。监听器可能正好在**探测进行中**触发，那时候还没有人在
     * `wait` 上，`notifyAll` 打在空处 —— 少了这个标志，那次切换就要多等整整一个周期
     * 才被发现，而这恰恰是最常见的时序（用户切完账号，界面立刻就变了）。
     *
     * @return false 表示线程被中断，该退出了
     */
    private fun awaitNextRound(): Boolean {
        lock.withLock {
            if (!pending) {
                try {
                    wakeUp.await(PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            pending = false
        }
        return true
    }

    private fun awaken() = lock.withLock {
        pending = true
        wakeUp.signalAll()
    }

    /** @return 更新后的「上次签发时刻」 */
    private fun probeOnce(scope: HookScope, context: Context, lastIssuedAt: Long): Long {
        val moduleVersion = ActivationGuard.moduleVersionCode()
        val sourceHash = ActivationToken.hashOf(scope.packageName)

        val dataDir = scope.appInfo.dataDir
        if (dataDir.isNullOrEmpty()) {
            scope.log.w("拿不到 dataDir，无法定位会话库")
            return lastIssuedAt
        }

        // 每一轮都重解析一次：用户可能在进程活着的时候切了账号、登录了、或者退了。
        val loggedIn = TelegramClients.loggedInAccounts(context)
        val account = TelegramClients.currentAccount(context, scope.classLoader)

        val (subject, result) = when {
            account != null -> {
                val path = TelegramClients.databaseOf(dataDir, account)
                "账号 $account" to NativeHook.activationProbe(path, moduleVersion, sourceHash)
            }

            // 一个账号都没登录 —— 全退了。这本身就是权威的「这个客户端不在群里」，
            // 不能因为「确定不了当前账号」就跳过，否则退出登录之后令牌会一直留着。
            // 仍然走一遍库：退登录时上游会把表清空（表还在、行没了），于是探测给出
            // ABSENT；而如果连表都没有（从没登录过的空壳），得到 UNREADABLE，
            // 那种情况下本来也不会有自己签发的令牌。
            loggedIn.isEmpty() ->
                "无登录账号" to probeEverySlot(dataDir, moduleVersion, sourceHash)

            // 有账号登录着，却解析不出是哪个。**什么都不做**，绝不退回账号 0 ——
            // 多账号用户的 0 号槽位常常是个 4 KiB 的空壳库，探它只会得出「读不出
            // 结论」，然后把人卡在未激活上，而界面还会给出完全不相干的原因。
            else -> {
                scope.log.w("有账号登录着（$loggedIn）但解析不出当前是哪个，本轮跳过")
                return lastIssuedAt
            }
        }

        scope.log.d("$subject 探测结果 ${result.outcome}")

        when (result.outcome) {
            NativeHook.ProbeOutcome.FOUND -> {
                val token = result.token ?: return lastIssuedAt
                val now = SystemClock.elapsedRealtime()
                if (lastIssuedAt != 0L && now - lastIssuedAt < RENEW_INTERVAL_MILLIS) {
                    scope.log.d("$subject 仍在群组内，令牌还在续签周期内")
                    return lastIssuedAt
                }
                scope.log.i("$subject 在群组内，签发新令牌")
                deliver(scope, context, token, revoke = false)
                return now
            }

            NativeHook.ProbeOutcome.ABSENT -> {
                // 权威的「没有」。只有当前令牌确实是自己签的才撤 —— 别的客户端签的
                // 轮不到这里说话，理由见类文档。
                val current = scope.settings.activationToken()
                when {
                    current.isNullOrEmpty() ->
                        scope.log.d("$subject 不在群组内，且当前没有令牌，无事可做")

                    !ActivationToken.issuedBy(current, scope.packageName) ->
                        scope.log.d("$subject 不在群组内，但当前令牌是别的客户端签的，不撤销")

                    else -> {
                        scope.log.i("$subject 已不在群组内，撤销自己签发的令牌")
                        deliver(scope, context, current, revoke = true)
                    }
                }
                // 撤销之后要允许立刻重新签发（比如用户切回在群的账号），所以把记账清零。
                return 0L
            }

            NativeHook.ProbeOutcome.UNREADABLE -> {
                scope.log.d("$subject 的会话库读不出可信结论，本轮不做判断")
                return lastIssuedAt
            }
        }
    }

    /**
     * 扫一遍全部槽位，取「最强的那个结论」。只在**一个账号都没登录**时用。
     *
     * 优先级 FOUND > ABSENT > UNREADABLE：任意一个槽位还留着那个群就不撤（多半是
     * 刚退登录、库还没清干净），只有确实读通了且都没有，才给出权威的否定。
     */
    private fun probeEverySlot(dataDir: String, moduleVersion: Int, sourceHash: Int): NativeHook.ProbeResult {
        var absent = false
        for (slot in 0 until TelegramClients.MAX_ACCOUNTS) {
            val result = NativeHook.activationProbe(
                TelegramClients.databaseOf(dataDir, slot),
                moduleVersion,
                sourceHash,
            )
            when (result.outcome) {
                NativeHook.ProbeOutcome.FOUND -> return result
                NativeHook.ProbeOutcome.ABSENT -> absent = true
                NativeHook.ProbeOutcome.UNREADABLE -> Unit
            }
        }
        return NativeHook.ProbeResult(
            if (absent) NativeHook.ProbeOutcome.ABSENT else NativeHook.ProbeOutcome.UNREADABLE,
            null,
        )
    }

    /**
     * 把结果递给模块应用 —— 签发一枚新的，或者撤销当前这一枚。
     *
     * 这一跳绕不过去：被注入的进程拿到的框架配置是**只读**的（`edit()` 直接抛），
     * 所以算出来的东西没法自己落盘。模块应用那边有 `XposedService` 给的可写句柄。
     *
     * 撤销时带的是**当前那枚令牌**，它同时是「我读得到框架配置」的证明 —— 接收方
     * 拿它和自己存着的比对，对不上就不认。否则任意一个应用都能发一条广播把别人的
     * 模块关掉。
     */
    private fun deliver(scope: HookScope, context: Context, token: String, revoke: Boolean) {
        val modulePackage = runCatching { scope.xposed.moduleApplicationInfo.packageName }
            .getOrNull()
        if (modulePackage.isNullOrEmpty()) {
            scope.log.w("拿不到模块包名，结果递不出去")
            return
        }

        val intent = Intent(ActivationGuard.Broadcast.ACTION)
            .setClassName(modulePackage, ActivationGuard.Broadcast.RECEIVER)
            .putExtra(ActivationGuard.Broadcast.EXTRA_TOKEN, token)
            .putExtra(ActivationGuard.Broadcast.EXTRA_SOURCE, scope.packageName)
            .putExtra(ActivationGuard.Broadcast.EXTRA_REVOKE, revoke)
            // 模块应用可能从装上就没被打开过，或者被用户划掉过。不带这个标志，
            // 广播会被系统按「已停止的包」直接丢掉，而且不会有任何提示。
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

        runCatching { context.sendBroadcast(intent) }
            .onSuccess { scope.log.i(if (revoke) "撤销请求已发出" else "令牌已递给 $modulePackage") }
            .onFailure { scope.log.w("递送失败", it) }
    }

    /**
     * 等目标的 Application 出现。
     *
     * 轮询而不是 hook `Application.onCreate`：那要认目标的类，而这个 hooker 覆盖十几个
     * 分支，正是靠「不认任何目标类」才做到加一个分支只改一行包名。等待期间这个线程是
     * 后台的，谁也不挡。
     */
    private fun awaitAppContext(scope: HookScope): Context? {
        val deadline = SystemClock.uptimeMillis() + CONTEXT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            scope.appContextOrNull()?.let { return it }
            runCatching { Thread.sleep(POLL_INTERVAL_MILLIS) }.onFailure { return null }
        }
        return scope.appContextOrNull()
    }

    private companion object {
        /**
         * 兜底周期。**切账号不靠它** —— 那条路是事件驱动的（[loop] 里的监听器），
         * 用户切完当场就重新校验。
         *
         * 这里兜的是监听器没能装上、或者是「在别处退了群、TG 进程一直没被重启」
         * 这类没有本地事件可依赖的情况。半小时足够快，而线程平时只是挂在
         * `wait` 上，不唤醒 CPU、不持 wakelock。
         */
        const val PROBE_INTERVAL_MILLIS = 30L * 60 * 1000

        /** 续签间隔。远小于令牌有效期，留足「连着几轮没广播成功」的余量。 */
        const val RENEW_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        /** 等 Application 的上限。冷启动慢的机器上十几秒也见过，给足。 */
        const val CONTEXT_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
