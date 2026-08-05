package com.chuanyi.hooker.hookers.airmusic

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * `libaudioproxy.so` 里那道「是不是正版」的闸门。
 *
 * ## 噪音是怎么加进去的
 *
 * 试用版每隔一段时间往 PCM 里混一段正弦波，应用自己的 `trial_message` 写得很直白：
 * *"some noise will be added to the audio after 10 minutes of playback"*。
 * 这件事整个发生在 `AudioProxy.transfer()` 的原生实现里，Java 侧看不到任何痕迹：
 *
 * ```text
 * ldrb w8, [x28, #AUTH]      ; 授权标志
 * cbz  w8, mix_noise         ; 0 → 混噪音
 * bl   pkg_gate              ; 包名后缀检查
 * tbz  w0, 0, mix_noise      ; false → 混噪音
 * bl   sig_gate              ; 签名 CRC 检查
 * tbnz w0, 0, clean          ; true → 干净输出
 * mix_noise:
 *   ...  w9++ ; if (w9 <= samplerate*1200) continue    ← 这就是那 10 分钟
 *   ...  取正弦表的值，与采样相加取平均，写回缓冲区
 * ```
 *
 * `samplerate*1200` 数的是 16 位**单**采样，立体声一帧两个，所以门限正好是 600 秒。
 *
 * ## 三个落点，一个都不能少
 *
 * 同一个三元组合门在 `transfer` 里出现三次、`JNI_OnLoad` 里一次，语义都一样。
 * 要让它整条通过，三个量都得对：
 *
 * | 量 | 含义 | 实测初值 |
 * |---|---|---|
 * | 授权标志 | 唯一写入点是 `AudioProxy.h` 验签成功那一刻 | `0` |
 * | 包名闸缓存 | 见下，检查包名是不是 `.pro` 结尾 | `0`（已判定为否） |
 * | 签名闸缓存 | 签名 CRC 比对三个常量之一 | `-1`（没跑过） |
 *
 * 包名那道闸的判据很朴素：取包名末尾，要求 `s[len-4] == '.'` 且 `s[len-1] == 'o'`
 * —— 也就是必须以 `.pro` 结尾。`app.airmusic.trial` 落在 `r` 上，所以**试用版和付费版
 * 共用同一份 so，靠包名后缀区分**。这也是为什么光把授权标志置 1 没用：包名闸的缓存
 * 已经是 0 了，短路在它那里。
 *
 * 两道闸都带缓存（`-1` = 没算过），命中缓存就直接返回，所以把缓存填成 1 等价于让它们
 * 恒真，比 hook 更轻。Dobby 那条路留作 [openGate] 的 `pinGates` 选项：万一某个版本改成
 * 每次重算，钉住返回值仍然有效。
 *
 * ## 为什么不直接改那条分支指令
 *
 * 改指令要写 `.text`，而走「让应用以为自己是正版」这条路，只碰三个 `.data` 上的量，
 * 应用其余部分的行为和真正的付费版**完全一致** —— 包括这三个量在别处的读取点。
 * 少一次对指令流的假设，也就少一个下个版本会碎掉的地方。
 *
 * 顺带：这三个量都在 `rw-` 数据页上，且同一页还放着 lame 的编码器指针等应用自己要写的
 * 东西。所以只能用 [NativeHook.writeMemory]（读真实权限、只补 `PROT_WRITE`、写完原样
 * 恢复），不能用 `patchMemory` —— 后者底层的 `DobbyCodePatch` 会把页恢复成
 * `PROT_READ|PROT_EXEC`，应用下一次写那一页就死于 `SEGV_ACCERR`，而且崩在很远的地方。
 */
internal object NativeGate {

    const val LIBRARY = "libaudioproxy.so"

    /**
     * 授权标志的唯一写入者，也是 Java 侧 `Policy.allowAccess()` 的最终裁决。
     * 名字来自 `AudioProxy.h(String, String)` 的 JNI 修饰，未混淆。
     */
    private const val SYM_AUTH = "Java_app_airmusic_proxy_AudioProxy_h"

    /** 混噪音的地方。两道闸的调用点在它开头，用来定位那两个函数。 */
    private const val SYM_TRANSFER = "Java_app_airmusic_proxy_AudioProxy_transfer___3BII"

    /** `h` 有 780 字节，写标志那条 `strb` 在最后。 */
    private const val AUTH_SCAN_BYTES = 0x400

    /** 两道闸的调用点距 `transfer` 起点 0xB0，读这么多绰绰有余。 */
    private const val TRANSFER_SCAN_BYTES = 0x200

    /** 闸函数开头几条就是取缓存，不用扫远。 */
    private const val GATE_SCAN_BYTES = 0x40

    /** 解析出来的落点，全是运行时绝对地址。 */
    data class Layout(
        val authFlag: Long,
        val pkgGate: Long,
        val pkgCache: Long,
        val sigGate: Long,
        val sigCache: Long,
    )

