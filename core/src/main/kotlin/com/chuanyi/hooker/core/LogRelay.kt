package com.chuanyi.hooker.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 把被注入进程里的日志送回模块应用。
 *
 * ## 为什么需要它
 *
 * hook 跑在**目标应用的进程**里，日志落进 logcat 和 LSPosed 的模块日志。这两处
 * 模块自己都读不到：
 *
 *  * logcat —— 一个普通应用只读得到自己 uid 打的行，别的进程的要 `READ_LOGS`
 *    （签名权限）或者 root；
 *  * LSPosed 的模块日志 —— 在管理器的私有目录里。
 *
 * 所以想在模块界面里看日志，只剩「让产生日志的那一侧主动递过来」这一条路。这和
 * 激活令牌那一跳（[ActivationGuard.Broadcast]）是同一个形状，理由也一样：被注入的
 * 进程对模块的存储**只读**，递不进去，只能广播。
 *
 * ## 攒着发，不是来一条发一条
 *
 * 一次 binder 往返在毫秒级，而 hook 里一句 `log.d` 可能挂在目标每帧都会走的路径上。
 * 所以 [offer] 只把条目丢进队列就返回（一次 `synchronized` 加一次入队），真正的发送
 * 由一条后台线程每 [QUIET_MILLIS] 毫秒攒一批送走。目标进程的线程一次都不会被
 * 阻塞在 binder 上。
 *
 * 队列上限 [QUEUE_LIMIT]，满了丢**最旧的**并计数 —— 日志洪峰时用户要看的是刚发生的
 * 那几条，丢新的等于把最有用的部分扔掉。丢了多少会随下一批一起报上去，界面照实显示。
 *
 * ## 拿 Context 是延迟的
 *
 * [AppHooker.Stage.PACKAGE_LOADED] 阶段目标的 `Application` 还不存在，发不了广播。
 * 这不用特殊处理：那时产生的条目留在队列里，等后台线程某一轮取到 Context 再一起送 ——
 * 时间戳是**入队时**记的，所以晚送不会让顺序或时间显示错乱。
 *
 * ## 这条路上不许打日志
 *
 * 这个文件里任何地方都不能调 [HookerLog]：那会立刻绕回 [offer]，形成自喂的死循环。
 * 真要报错就直接走 [Log]，且只在 [Log.WARN] 以上、不带节流地报一次。
 */
object LogRelay {

    /**
     * 递送契约。发送方是 hooker 模块（在目标进程里），接收方在 `:app`，两边都只依赖
     * `:core` —— 和 [ActivationGuard.Broadcast] 一样放在这里，避免两侧各写一份字符串。
     *
     * 一批日志拍成几个**平行数组**而不是一串 Parcelable：数组是 bootclasspath 类型，
     * 不必让接收方认识发送方 classloader 里的类，也就不存在版本错开时反序列化失败的
     * 可能 —— 模块热更新之后，目标进程里跑的可能还是上一代的代码。
     */
    object Wire {
        const val ACTION = "com.chuanyi.hooker.action.LOG"
        const val RECEIVER = "com.chuanyi.hooker.data.LogReceiver"

        /** 发送方进程名，形如 `com.foo` 或 `com.foo:push`。界面按它分组。 */
        const val EXTRA_PROCESS = "process"

        /** `long[]`，每条的产生时刻（毫秒）。 */
        const val EXTRA_TIMES = "times"

        /** `int[]`，每条的 [LogLevel.id]。 */
        const val EXTRA_LEVELS = "levels"

        /** `String[]`，每条的 tag。 */
        const val EXTRA_TAGS = "tags"

        /** `String[]`，每条的正文（异常栈已经拼在里面）。 */
        const val EXTRA_MESSAGES = "messages"

        /** `int`，上一批到这一批之间因为队列满而丢掉的条数。 */
        const val EXTRA_DROPPED = "dropped"
    }

    /** 队列上限。满了丢最旧的。 */
    private const val QUEUE_LIMIT = 512

    /** 一次广播最多带多少条。整批的 Bundle 要塞进 binder 事务缓冲区（1 MB）。 */
    private const val BATCH_LIMIT = 64

    /** 攒批的间隔。同时也是「一条日志最晚多久出现在界面上」。 */
    private const val QUIET_MILLIS = 400L

    /** 单条正文上限。异常栈可以很长，截断比撑爆事务缓冲区好。 */
    private const val MESSAGE_LIMIT = 4000

    /** 连着这么多轮没东西可送就收工，[offer] 会在需要时把线程重新拉起来。 */
    private const val IDLE_ROUNDS_BEFORE_STOP = 25

    /** 连着这么多轮取不到 Context 就放弃这个进程（不是每个进程都有 Application）。 */
    private const val CONTEXT_ROUNDS_BEFORE_GIVE_UP = 50

    private class Pending(
        val time: Long,
        val level: Int,
        val tag: String,
        val message: String,
    )

    private val lock = Any()

    /** 模块应用的包名。null 表示还没接线，此时 [offer] 直接返回。 */
    @Volatile
    private var modulePackage: String? = null

    @Volatile
    private var processName: String = ""

    @Volatile
    private var enabled: Boolean = false

    /** 缓存下来的目标 Application。取到一次就不会再变。 */
    @Volatile
    private var cachedContext: Context? = null

    private val queue = ArrayDeque<Pending>()
    private var dropped = 0
    private var worker: Thread? = null

