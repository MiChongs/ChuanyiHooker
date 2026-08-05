package com.chuanyi.hooker.hookers.cellularpro

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * 改被解释执行的那段字节码，而不是改代码段。
 *
 * ## 为什么绕这么一圈
 *
 * 目标对自己的代码段做完整性校验。三条常规路子都试过，都会被它发现并导致进程在十几秒内
 * 死掉（表现分别是 SIGSYS、SIGSEGV，退出记录里是 `reason=2 (SIGNALED)`）：
 *
 * | 做法 | 动了什么 | 结果 |
 * |---|---|---|
 * | ART 级 hook | Java 调用栈多一帧 | 会员记录构造器里的调用方校验当场发现 |
 * | `RegisterNatives` 换函数指针 | 绑定地址落到模块的 `.so` 里 | 它回查绑定是否仍在自己模块内 |
 * | Dobby 原地 inline hook | 目标 `.text` 被写入 | 代码段校验不过 |
 *
 * 但这些方法的**方法体根本不在代码段里** —— nmmp 把它们编译成了一段字节码放在
 * `.rodata`，运行时由 `libkotlin.so` 导出的解释器执行，`.text` 里留下的只是一个
 * 「取字节码指针 + 调解释器」的桩。所以把那段字节码改掉，代码段一个字节都不用动，
 * 注册表里的函数指针也保持原样。
 *
 * ## 桩长什么样
 *
 * ```
 * adrp x2, <解释器分发表页>      ; 落在 .data.rel.ro，RVA 高
 * add  x2, x2, #off
 * add  x1, sp, #8
 * adrp x8, <字节码页>            ; 落在 .rodata，RVA 低  ← 要的是这个
 * add  x8, x8, #off
 * str  x8, [sp, #8]
 * mov  w8, #<长度>
 * str  w8, [sp, #0x10]
 * ...
 * bl   vmInterpret
 * ```
 *
 * 两个 `adrp`+`add` 对里，**RVA 小的那个是字节码**（`.rodata` 在 `.data.rel.ro` 前面），
 * 不用写死任何偏移就能分辨。
 *
 * ## 要写进去的字节
 *
 * 从目标自己那些「恒真 / 恒假」的方法里抄的现成写法 —— 它自己就有几个方法体正好是
 * 这四个字节，所以这不是我们发明的编码：
 *
 * ```
 * 97 10   const v0, #1     (高半字节是值，低半字节是寄存器号)
 * e1 00   return v0
 * ```
 *
 * 只写 4 字节，桩里那个长度字段不用动：解释器遇到 `return` 就停，后面剩下的旧字节
 * 永远执行不到。
 */
internal object VmCode {

    /** `const v0, #1` + `return v0`。 */
    private val RETURN_TRUE = byteArrayOf(0x97.toByte(), 0x10, 0xe1.toByte(), 0x00)

    /** `const v0, #0` + `return v0`。 */
    private val RETURN_FALSE = byteArrayOf(0x97.toByte(), 0x00, 0xe1.toByte(), 0x00)

    /** 桩里前若干条指令足够覆盖两个 `adrp`+`add` 对。 */
    private const val THUNK_SCAN_BYTES = 160

    /**
     * 把 [thunk] 这个 JNI 桩背后的方法体改成「返回 [value]」。
     *
     * @param thunk `RegisterNatives` 绑定的那个地址，由
     *   [NativeHook.jniRegistrationAddress] 给出
     * @return 成功与否；失败时 [log] 里有原因
     */
    fun forceReturn(log: HookerLog, name: String, thunk: Long, value: Boolean): Boolean {
        val moduleBase = NativeHook.moduleBase(TARGET_LIBRARY)
        if (moduleBase == 0L) {
            log.e("$TARGET_LIBRARY 没有加载，改不了 $name")
            return false
        }
        val body = findBodyAddress(thunk, moduleBase) ?: run {
            log.e("$name 的桩里没找到字节码指针（0x${thunk.toString(16)}）")
            return false
        }
        val want = if (value) RETURN_TRUE else RETURN_FALSE
        val before = NativeHook.readMemory(body, want.size)
        if (before != null && before.contentEquals(want)) return true // 已经是想要的样子

        if (!NativeHook.writeMemory(body, want)) {
            log.e("$name 的字节码写不进去 @ 0x${body.toString(16)}")
            return false
        }
        return true
    }

    /**
     * 从桩里解出字节码地址：扫 `adrp`+`add` 对，取落在目标模块内、**RVA 最小**的那个。
     *
     * 解释器分发表在 `.data.rel.ro`，字节码在 `.rodata`，后者地址一定更低，所以按
     * RVA 取小即可，不必写死任何段边界。
     */
    private fun findBodyAddress(thunk: Long, moduleBase: Long): Long? {
        val code = NativeHook.readMemory(thunk, THUNK_SCAN_BYTES) ?: return null
        val pending = HashMap<Int, Long>()
        var best: Long? = null

        for (offset in 0 until code.size - 3 step 4) {
            val w = (code[offset].toLong() and 0xff) or
                ((code[offset + 1].toLong() and 0xff) shl 8) or
                ((code[offset + 2].toLong() and 0xff) shl 16) or
                ((code[offset + 3].toLong() and 0xff) shl 24)
            val pc = thunk + offset

            // ADRP: bit31 = op, bits 28..24 = 10000
            if (((w ushr 24) and 0x9f) == 0x90L) {
                val rd = (w and 0x1f).toInt()
                val immLow = (w ushr 29) and 3
                val immHigh = (w ushr 5) and 0x7ffff
                var imm = (immHigh shl 2) or immLow
                if (imm and 0x100000L != 0L) imm -= 0x200000L      // 21 位有符号
                pending[rd] = (pc and 0xfffL.inv()) + imm * 4096L
                continue
            }
            // ADD（立即数，64 位）: sf=1, bits 28..23 = 100010
            if (((w ushr 23) and 0x1ff) == 0x122L) {
                val rd = (w and 0x1f).toInt()
                val rn = ((w ushr 5) and 0x1f).toInt()
                val page = pending.remove(rn) ?: continue
                if (rd != rn) continue
                val target = page + ((w ushr 10) and 0xfff)
                if (target <= moduleBase) continue
                if (best == null || target < best!!) best = target
            }
        }
        return best
    }

    /** 字节码与桩都在这个库里。名字是伪装的，实际是 nmmp 的产物容器。 */
    private const val TARGET_LIBRARY = "libQualcommAdapter.so"
}
