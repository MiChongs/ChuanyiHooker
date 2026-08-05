package com.chuanyi.hooker.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root shell，libsu 支撑。
 *
 * 存在的唯一理由：**没有任何非 root 途径能停掉别的应用的进程**。而「改完开关要让
 * 目标重启才生效」是这个模块最高频的操作，让用户自己去设置里强行停止太别扭。
 *
 * libxposed 的 `hotReloadModule` 解决的是另一个问题（换新一代模块代码，不重启进程），
 * 官方明确说了不要拿它传播配置变更 —— 两者不能互相替代，见
 * [FrameworkService.hotReload]。
 *
 * ## 不主动申请 root
 *
 * 初始化时只读 `Shell.isAppGrantedRoot()`，这个调用不会建 shell、不会弹授权框。
 * 真正的 shell 推迟到用户第一次点「重启目标应用」时才建（[ensureAccess]）——
 * 一个 Xposed 模块管理界面没道理一打开就弹 root 授权。
 */
@Stable
object RootShell {

    /** 命令执行结果。[message] 只在失败时有值。 */
    @Immutable
    data class Outcome(val isSuccess: Boolean, val message: String?)

    enum class Access {
        /** 还没问过。可能有，也可能没有。 */
        Unknown,

        Granted,

        /** 问过了，没有。 */
        Denied,
    }

    /** 单条命令的上限。force-stop 很快，但授权框要等用户点。 */
    private const val TIMEOUT_SECONDS = 20L

    var access by mutableStateOf(Access.Unknown)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    init {
        // 必须早于任何 getShell()；本对象是全进程唯一的 shell 使用者，所以放这儿。
        runCatching {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setTimeout(TIMEOUT_SECONDS),
            )
        }
        // 只读已缓存的授权状态：true/false 是确定答案，null 表示没问过。
        access = when (Shell.isAppGrantedRoot()) {
            true -> Access.Granted
            false -> Access.Denied
            null -> Access.Unknown
        }
    }

    /**
     * 确保拿到 root shell，必要时弹授权框。**阻塞**，只能在 IO 线程上调。
     */
    suspend fun ensureAccess(): Boolean = withContext(Dispatchers.IO) {
        val shell = runCatching { Shell.getShell() }.getOrElse {
            lastError = it.message ?: it.javaClass.simpleName
            access = Access.Denied
            return@withContext false
        }
        // 授权被拒时 libsu 会回落成普通 shell，而不是抛异常 —— 必须查 isRoot。
        access = if (shell.isRoot) Access.Granted else Access.Denied
        if (!shell.isRoot) lastError = "root 授权被拒绝"
        shell.isRoot
    }

    /** 目标是否有进程在跑。用于重启后确认，以及界面上的状态显示。 */
    suspend fun isRunning(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (access != Access.Granted) return@withContext false
        runCatching { Shell.cmd("pidof ${packageName.shellArg()}").exec() }
            .getOrNull()
            ?.out
            ?.any { it.isNotBlank() } == true
    }

    /**
     * 停掉目标的全部进程。模块设置的改动会在目标下次启动时生效。
     */
    suspend fun forceStop(packageName: String): Outcome =
        exec("am force-stop ${packageName.shellArg()}")

    /**
     * 停掉再拉起来。
     *
     * 拉起用 `cmd package resolve-activity --brief` 解出的启动 Activity；解不出来
     * （没有 launcher 入口，或被系统隐藏）就退回 `monkey`。两条都在同一个 shell
     * 会话里跑完，不必来回切进程。
     */
    suspend fun restart(packageName: String): Outcome {
        val stopped = forceStop(packageName)
        if (!stopped.isSuccess) return stopped
        return launch(packageName)
    }

    /** 拉起目标的启动 Activity。 */
    suspend fun launch(packageName: String): Outcome {
        val pkg = packageName.shellArg()
        return exec(
            "component=\$(cmd package resolve-activity --brief $pkg | tail -n 1)",
            "case \"\$component\" in " +
                "*/*) am start -n \"\$component\" ;; " +
                "*) monkey -p $pkg -c android.intent.category.LAUNCHER 1 ;; " +
                "esac",
        )
    }

    private suspend fun exec(vararg commands: String): Outcome = withContext(Dispatchers.IO) {
        if (!ensureAccess()) {
            return@withContext Outcome(false, lastError ?: "没有 root 权限")
        }
        val result = runCatching { Shell.cmd(*commands).exec() }.getOrElse {
            val message = it.message ?: it.javaClass.simpleName
            lastError = message
            return@withContext Outcome(false, message)
        }
        if (result.isSuccess) {
            lastError = null
            Outcome(true, null)
        } else {
            // am / monkey 出错时信息可能走 stdout 也可能走 stderr，两边都捞。
            val message = (result.err + result.out).firstOrNull { it.isNotBlank() }
                ?: "退出码 ${result.code}"
            lastError = message
            Outcome(false, message)
        }
    }

    /**
     * 包名按 Android 的规则只能是字母数字下划线和点，正常情况下不需要转义。
     * 这里仍然过一道：包名是从 PackageManager / hooker 声明里来的，但万一将来
     * 有人把用户输入接进来，不该在 root shell 里炸开。
     */
    private fun String.shellArg(): String =
        if (all { it.isLetterOrDigit() || it == '.' || it == '_' }) this
        else "'" + replace("'", "'\\''") + "'"
}