    /**
     * 接线。每一代模块（初次加载和每次热重载）都要重做一遍 —— 热重载换 classloader，
     * 新一代的这个 object 是全新的。
     *
     * [enabled] 为 false 时连队列都不要：不上报的进程不该为这件事付任何代价。
     */
    fun configure(modulePackage: String?, processName: String, enabled: Boolean) {
        this.modulePackage = modulePackage?.takeIf { it.isNotEmpty() }
        this.processName = processName
        this.enabled = enabled
        if (!enabled) {
            synchronized(lock) {
                queue.clear()
                dropped = 0
            }
        }
    }

    /**
     * 收下一条。**由 [HookerLog.write] 调用，不要在别处调**。
     *
     * 只做入队，不做任何 IO。等级过滤已经在 [HookerLog] 那边做过了。
     */
    fun offer(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!enabled || modulePackage == null) return

        val text = buildString {
            append(message.take(MESSAGE_LIMIT))
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable).take(MESSAGE_LIMIT))
            }
        }

        synchronized(lock) {
            // 满了丢最旧的：洪峰时用户要看的是刚发生的那几条。
            if (queue.size >= QUEUE_LIMIT) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(Pending(System.currentTimeMillis(), level.id, tag, text))
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker != null) return
            val thread = Thread(::pump, "chuanyi-log-relay").apply {
                // 守护线程 + 最低优先级：这是纯粹的旁路，绝不能影响目标本身的调度，
                // 也不该让目标进程因为它而多活一秒。
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            // 起不来（进程正在收尾、线程数到顶）时**不能**把它记成当前 worker：
            // 记了的话这个字段就再也不是 null，往后每一次 offer 都以为有人在跑，
            // 而实际上一条都发不出去。
            if (runCatching { thread.start() }.isSuccess) worker = thread
        }
    }

    /**
     * 后台线程主体。
     *
     * 收工条件有两个，都会把 [worker] 置空，下一次 [offer] 再把它拉起来：
     *
     *  * 连着 [IDLE_ROUNDS_BEFORE_STOP] 轮无事可做 —— 常态，注入完成后日志就停了；
     *  * 连着 [CONTEXT_ROUNDS_BEFORE_GIVE_UP] 轮取不到 Context —— 有些进程（比如只跑
     *    了 ContentProvider 的）确实到死都没有 Application，不能为它一直空转。
     */
    private fun pump() {
        var idleRounds = 0
        var contextRounds = 0

        while (true) {
            runCatching { Thread.sleep(QUIET_MILLIS) }.onFailure { return abandon() }
            if (!enabled) return abandon()

            val context = appContext()
            if (context == null) {
                // 不取队列：条目留着等 Context 出现，超上限由 offer 丢最旧的。
                if (++contextRounds >= CONTEXT_ROUNDS_BEFORE_GIVE_UP) return abandon()
                continue
            }
            contextRounds = 0

            val ready = drainBatch()
            if (ready == null) {
                if (++idleRounds >= IDLE_ROUNDS_BEFORE_STOP && stopIfDrained()) return
                continue
            }
            idleRounds = 0
            send(context, ready.first, ready.second)
        }
    }

    /** 取一批。队列空时返回 null。 */
    private fun drainBatch(): Pair<List<Pending>, Int>? = synchronized(lock) {
        if (queue.isEmpty()) return null
        val size = minOf(BATCH_LIMIT, queue.size)
        val batch = ArrayList<Pending>(size)
        repeat(size) { batch += queue.removeFirst() }
        val drops = dropped
        dropped = 0
        batch to drops
    }

    /**
     * 闲够了，收工 —— 但只在**此刻队列确实还空着**的时候。
     *
     * 判空和交还 [worker] 必须在同一个锁里。分开写会漏：`drainBatch` 返回 null 之后、
     * 交还 worker 之前要是有一次 [offer]，那次的 `ensureWorker` 会看到
     * `worker != null` 而不起线程，紧接着这边把它置空、线程退出 —— 那一批就悬在队列
     * 里，直到下一条日志碰巧把线程重新拉起来才被送走，一条都不来就永远留在那儿。
     *
     * @return true 表示可以退出线程了
     */
    private fun stopIfDrained(): Boolean = synchronized(lock) {
        if (queue.isNotEmpty()) return false
        worker = null
        true
    }

    /** 放弃这个进程（关掉了、或者到死都没有 Application）。留着队列没有意义。 */
    private fun abandon() {
        synchronized(lock) {
            queue.clear()
            dropped = 0
            worker = null
        }
    }

    private fun send(context: Context, batch: List<Pending>, drops: Int) {
        val target = modulePackage ?: return
        val intent = Intent(Wire.ACTION)
            .setClassName(target, Wire.RECEIVER)
            .putExtra(Wire.EXTRA_PROCESS, processName)
            .putExtra(Wire.EXTRA_TIMES, LongArray(batch.size) { batch[it].time })
            .putExtra(Wire.EXTRA_LEVELS, IntArray(batch.size) { batch[it].level })
            .putExtra(Wire.EXTRA_TAGS, Array(batch.size) { batch[it].tag })
            .putExtra(Wire.EXTRA_MESSAGES, Array(batch.size) { batch[it].message })
            .putExtra(Wire.EXTRA_DROPPED, drops)
            // 模块应用可能从装上就没被打开过，或者被用户划掉过。不带这个标志，
            // 广播会按「已停止的包」被系统静默丢掉。和激活那条链路是同一个坑。
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

        runCatching { context.sendBroadcast(intent) }
    }

    /**
     * 目标的 Application。
     *
     * 和 [HookScope.appContextOrNull] 同一条路，但这里不能用那个 —— 后台线程手里没有
     * scope，而且这个 object 要在 hooker 之外（比如 [HookerRuntime] 自己的日志）也能用。
     */
    private fun appContext(): Context? {
        cachedContext?.let { return it }
        val found = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application
        }.getOrNull()
        cachedContext = found
        return found
    }
}
