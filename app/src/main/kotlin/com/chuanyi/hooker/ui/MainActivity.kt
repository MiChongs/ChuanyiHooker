package com.chuanyi.hooker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chuanyi.hooker.data.ModuleSettings
import com.chuanyi.hooker.ui.navigation.HookerNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HookerTheme {
                val context = LocalContext.current
                val settings = remember(context) { ModuleSettings.get(context) }
                // 返回栈、返回键、转场都归 NavDisplay 管，这里不再自己处理。
                HookerNavHost(settings = settings)
            }
        }
    }
}
