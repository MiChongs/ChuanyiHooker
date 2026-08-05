package com.chuanyi.hooker.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /**
     * **建 shell** 的上限，不是单条命令的上限（libsu 的命令没有超时）。授权框要等
     * 用户点，所以不能压得太短。20 秒也正好是 libsu 自己的默认值。
     */
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
     *
     * 每次调用都会重新问一遍：拿到 root 就一直复用同一个 shell，没拿到就把 libsu
     * 缓存的那个丢掉，下次重来（原因见函数体内）。
     */
    suspend fun ensureAccess(): Boolean = withContext(Dispatchers.IO) {
        val shell = runCatching { Shell.getShell() }.getOrElse {
            lastError = it.message ?: it.javaClass.simpleName
            access = Access.Denied
            return@withContext false
        }
        if (shell.isRoot) {
            access = Access.Granted
            lastError = null
            return@withContext true
        }
        // 授权被拒（或授权框超时）时 libsu 不抛异常，而是回落成普通 sh，并把它存成
        // **主 shell**。不主动关掉的话，之后每次 getShell() 都返回这同一个非 root
        // 实例 —— 用户跑去 Magisk 里补授权也没用，只能杀进程重开。close() 把它的
        // status 打成 UNKNOWN，libsu 下次就会重新建、重新弹框。
        runCatching { shell.close() }
        access = Access.Denied
        lastError = "root 授权被拒绝"
        false
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
     */
    suspend fun restart(context: Context, packageName: String): Outcome {
        val stopped = forceStop(packageName)
        if (!stopped.isSuccess) return stopped
        return launch(context, packageName)
    }

    /**
     * 拉起目标的启动 Activity。
     *
     * 启动组件在**本进程**用 PackageManager 解，不在 shell 里解。早先那版把这事整个
     * 丢给了 shell：`cmd package resolve-activity --brief <包名> | tail -n 1` 取一行，
     * 再用 `case` 判断它长得像不像「包名/类名」，像就 `am start -n`，不像就退 `monkey`。
     * 这套在一部分机器上必然是坏的，两个互相独立的原因：
     *
     *  * **解出来的根本不是启动 Activity。**只给包名、不给 action 的 intent，在 PMS
     *    眼里是「这个包的**每一个** activity 都匹配」—— `IntentFilter` 对 null action
     *    直接跳过 action 检查。接着 `resolveIntent` 要从这堆同分候选里挑一个：挑得出
     *    就是**清单里排在前面**的那个（未必是启动页），彻底同分则返回
     *    `android/…ResolverActivity`。挑中哪个由这台机器上的 PMS 枚举顺序和这个版本
     *    的清单决定，所以它在某些设备/某些应用上一直是对的，换一台就一直是错的。
     *  * **判据只是「有没有斜杠」。**`case` 的模式就这一个条件，而上面两种错误结果
     *    （`ResolverActivity`、以及部分 ROM 往 stdout 里掺的带路径日志行）统统带斜杠，
     *    照样走进 `am start -n` 分支，报的错还跟包名毫无关系。说到底这是在拿一段**给人
     *    看的调试输出**当接口用：`resolve-activity` 有 `ri.dump()` 多行、`--brief` 一行、
     *    `No activity found` 三种形态，没有任何稳定性承诺。
     *
     * PackageManager 走的是同一套解析（[PackageManager.getLaunchIntentForPackage]
     * 先 INFO 后 LAUNCHER），但拿到的是结构化结果，不用去猜谁家 shell 输出长什么样；
     * 目标包都写在各 hooker 的 `<queries>` 里，Android 11+ 的可见性也是通的。
     *
     * 解不出来才退回 `monkey`：它跑在特权进程里，不受本应用的包可见性影响。
     */
    suspend fun launch(context: Context, packageName: String): Outcome {
        val pm = context.applicationContext.packageManager
        val intent = withContext(Dispatchers.IO) { resolveLaunchIntent(pm, packageName) }
        val component = intent?.component
        return if (component != null) {
            exec(startCommand(intent, component))
        } else {
            exec("monkey -p ${packageName.shellArg()} -c android.intent.category.LAUNCHER 1")
        }
    }

    /**
     * 目标的启动入口。
     *
     * [PackageManager.getLaunchIntentForPackage] 就是桌面点图标那条路。它给不出结果
     * 的只剩两类：TV 的 `LEANBACK_LAUNCHER` 入口，和有 MAIN 但不带任何图标类别的
     * 应用 —— 后面那次纯 MAIN 查询把两类都兜住。
     *
     * 两处都用 `queryIntentActivities` 而不是 `resolveActivity`：前者返回按优先级排好
     * 的候选表，后者在多个候选同分时返回的是 `ResolverActivity`，那东西不能拿去
     * `am start -n`。
     */
    private fun resolveLaunchIntent(pm: PackageManager, packageName: String): Intent? {
        runCatching { pm.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.takeIf { it.component != null }
            ?.let { return it }

        val main = Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val activity = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(main, 0)
        }.getOrNull()?.firstOrNull()?.activityInfo ?: return null
        return main.setComponent(ComponentName(activity.packageName, activity.name))
    }

    /**
     * action / category 照抄解出来的 intent，组件写死。
     *
     * `-f 0x10200000` = `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`，
     * 桌面起应用用的就是这一组：目标已有任务栈时回到它的根 Activity，而不是把界面叠
     * 在用户上次停留的那一屏上面 —— 刚 force-stop 过，栈里留的是已经死掉的记录。
     */
    private fun startCommand(intent: Intent, component: ComponentName): String = buildString {
        append("am start")
        append(" -a ").append((intent.action ?: Intent.ACTION_MAIN).shellArg())
        intent.categories.orEmpty().forEach { append(" -c ").append(it.shellArg()) }
        append(" -f 0x10200000")
        append(" -n ").append(component.flattenToString().shellArg())
    }

    private suspend fun exec(command: String): Outcome = withContext(Dispatchers.IO) {
        if (!ensureAccess()) {
            return@withContext Outcome(false, lastError ?: "没有 root 权限")
        }
        val result = runCatching { Shell.cmd(command).exec() }.getOrElse {
            val message = it.message ?: it.javaClass.simpleName
            lastError = message
            return@withContext Outcome(false, message)
        }
        // am / monkey 出错时信息可能走 stdout 也可能走 stderr，两边都捞。
        val lines = result.out + result.err
        val reported = lines.firstOrNull { it.isFailureReport() }
        if (reported == null && result.isSuccess) {
            lastError = null
            return@withContext Outcome(true, null)
        }
        val message = reported
            ?: lines.firstOrNull { it.isNotBlank() }
            ?: "退出码 ${result.code}"
        lastError = message
        Outcome(false, message)
    }

    /**
     * 退出码不能单独作数：`am start` 起不来时照样退 0，错误只写在输出里
     * （`Error: Activity class {…} does not exist.`、`Error type 3`）。
     *
     * 只认 `Error` 开头，不能顺手把 `Warning` 也算上 —— 目标已经在前台时 `am` 打的是
     * `Warning: Activity not started, its current task has been brought to the front`，
     * 那是成功。
     */
    private fun String.isFailureReport(): Boolean {
        val line = trimStart()
        return line.startsWith("Error", ignoreCase = true) ||
            line.contains("monkey aborted")
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