    /**
     * 解析 [Layout]，失败返回 null。
     *
     * 全程只认导出符号加指令形状，不含任何写死的偏移 —— 目标下一版挪动了代码，这里照样
     * 能算对。前提只有一条：`libaudioproxy.so` 已经被目标进程加载。
     */
    fun resolve(log: HookerLog): Layout? {
        if (!NativeHook.isAvailable) {
            log.w("原生层不可用（${NativeHook.lastError}），噪音开关跳过")
            return null
        }
        val base = NativeHook.moduleBase(LIBRARY)
        if (base == 0L) {
            log.d("$LIBRARY 还没加载，稍后再试")
            return null
        }

        val authSym = NativeHook.findSymbol(LIBRARY, SYM_AUTH)
        if (authSym == 0L) {
            log.w("找不到 $SYM_AUTH，$LIBRARY 可能已换实现")
            return null
        }
        // `h` 里只有一处 strb，就是验签通过后置授权标志那一条。
        val authFlag = Arm64.findAbsoluteRef(authSym, AUTH_SCAN_BYTES, Arm64.Ref.STRB)
        if (authFlag == 0L) {
            log.w("$SYM_AUTH 里没解出授权标志的地址")
            return null
        }

        val transfer = NativeHook.findSymbol(LIBRARY, SYM_TRANSFER)
        if (transfer == 0L) {
            log.w("找不到 $SYM_TRANSFER")
            return null
        }
        // 开头那几个 bl 是 lame 和 log 的 PLT 桩，按「目标首指令是标准函数序言」筛掉。
        val gates = Arm64.findLocalCalls(transfer, TRANSFER_SCAN_BYTES, limit = 2)
        if (gates.size < 2) {
            log.w("$SYM_TRANSFER 开头只找到 ${gates.size} 个本地调用，认不出两道闸")
            return null
        }
        val (pkgGate, sigGate) = gates
        // 每道闸开头第一条 `ldr Wt, [Xn, #imm]` 取的就是自己的缓存。
        val pkgCache = Arm64.findAbsoluteRef(pkgGate, GATE_SCAN_BYTES, Arm64.Ref.LDR_W)
        val sigCache = Arm64.findAbsoluteRef(sigGate, GATE_SCAN_BYTES, Arm64.Ref.LDR_W)
        if (pkgCache == 0L || sigCache == 0L) {
            log.w("闸函数里没解出缓存地址（包名闸=$pkgCache 签名闸=$sigCache）")
            return null
        }

        val layout = Layout(authFlag, pkgGate, pkgCache, sigGate, sigCache)
        log.i(
            "已定位 $LIBRARY 落点（相对基址）：" +
                "授权标志=+0x${(authFlag - base).toString(16)} " +
                "包名闸=+0x${(pkgGate - base).toString(16)}(缓存 +0x${(pkgCache - base).toString(16)}) " +
                "签名闸=+0x${(sigGate - base).toString(16)}(缓存 +0x${(sigCache - base).toString(16)})",
        )
        return layout
    }

    /**
     * 把闸打开。已经开着就什么都不做，返回 true。
     *
     * @param pinGates 额外用 Dobby 把两道闸的返回值钉成 1。缓存路径失效时的保险，
     *   代价是两个 inline hook。
     */
    fun openGate(log: HookerLog, layout: Layout, pinGates: Boolean): Boolean {
        val before = read(layout)
        if (before != null && before.isOpen) {
            log.d("闸已经是开的，跳过")
            return true
        }

        val one = byteArrayOf(1)
        val oneWord = byteArrayOf(1, 0, 0, 0)   // 小端 int32 = 1
        var ok = true
        ok = NativeHook.writeMemory(layout.pkgCache, oneWord) && ok
        ok = NativeHook.writeMemory(layout.sigCache, oneWord) && ok
        ok = NativeHook.writeMemory(layout.authFlag, one) && ok

        if (pinGates) {
            // 缓存被重置也不怕：直接让两道闸恒返回 1。
            val a = NativeHook.returnConstant(layout.pkgGate, 1L)
            val b = NativeHook.returnConstant(layout.sigGate, 1L)
            log.d("Dobby 钉住闸返回值：包名闸=$a 签名闸=$b")
        }

        val after = read(layout)
        return when {
            after == null -> {
                log.w("写完读不回来，无法确认")
                false
            }

            after.isOpen -> {
                log.i(
                    "试用噪音已关闭（授权标志 ${before?.authFlag} → ${after.authFlag}，" +
                        "包名闸 ${before?.pkgCache} → ${after.pkgCache}，" +
                        "签名闸 ${before?.sigCache} → ${after.sigCache}）",
                )
                true
            }

            else -> {
                log.w("写入未生效：授权标志=${after.authFlag} 包名闸=${after.pkgCache} 签名闸=${after.sigCache}")
                false
            }
        }
    }

    /** 三个量的当前值。 */
    data class State(val authFlag: Int, val pkgCache: Int, val sigCache: Int) {
        val isOpen: Boolean get() = authFlag != 0 && pkgCache != 0 && sigCache != 0
    }

