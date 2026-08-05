package com.chuanyi.hooker.hookers.cellularpro

import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.hookers.cellularpro.CellularPro.isNativePredicate
import com.chuanyi.hooker.nativehook.NativeHook
import java.lang.reflect.Modifier

/**
 * 会员判定的接管。
 *
 * ## 为什么只能在原生层做
 *
 * 会员管理器上的方法在 dex 里只剩 `native` 声明，方法体是 `libQualcommAdapter.so` 里的
 * 一段字节码，由 `libkotlin.so` 导出的解释器执行（nmmp 虚拟化，全包一万余个方法都是
 * 这样）。所以改不了 dex —— 方法体不在那里。
 *
 * 也不能用 ART 级 hook：会员记录的构造器会读调用栈确认调用方是管理器本身，任何
 * ART hook 都会在栈上多出一帧，应用随即判定被注入并逐个关闭 Activity。
 *
 * ## 改哪一层
 *
 * 目标对自己的代码段做完整性校验，所以能动的东西比看上去少。逐项试下来只剩两条路：
 *
 * | 做法 | 动了什么 | 结果 |
 * |---|---|---|
 * | 换 `RegisterNatives` 里的函数指针 | 绑定地址落到模块的 `.so` | 它回查绑定是否仍在自己模块内 |
 * | Dobby 原地 inline hook | 目标 `.text` 被写入 | 代码段完整性校验不过 |
 * | **改被解释执行的字节码** | 只动 `.rodata` | 可用，见 [VmCode] |
 * | **改被比较的状态字段** | 只是普通反射赋值 | 可用，见 [Channel] |
 *
 * ## 分成两半改
 *
 * 管理器上的判定其实是两类，必须区别对待：
 *
 * * [CellularPro.TIER_PREDICATES] —— 「会员成不成立」，只被界面读，**改字节码**；
 * * [CellularPro.CHANNEL_PREDICATES] —— 「当前拿到哪一档能力」的一组互斥判定，
 *   **不能改**：它们同时被射频与解码链路读，按常量硬顶会让解码器走进不匹配的分支，
 *   进程十几秒内崩在 `libd001.so` 里。这一档改的是被比较的**状态字段**本身，
 *   让这组判定各自算出自洽的结果。
 *
 * 功能入口的写法是一条判定链：命中低档位就跳去提示或购买页，全部落空才轮到高档那一支。
 * 所以只把「会员成立」按真不够，档位也得真的是高档那一档。
 *
 * ## 没有接管什么
 *
 * **会员记录本身不动。**伪造一份记录装进管理器是走得通的（用 `Unsafe` 绕开构造器里那道
 * 调用方校验），但记录带着服务端签名，界面读它时会复验，验不过同样让应用退出。真付费
 * 用户的记录因此也不受影响。
 *
 * 芯片平台判定与通用配置读取两组方法同样刻意不动 —— 它们与会员无关却与解调链路直接
 * 相关：实测把它们按成常量会让测量值全部变空，或让界面上的文本变成 `null`。
 */
internal object Membership {

