package com.chuanyi.hooker.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.chuanyi.hooker.BuildConfig
import com.chuanyi.hooker.core.ActivationGuard
import com.chuanyi.hooker.core.ActivationToken
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * 收下 TG 客户端进程算出来的激活令牌，落进框架配置。
 *
 * ## 为什么需要这一跳
 *
 * 判定「有没有那个群」只能在 TG 客户端自己的进程里做（那是它的私有目录），可那个
 * 进程拿到的框架配置是**只读**的 —— `XposedInterface.getRemotePreferences` 给的
 * 视图 `edit()` 直接抛。能写的句柄只有模块应用手里这一份（`XposedService`）。
 * 于是探测结果只能广播过来，由这里落盘。
 *
 * ## 导出它是安全的
 *
 * 这个接收器对所有应用开放，任何人都能往它发一串东西。这不构成问题：令牌带 MAC，
 * 密钥只存在于壳内代码解密后的匿名内存里（见 `:native` 的 `cpp/payload/`），
 * 伪造不出来。收到假的只会在下面这行 [com.chuanyi.hooker.nativehook.NativeHook.activationVerify]
 * 上被挡掉，连写都不会写。
 *
 * **先验后写**这个顺序是要紧的：写进去的东西会被每一个被注入的进程读走，
 * 让未经校验的值落一次盘，等于把闸门开在了这里。
 *
 * ## goAsync
 *
 * 框架的 binder 是异步送到模块应用的（见 [ModuleSettings]）。广播来得早的时候
 * （比如模块应用刚被这条广播拉起来）它还没到，直接写会落进本地兜底存储，要等用户
 * 下次打开模块界面才迁移过去 —— 而用户根本没有理由知道自己得去打开一次。所以这里
 * 用 `goAsync` 等一小会儿，等到了再写。
 */
class ActivationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivationGuard.Broadcast.ACTION) return

        val token = intent.getStringExtra(ActivationGuard.Broadcast.EXTRA_TOKEN)
            ?.takeIf { it.isNotEmpty() } ?: return
        val source = intent.getStringExtra(ActivationGuard.Broadcast.EXTRA_SOURCE).orEmpty()
        val revoke = intent.getBooleanExtra(ActivationGuard.Broadcast.EXTRA_REVOKE, false)

        // 先验再动。验不过的连日志都不用留：这条路上唯一会发生的「验不过」就是有人
        // 手动构造了一条广播，为它记账没有意义。
        //
        // 撤销请求也要验：不然一条随手构造的广播就能把模块关掉。
        if (!NativeHook.activationVerify(token, BuildConfig.VERSION_CODE)) return

        // 撤销还要再过两道，两道管的是不同的事：
        //  * 签发方对得上 —— 令牌是不是这个客户端发的。签发方签在 MAC 里，改不了。
        //  * 令牌对得上   —— 发送方读得到框架配置，也就是它真的是个被注入的进程。
        //    这一道挡的是「知道令牌格式的第三方应用随便发一条撤销」。
        if (revoke && !ActivationToken.issuedBy(token, source)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                val settings = ModuleSettings.get(appContext)
                awaitBinder(settings)
                if (revoke) {
                    if (settings.activationToken == token) settings.revokeActivation()
                } else {
                    settings.acceptActivation(token, source)
                }
            } finally {
                pending.finish()
            }
        }, "chuanyi-activation").start()
    }

    /**
     * 等框架把可写句柄交过来。
     *
     * 等不到也照写不误：[ModuleSettings.write] 会落进本地兜底存储，binder 到达时
     * 由它自己迁移。等待只是为了把「常见情况」做成一次就位。
     */
    private fun awaitBinder(settings: ModuleSettings) {
        val deadline = SystemClock.uptimeMillis() + BINDER_TIMEOUT_MILLIS
        while (!settings.isSynced && SystemClock.uptimeMillis() < deadline) {
            runCatching { Thread.sleep(POLL_INTERVAL_MILLIS) }.onFailure { return }
        }
    }

    private companion object {
        /**
         * 广播接收器本身的上限是 10 秒（前台）/ 60 秒（后台），超时会被系统判成 ANR。
         * 留足余量。
         */
        const val BINDER_TIMEOUT_MILLIS = 6_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
