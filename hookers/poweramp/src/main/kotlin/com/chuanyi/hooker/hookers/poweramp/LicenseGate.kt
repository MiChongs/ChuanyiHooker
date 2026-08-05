package com.chuanyi.hooker.hookers.poweramp

import android.app.Activity
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook

/**
 * 到期对话框。
 *
 * ## 它不是从 Java 启动的
 *
 * `ExpiredActivity` 写在 manifest 里，Java 侧却**没有任何一处启动它** ——
 * 全包 grep 只找得到它自己和一个按钮回调。启动它的是原生授权引擎：应用把
 * `Context` 交给了 `Sync`（处理 intent 那条路），原生侧判定过期时自己拉起。
 *
 * 所以改写授权结果按不住它：结果 Bundle 是原生侧**已经做完判断之后**才发出来的，
 * 那时对话框可能已经在路上。这一项是补上那个缺口。
 *
 * ## 为什么是 after + finish
 *
 * `onCreate` 不能拦掉 —— `Activity.onCreate` 必须跑完，跳过它下一步就崩。
 * 走 after 再 `finish()`：这条路应用自己也在用（它在设置页已打开时就是这么做的），
 * 所以不是一条没走过的分支。
 *
 * 「解锁完整版」开着的时候，对话框其实也活不长 —— 它订阅了版本信息变化，
 * 一旦发现不在试用状态就自己 `finish()`。这里只是不让它闪出来。
 */
internal object LicenseGate {

    fun HookScope.installExpiryGuard() {
        val activity = classOrNull(Poweramp.EXPIRED_ACTIVITY)
        if (activity == null) {
            log.w("找不到 ${Poweramp.EXPIRED_ACTIVITY}，到期对话框不受影响")
            return
        }
        val onCreate = activity.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 1
        }
        if (onCreate == null) {
            log.w("${Poweramp.EXPIRED_ACTIVITY} 上没有 onCreate，到期对话框不受影响")
            return
        }

        onCreate.createAfterHook("poweramp.expiry.dismiss") { param ->
            val instance = param.thisObjectOrNull as? Activity ?: return@createAfterHook
            if (instance.isFinishing) return@createAfterHook
            instance.finish()
            log.i("已关闭原生侧拉起的到期对话框")
        }
        log.d("到期对话框已接管")
    }
}