    fun read(layout: Layout): State? {
        val auth = NativeHook.readMemory(layout.authFlag, 1) ?: return null
        val pkg = NativeHook.readMemory(layout.pkgCache, 4) ?: return null
        val sig = NativeHook.readMemory(layout.sigCache, 4) ?: return null
        return State(auth[0].toInt() and 0xFF, pkg.int32(), sig.int32())
    }

    private fun ByteArray.int32(): Int =
        (this[0].toInt() and 0xFF) or
            ((this[1].toInt() and 0xFF) shl 8) or
            ((this[2].toInt() and 0xFF) shl 16) or
            ((this[3].toInt() and 0xFF) shl 24)
}

/**
 * 够用的一小撮 AArch64 解码。
 *
 * 存在的理由是「不写死偏移」：`0x4e52c` 这种数字只对当前这一版成立，而
 * `adrp Xn, page` + `strb Wt, [Xn, #off]` 这个**形状**是编译器生成
 * 「给某个全局变量赋值」时的固定产物，跨版本、跨编译器都稳。
 *
 * 只解四条指令，全部是无条件形式，够定位就行。
 */
private object Arm64 {

    /** 解出绝对地址的两种取址形式。 */
    enum class Ref { STRB, LDR_W }

    /**
     * 在 [start] 起 [bytes] 字节内找第一处「`adrp` 定基址 + 取址指令带偏移」，
     * 返回它指向的绝对地址；找不到返回 0。
     *
     * 逐条跟踪每个寄存器最近一次 `adrp` 的结果 —— 这两处的 `adrp` 和取址指令都紧挨着，
     * 但跟踪整张表可以容忍编译器在中间插调度。
     */
    fun findAbsoluteRef(start: Long, bytes: Int, ref: Ref): Long {
        val code = NativeHook.readMemory(start, bytes) ?: return 0L
        val page = LongArray(32)
        for (offset in 0 until code.size - 3 step 4) {
            val pc = start + offset
            val insn = code.insn(offset)

            if (insn and 0x9F000000.toInt() == 0x90000000.toInt()) {
                // adrp Xd, #imm : Xd = (PC & ~0xFFF) + (SignExtend(immhi:immlo) << 12)
                val immLo = (insn ushr 29) and 0b11
                val immHi = (insn ushr 5) and 0x7FFFF
                val imm = signExtend(((immHi shl 2) or immLo).toLong(), 21) shl 12
                page[insn and 0x1F] = (pc and 0xFFF.inv()) + imm
                continue
            }

            when (ref) {
                // strb Wt, [Xn, #imm12] — 字节访问，imm12 不缩放
                Ref.STRB -> if (insn and 0xFFC00000.toInt() == 0x39000000) {
                    val base = page[(insn ushr 5) and 0x1F]
                    if (base != 0L) return base + ((insn ushr 10) and 0xFFF)
                }

                // ldr Wt, [Xn, #imm12] — 32 位访问，imm12 以 4 为单位
                Ref.LDR_W -> if (insn and 0xFFC00000.toInt() == 0xB9400000.toInt()) {
                    val base = page[(insn ushr 5) and 0x1F]
                    if (base != 0L) return base + ((insn ushr 10) and 0xFFF) * 4L
                }
            }
        }
        return 0L
    }

    /**
     * [start] 起 [bytes] 字节内前 [limit] 个**本地函数**调用的目标地址。
     *
     * 跳过 PLT 桩：这里要找的是同一个 so 里的普通函数，而 `bl` 也会指向导入符号的桩。
     * 判据取正面的那个 —— 目标首指令是不是标准的 `stp x29, x30, [sp, #imm]!` 序言。
     * PLT 桩以 `adrp x16` 开头，不会误收。
     */
    fun findLocalCalls(start: Long, bytes: Int, limit: Int): List<Long> {
        val code = NativeHook.readMemory(start, bytes) ?: return emptyList()
        val found = ArrayList<Long>(limit)
        for (offset in 0 until code.size - 3 step 4) {
            val insn = code.insn(offset)
            if (insn and 0xFC000000.toInt() != 0x94000000.toInt()) continue   // bl imm26
            val target = start + offset + (signExtend((insn and 0x3FFFFFF).toLong(), 26) shl 2)
            if (!hasFramePrologue(target)) continue
            found += target
            if (found.size == limit) break
        }
        return found
    }

    /** 目标处第一条指令是不是 `stp x29, x30, [sp, #imm]!`。 */
    private fun hasFramePrologue(address: Long): Boolean {
        val head = NativeHook.readMemory(address, 4) ?: return false
        // 1010100110 imm7 Rt2=30 Rn=31 Rt=29  →  0xA980_0000 | imm7<<15 | 0x7BFD
        return head.insn(0) and 0xFFC07FFF.toInt() == 0xA9807BFD.toInt()
    }

    /** 小端取一条 32 位指令。 */
    private fun ByteArray.insn(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun signExtend(value: Long, bits: Int): Long {
        val shift = 64 - bits
        return (value shl shift) shr shift
    }
}