    /**
     * 把注入的痕迹藏起来，并挡住检测线程的自杀动作。
     *
     * ## 目标确实在查
     *
     * 包里带着一整套检测件：`libplt-base.so`（自带的 PLT hook 框架，日志里那句
     * `hook_module_exit … over` 就是它）、`libtiny_magic.so`（`dlopen` / `dladdr` /
     * `readlink` / `uname` / `opendir` / `__system_property_get` 一整套环境探测）、
     * `libsgcore.so` 与 `libsecsdk.so`。实测确认在用的两条枚举路径：`dl_iterate_phdr`
     * 走 linker 的已加载对象链表，以及绕开 libc 直接下 `openat` 读 `/proc/self/maps`
     * （十几秒内查几十次）。
     *
     * ## 挡在哪
     *
     * * `env_spoof` —— 让环境探测得出「干净」的结论，并把 `/proc/<pid>/fd/<n>` 指向模块 APK 的
     *   那些链接报成不存在；它自己会连带装上 `linkmap_hide`，把模块的 `.so` 与 APK 映射
     *   从 linker 链表里摘掉。**必须赶在目标加载它那批 `.so` 之前装**。
     * * `suicide_guard` —— 用 seccomp 把绕开 libc 的 `openat` / `exit_group` 转成 SIGSYS
     *   接管掉，并锁住 SIGSYS 的处理器（目标的 `libucrash.so` 会来抢，抢走之后我们的陷阱
     *   会被它当成原生崩溃处理）。
     *
     * 这一项排在功能列表最前面，因为安装顺序就是声明顺序。
     */
    fun HookScope.installStealth() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())

        val installed = STEALTH_HOOKERS.filter { NativeHook.install(it) }
        if (installed.isEmpty()) error("反检测一项都装不上，原生层可能不完整")
        log.i("反检测已就位：${installed.joinToString()}")
        val missing = STEALTH_HOOKERS - installed.toSet()
        if (missing.isNotEmpty()) log.w("以下反检测项装不上：${missing.joinToString()}")
    }

    /**
     * 解锁会员。
     *
     * 两步都要等目标自己把管理器那批原生方法注册上来（懒注册，界面第一次用到才发生），
     * 所以整个过程放在后台轮询里做。
     */
    fun HookScope.installUnlock(refs: CellularProDex.Refs) {
        val manager = refs.managerClass ?: error("没定位到会员管理器")
        prepareNative()

        val tier = existing(manager, CellularPro.TIER_PREDICATES)
        if (tier.isEmpty()) {
            error(
                "会员判定一个都没找到 —— ${manager.name} 上没有 " +
                    "${CellularPro.TIER_PREDICATES.joinToString()}，目标版本可能换了重命名结果",
            )
        }
        val missing = CellularPro.TIER_PREDICATES - tier.toSet()
        if (missing.isNotEmpty()) {
            log.w("以下判定在目标里不存在，已跳过：${missing.joinToString()} —— 部分入口可能仍受限")
        }

        val log = log
        val switchChannel = isEnabled(FEATURE_TOP_CHANNEL, false)
        poll("cellularpro-unlock") {
            val thunks = tier.associateWith { NativeHook.jniRegistrationAddress(manager.name, it) }
            if (thunks.values.any { it == 0L }) return@poll false

            // 能力档位是可选的，且默认关着 —— 见 FEATURE_TOP_CHANNEL 的说明。
            // 应用还没给自己定档时 grantChannel 返回 null，那就下一轮再来。
            if (switchChannel && grantChannel(log, manager) == null) return@poll false

            val done = thunks.filter { (name, thunk) -> VmCode.forceReturn(log, name, thunk, true) }
            if (done.size != thunks.size) {
                log.e(
                    "会员判定有 ${thunks.size - done.size} 处改不动：" +
                        (thunks.keys - done.keys).joinToString(),
                )
            }
            if (done.isNotEmpty()) log.i("会员判定已接管：${done.keys.joinToString()}")
            true
        }
    }

    /** 会员页上那六个价格按钮不再渲染。纯外观，与权益无关。 */
    fun HookScope.installHidePurchase(refs: CellularProDex.Refs) {
        val manager = refs.managerClass ?: error("没定位到会员管理器")
        prepareNative()
        val names = existing(manager, listOf(CellularPro.PURCHASE_HIDDEN))
        if (names.isEmpty()) error("没找到 ${CellularPro.PURCHASE_HIDDEN}，购买入口无法隐藏")
        val log = log
        poll("cellularpro-purchase") {
            val thunk = NativeHook.jniRegistrationAddress(manager.name, names.first())
            if (thunk == 0L) return@poll false
            if (VmCode.forceReturn(log, names.first(), thunk, true)) log.i("购买入口已隐藏")
            true
        }
    }

    /** 排查用：等目标把会员链路上的类注册完之后，把捕获到的原生方法列出来。 */
    fun HookScope.installLog(refs: CellularProDex.Refs) {
        if (!NativeHook.isAvailable) {
            log.w("原生层不可用，无法记录判定：${NativeHook.lastError}")
            return
        }
        NativeHook.watchJniRegistrations()
        val names = listOfNotNull(refs.managerClass?.name, refs.recordClass?.name)
        val log = log
        poll("cellularpro-log") { round ->
            val entries = NativeHook.jniRegistrations().filter { it.className in names }
            if (entries.isEmpty()) return@poll false
            log.i("第 ${round + 1} 次采样，捕获到 ${entries.size} 个原生方法：")
            entries.forEach {
                log.i("  ${it.className}.${it.methodName}${it.signature} @ 0x${it.address.toString(16)}")
            }
            true
        }
    }

    // -----------------------------------------------------------------------

    private fun HookScope.prepareNative() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())
        // 监视本身不改任何指针，只是把「名字 -> 绑定地址」记下来。
        if (!NativeHook.watchJniRegistrations()) error("RegisterNatives 监视装不上")
    }

    /** [names] 里确实存在于 [owner] 上的那些判定。先按形状复核，免得挂一个不存在的名字。 */
    private fun existing(owner: Class<*>, names: Collection<String>): List<String> {
        val available = runCatching { owner.declaredMethods }.getOrNull()
            ?.filter { it.isNativePredicate() }
            ?.mapTo(HashSet()) { it.name }
            ?: return emptyList()
        return names.filter { it in available }
    }

    /**
     * 换档位：拿到单例，交给 [Channel] 做差分探测。
     *
     * 单例取值方法本身也是被虚拟化的原生方法，但**调用**它和调用普通方法没有区别 ——
     * 只是不能 hook 它。这里只调用。
     */
    private fun grantChannel(log: HookerLog, manager: Class<*>): Channel.Grant? {
        val singleton = runCatching {
            manager.declaredMethods
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == manager
                }
                ?.apply { isAccessible = true }
                ?.invoke(null)
        }.getOrNull() ?: run {
            log.w("拿不到会员管理器的单例，档位保持原样")
            return null
        }
        val exclusive = manager.declaredMethods
            .filter { it.isNativePredicate() && it.name in CellularPro.CHANNEL_PREDICATES }
            .onEach { it.isAccessible = true }
        return Channel.grantTopTier(log, manager, singleton, exclusive)
    }

    /** 后台轮询直到 [step] 返回 true，或者次数用尽。 */
    private fun poll(threadName: String, step: (Int) -> Boolean) {
        Thread {
            repeat(POLL_ATTEMPTS) { round ->
                Thread.sleep(POLL_INTERVAL_MS)
                if (runCatching { step(round) }.getOrDefault(false)) return@Thread
            }
        }.apply { isDaemon = true; name = threadName }.start()
    }

    /** 一共等约 60 秒 —— 会员相关的类通常在打开设置页时才注册。 */
    private const val POLL_ATTEMPTS = 60
    private const val POLL_INTERVAL_MS = 1_000L

    /** 顺序有意义：先洗环境（它会连带把模块从 linker 链表里摘掉），再挂自杀兜底。 */
    private val STEALTH_HOOKERS = listOf("env_spoof", "suicide_guard")

    /**
     * 见 [CellularProHooker] 里同名的功能开关。**默认关闭**。
     *
     * 换档位会改掉解码通道的选择：实测在骁龙机型上把它换到最高档之后，厂商解码库
     * `libd001.so` 会在十几秒内崩掉（`fault addr = 0x0`，PC 落在非法地址，
     * 即跳进了垃圾），且**只做这一步、完全不改任何判定**时同样会崩 —— 也就是说这不是
     * 注入被发现，而是那一档在这台设备上根本走不通。
     *
     * 留着这个开关是因为它在别的芯片平台上可能是成立的：工参管理那条入口链要求
     * 高档位为真，这是唯一能让它成立而又不去按常量硬顶那组判定的办法。
     */
    const val FEATURE_TOP_CHANNEL = "top_channel"
}
