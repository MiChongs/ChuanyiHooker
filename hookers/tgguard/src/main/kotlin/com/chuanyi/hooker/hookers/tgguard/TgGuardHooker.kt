package com.chuanyi.hooker.hookers.tgguard

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.chuanyi.hooker.core.ActivationGuard
import com.chuanyi.hooker.core.ActivationToken
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * 模块激活的**产出端**：在 TG 客户端自己的进程里确认那个群在不在，在就签发一枚令牌。
 *
 * 这是唯一一个不改目标任何行为的 hooker —— 它一个 hook 都不装，只读一个文件。
 *
 * ## 为什么非得在 TG 进程里
 *
 * 判据是本机 TG 客户端的 `files/cache4.db`，那是应用私有目录，别的 uid 打不开，
 * 模块自己的进程也不行。要读它就只能在它自己的进程里读 —— 这也是为什么这个模块
 * 必须在 LSPosed 里被勾进 TG 的作用域，界面上的「未激活」提示说的就是这件事。
 *
 * ## 每次启动都真查一遍
 *
 * 不做「令牌还新鲜就跳过」的优化。那个优化省下的是几毫秒（开一个文件、走一趟很浅的
 * B 树），代价却是**退群之后最多要等到令牌过期才停** —— 而「退群立刻停用」正是这套
 * 东西要做到的事。所以每次冷启动都查，查完再决定是续签、撤销，还是什么都不做。
 *
 * ## 三种结果，三种处理
 *
 * ```
 * 找到了            -> 令牌不新鲜就签一枚新的，广播给模块应用
 * 权威地没找到      -> 只有当前这枚令牌是**我**签的，才广播撤销
 * 读不出来          -> 什么都不做
 * ```
 *
 * 中间那条的两个限定词都不能少：
 *
 *  * **权威地**。库打不开、表结构没认出来，都不算数（[NativeHook.ProbeOutcome.UNREADABLE]）。
 *    否则一次文件被占用就能把人锁在外面。
 *  * **我签的**。一台机器上可能同时装着好几个 TG 客户端：在 NekoX 里进了群、同时也
 *    装着官方 Telegram。后者查不到是正常的，它没有资格撤销前者签发的令牌。签发方
 *    签在令牌里（进 MAC，见 [com.chuanyi.hooker.core.ActivationToken]），所以这个
 *    判断改不了也伪造不了。
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

    override val description: String = "读取 TG 客户端的本地会话库，确认模块的使用资格"

    override val targetPackages: Set<String> = TelegramClients.PACKAGES

    /** 不改目标任何行为，所以没有可开关的东西。 */
    override val features: List<HookFeature> = emptyList()

    override val bypassesActivation: Boolean = true

    override fun onHook(scope: HookScope) {
        // 挪去后台线程有两个原因，都不是「怕慢」：
        //  * 这里跑在 EzHookTool 的 target-ready 事务里，也就是目标 Application 起来
        //    之前。发广播要 Context，那时候还没有。
        //  * 读库要开文件、可能还要翻 WAL，卡在应用启动路径上不合适。
        Thread({ runCatching { probeAndReport(scope) } }, "chuanyi-tgguard").apply {
            isDaemon = true
            start()
        }
    }

    private fun probeAndReport(scope: HookScope) {
        val moduleVersion = ActivationGuard.moduleVersionCode()
        val sourceHash = ActivationToken.hashOf(scope.packageName)
        val current = scope.settings.activationToken()

        val dataDir = scope.appInfo.dataDir
        if (dataDir.isNullOrEmpty()) {
            scope.log.w("拿不到 dataDir，无法定位会话库")
            return
        }

        var token: String? = null
        // 「至少有一个库是真读通了、并且里面确实没有」。多账号里只要有一个账号在群里
        // 就算数，所以这个标志只在**没有任何账号命中**的时候才有意义。
        var authoritativeAbsent = false
        var readable = 0

        for (path in TelegramClients.databaseCandidates(dataDir)) {
            val result = NativeHook.activationProbe(path, moduleVersion, sourceHash)
            when (result.outcome) {
                NativeHook.ProbeOutcome.FOUND -> {
                    readable++
                    token = result.token
                }

                NativeHook.ProbeOutcome.ABSENT -> {
                    readable++
                    authoritativeAbsent = true
                }

                // 多账号目录多数根本不存在，这里绝大部分是「文件不在」，不是错误。
                NativeHook.ProbeOutcome.UNREADABLE -> Unit
            }
            if (token != null) break
        }

        if (token != null) {
            // 已经有一枚今天签的就不再广播 —— 探测本身每次都做（那才是「退群立刻停用」
            // 的前提），但没必要每次冷启动都去吵醒模块应用写一次盘。
            if (!current.isNullOrEmpty() &&
                NativeHook.activationVerify(current, moduleVersion, ttlDays = 0)
            ) {
                scope.log.d("群组仍在，当前令牌是今天签的，无需续签")
                return
            }
            scope.log.i("查过 $readable 个会话库，群组在，签发新令牌")
            deliver(scope, token, revoke = false)
            return
        }

        if (!authoritativeAbsent) {
            scope.log.d("$readable 个会话库都没读出可信结论，本次不做任何判断")
            return
        }

        // 到这里是权威的「没有」。只有当前令牌确实是自己签的才撤 —— 别的客户端签的
        // 轮不到这里说话，理由见类文档。
        if (current.isNullOrEmpty()) {
            scope.log.d("确认没有该群组，且当前也没有令牌，无事可做")
            return
        }
        if (!ActivationToken.issuedBy(current, scope.packageName)) {
            scope.log.d("确认本客户端没有该群组，但当前令牌是别的客户端签的，不撤销")
            return
        }
        scope.log.i("确认已不在群组中（查过 $readable 个会话库），撤销自己签发的令牌")
        deliver(scope, current, revoke = true)
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
    private fun deliver(scope: HookScope, token: String, revoke: Boolean) {
        val context = awaitAppContext(scope)
        if (context == null) {
            scope.log.w("等不到 Application，本次结果下次启动再递")
            return
        }
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
        /** 等 Application 的上限。冷启动慢的机器上十几秒也见过，给足。 */
        const val CONTEXT_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
