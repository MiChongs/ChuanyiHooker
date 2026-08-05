package com.chuanyi.hooker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.data.UiPreferences
import com.chuanyi.hooker.ui.navigation.HookerNavHost
import com.chuanyi.hooker.ui.screen.ActivationLockScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val settings = remember(context) { ModuleSettings.get(context) }
            val uiPreferences = remember(context) { UiPreferences.get(context) }

            // 主题读的是 UiPreferences 里的 snapshot state，所以设置页改一下就立刻生效，
            // 不需要重建 Activity。
            HookerTheme(ui = uiPreferences) {
                // 没通过群组校验时**整个界面都不给**，换成一屏拦截页。
                //
                // 不是把开关灰掉、也不是加一张提示卡：未激活状态下被注入的进程里一个
                // hook 都不会装（见 HookerRuntime.install），那一屏开关全都是假的。
                // 让人对着一堆能拨但不生效的开关，比直接说清楚要糟糕得多。
                //
                // isActivated 每次读都重新走一遍原生校验，而它读了 settings.revision，
                // 所以令牌被广播写进来（或被撤销）时这里会自动换屏，不用重启应用。
                if (settings.isActivated) {
                    // 返回栈、返回键、转场都归 NavDisplay 管，这里不再自己处理。
                    HookerNavHost(settings = settings)
                } else {
                    ActivationLockScreen(settings = settings)
                }
            }
        }
    }
}
