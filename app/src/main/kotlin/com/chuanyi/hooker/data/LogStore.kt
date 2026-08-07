package com.chuanyi.hooker.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.core.LogLevel
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条日志。
 *
 * [id] 只用来当列表的 key —— 时间戳会撞（同一毫秒里能打好几条），tag 加正文更会撞
 * （循环里打同一句）。key 撞了 LazyColumn 直接抛，所以必须有一个自己发的号。
 */
@Immutable
data class LogEntry(
    val id: Long,
    val time: Long,
    val level: LogLevel,
    /** 产生它的进程，形如 `com.foo` 或 `com.foo:push`。 */
    val process: String,
    /** 形如 `ChuanyiHooker/poweramp`。 */
    val tag: String,
    val message: String,
)

/**
 * 模块界面这一侧的日志仓库。
 *
 * 数据从两处进来：
 *
 *  * [LogReceiver] —— 被注入的进程广播回来的（[com.chuanyi.hooker.core.LogRelay]）；
 *  * [append] —— 模块应用自己产生的（框架连上了、令牌收下了这类）。
 *
 * ## 内存里一份，磁盘上一份
 *
 * [entries] 是给界面看的，封顶 [CAPACITY] 条，满了从头上砍。磁盘那份是为了「杀掉
 * 应用再打开，之前那次注入的日志还在」—— 而那恰恰是最需要日志的场景：目标一崩，
 * 用户第一件事就是回来看刚才发生了什么。
 *
 * 落盘走一条单线程 executor，界面线程只管往 [entries] 里塞。两份的顺序各自由各自的
 * 队列保证，互不等待。
 *
 * ## 行格式
 *
 * ```
 * 时间(毫秒) ⟨sep⟩ 等级id ⟨sep⟩ 进程 ⟨sep⟩ tag ⟨sep⟩ 正文(换行替换成 ⟨nl⟩)
 * ```
 *
 * 分隔符用 U+0001 / U+0002 这两个控制字符，不用逗号或制表符：正文是从目标应用里捞
 * 出来的任意文本，寻常分隔符都可能出现在里面，而这两个不会（Android 的日志 API 也
 * 不产出它们）。于是「一行就是一条」，读的时候不必做任何转义状态机。
 */
@Stable
object LogStore {

    /** 界面里最多留多少条。再多就是往上翻也翻不到底的量了。 */
    const val CAPACITY = 2000

    /** 单个日志文件的上限，超了就轮转。两个文件合计约 1 MB。 */
    private const val FILE_LIMIT_BYTES = 512L * 1024L

    private const val FIELD = '\u0001'
    private const val NEWLINE = '\u0002'

    private val nextId = AtomicLong(1L)

    /** 旧 -> 新。界面自己按需要倒过来显示。 */
    val entries: SnapshotStateList<LogEntry> = mutableStateListOf()

    /**
     * 因为队列满而在**发送侧**丢掉的条数（见 [com.chuanyi.hooker.core.LogRelay]）。
     * 界面照实显示，让人知道自己看到的不是全部。
     */
    var dropped by mutableIntStateOf(0)
        private set

    /** 磁盘那份读回来了没有。读回来之前界面上只有本次运行产生的行。 */
    var isRestored by mutableStateOf(false)
        private set

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chuanyi-log-store").apply { isDaemon = true }
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** 模块应用自己那些行显示成什么进程。[restore] 接上 Context 时填。 */
    @Volatile
    private var selfProcess: String = "module"

    @Volatile
    private var logDir: File? = null

    /** 当前文件已经写了多少字节。只在 [io] 那条线程上读写。 */
    @Volatile
    private var currentSize = 0L

    /**
     * 接上磁盘，并把上次的日志读回来。由 [com.chuanyi.hooker.ui.MainActivity] 调一次。
     *
     * 历史条目是**插到最前面**的，不是追加：读盘期间广播可能已经送来了新的行，那些
     * 必须留在后面。历史用负号 id（`-n … -1`），插进去之后整个列表仍然按 id 单调递增。
     */
    fun restore(context: Context) {
        if (logDir != null) return
        val appContext = context.applicationContext
        selfProcess = appContext.packageName
        val dir = File(appContext.filesDir, "logs")
        logDir = dir

        io.execute {
            val history = runCatching { readHistory(dir) }.getOrDefault(emptyList())
            currentSize = runCatching { currentFile(dir).length() }.getOrDefault(0L)
            val numbered = history.mapIndexed { index, raw ->
                raw.copy(id = (index - history.size).toLong())
            }
            main.post {
                entries.addAll(0, numbered)
                trim()
                isRestored = true
            }
        }
    }

    /**
     * 模块应用自己的一行（框架掉线、令牌换了这类）。被注入的进程走 [appendBatch]。
     *
     * tag 统一挂在根 tag 下，界面上那一列才和被注入的进程是同一套写法。
     */
    fun append(level: LogLevel, tag: String, message: String) {
        appendBatch(
            process = selfProcess,
            times = longArrayOf(System.currentTimeMillis()),
            levels = intArrayOf(level.id),
            tags = arrayOf("${HookerLog.ROOT_TAG}/$tag"),
            messages = arrayOf(message),
            droppedBySender = 0,
        )
    }

