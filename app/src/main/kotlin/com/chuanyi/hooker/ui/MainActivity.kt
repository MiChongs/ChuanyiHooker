package com.chuanyi.hooker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chuanyi.hooker.data.ActivationJob
import com.chuanyi.hooker.data.LogStore
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.data.UiPreferences
import com.chuanyi.hooker.data.rememberActivationStatus
import com.chuanyi.hooker.ui.navigation.HookerNavHost
import com.chuanyi.hooker.ui.screen.ActivationLockScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 后台稽核。用户改完 LSPosed 作用域之后如果再也不打开这个界面，前台那次稽核
        // 就永远不会发生，只能靠令牌过期兜底 —— 这个作业把那段空窗压到一天。
        ActivationJob.ensureScheduled(this)

        // 把上次的日志从磁盘读回来。放在这里而不是日志页里：进程可能是被
        // LogReceiver 拉起来的（那条路径自己也会调一次），也可能用户先打开界面、
        // 后台才陆续收到广播 —— 两种顺序都要能拼出一份完整的列表。
        LogStore.restore(this)

        setContent {
            val context = LocalContext.current
            val settings = remember(context) { ModuleSettings.get(context) }
            val uiPreferences = remember(context) { UiPreferences.get(context) }

            // 主题读的是 UiPreferences 里的 snapshot state，所以设置页改一下就立刻生效，
            // 不需要重建 Activity。
            HookerTheme(ui = uiPreferences) {
                // 稽核必须挂在分支**之前**，两种状态下都跑。
                //
                // 它负责发现两件只有模块应用这一侧看得见的事：签发方被卸载了、签发方
                // 被移出了框架作用域。早先它挂在 ActivationLockScreen 里，而那一屏只在
                // 未激活时渲染 —— 于是稽核只在已经锁上之后才跑，激活状态下取消作用域
                // 永远不会被发现。表现就是「作用域取消了，模块照样能用」。
                val activation = rememberActivationStatus(settings)

                // 没通过群组校验时**整个界面都不给**，换成一屏拦截页。
                //
                // 不是把开关灰掉、也不是加一张提示卡：未激活状态下被注入的进程里一个
                // hook 都不会装（见 HookerRuntime.install），那一屏开关全都是假的。
                // 让人对着一堆能拨但不生效的开关，比直接说清楚要糟糕得多。
                //
                // isActivated 每次读都重新走一遍原生校验，而它读了 settings.revision，
                // 所以令牌被广播写进来（或被稽核撤销）时这里会自动换屏，不用重启应用。
                if (settings.isActivated) {
                    // 返回栈、返回键、转场都归 NavDisplay 管，这里不再自己处理。
                    HookerNavHost(settings = settings)
                } else {
                    ActivationLockScreen(settings = settings, status = activation)
                }
            }
        }
    }
}