    /**
     * 收下一批。参数就是 [com.chuanyi.hooker.core.LogRelay.Wire] 那几个平行数组，
     * 原样接过来不必先组装成对象。
     *
     * [entries] 是 snapshot 列表，跨线程写会让界面读到撕裂的中间状态，所以不在主线程
     * 时先转一跳。[LogReceiver] 的 `onReceive` 本来就在主线程，那条路上这个判断是白判
     * 一次布尔 —— 但 [append] 的调用方（广播接收器里另起的线程、后台作业）不是，
     * 与其在每个调用点提醒一遍「记得切线程」，不如在这里一次挡掉。
     */
    fun appendBatch(
        process: String,
        times: LongArray,
        levels: IntArray,
        tags: Array<String>,
        messages: Array<String>,
        droppedBySender: Int,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { appendBatch(process, times, levels, tags, messages, droppedBySender) }
            return
        }
        if (droppedBySender > 0) dropped += droppedBySender

        // 四个数组长度对不上说明这条广播不是本模块发的（接收器是导出的，谁都能发），
        // 按最短的那个截断即可 —— 不用报错，日志页不值得为一条垃圾广播弹东西。
        val count = minOf(times.size, levels.size, tags.size, messages.size)
        if (count == 0) return

        val added = ArrayList<LogEntry>(count)
        for (index in 0 until count) {
            added += LogEntry(
                id = nextId.getAndIncrement(),
                time = times[index],
                level = LogLevel.byId(levels[index]),
                process = process,
                tag = tags[index],
                message = messages[index],
            )
        }
        entries.addAll(added)
        trim()
        persist(added)
    }

    /** 清空内存和磁盘两份。 */
    fun clear() {
        entries.clear()
        dropped = 0
        val dir = logDir
        io.execute {
            currentSize = 0L
            if (dir != null) {
                runCatching { currentFile(dir).delete() }
                runCatching { rotatedFile(dir).delete() }
            }
        }
    }

    /** 导出成人能读的文本。行首那一段和界面上显示的是同一套。 */
    fun render(list: List<LogEntry>): String = buildString {
        list.forEach { entry ->
            append(LogFormat.stamp(entry.time))
            append(' ').append(entry.level.mark)
            append(' ').append(entry.process)
            append(' ').append(entry.tag)
            append("  ").append(entry.message)
            append('\n')
        }
    }

    private fun trim() {
        val excess = entries.size - CAPACITY
        if (excess > 0) entries.removeRange(0, excess)
    }

    // --- 磁盘 ---------------------------------------------------------------

    private fun currentFile(dir: File) = File(dir, "module.log")

    private fun rotatedFile(dir: File) = File(dir, "module.log.1")

    private fun persist(added: List<LogEntry>) {
        val dir = logDir ?: return
        val text = buildString {
            added.forEach { entry ->
                append(entry.time).append(FIELD)
                append(entry.level.id).append(FIELD)
                append(entry.process).append(FIELD)
                append(entry.tag).append(FIELD)
                append(entry.message.replace('\n', NEWLINE))
                append('\n')
            }
        }
        io.execute {
            runCatching {
                if (!dir.isDirectory) dir.mkdirs()
                val file = currentFile(dir)
                // 轮转在**写之前**判：写完再判的话，刚超限的那一批会先落进旧文件，
                // 下一次轮转就把它整个带走了。
                if (currentSize >= FILE_LIMIT_BYTES) {
                    val rotated = rotatedFile(dir)
                    rotated.delete()
                    file.renameTo(rotated)
                    currentSize = 0L
                }
                file.appendText(text)
                currentSize += text.toByteArray().size
            }
        }
    }

    /**
     * 读回最近 [CAPACITY] 条。轮转过的那份排在前面（它更旧）。
     *
     * 整个读进内存再截尾：两个文件加起来封顶 1 MB，不值得为它写一个反向逐行读取器。
     */
    private fun readHistory(dir: File): List<LogEntry> {
        val lines = buildList {
            listOf(rotatedFile(dir), currentFile(dir)).forEach { file ->
                if (file.isFile) addAll(file.readLines())
            }
        }
        return lines.takeLast(CAPACITY).mapNotNull(::parse)
    }

    /** id 由 [restore] 统一发，这里先占位成 0。 */
    private fun parse(line: String): LogEntry? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD, limit = 5)
        if (parts.size < 5) return null
        val time = parts[0].toLongOrNull() ?: return null
        val level = parts[1].toIntOrNull() ?: return null
        return LogEntry(
            id = 0L,
            time = time,
            level = LogLevel.byId(level),
            process = parts[2],
            tag = parts[3],
            message = parts[4].replace(NEWLINE, '\n'),
        )
    }
}
