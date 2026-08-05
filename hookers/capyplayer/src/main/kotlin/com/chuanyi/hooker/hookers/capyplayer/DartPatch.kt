package com.chuanyi.hooker.hookers.capyplayer

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * 往 Dart AOT 快照的代码段里打补丁。
 *
 * CapyPlayer 的权益判定一条都不在 Java 层 —— `libapp.so` 里是 Dart 编译出来的
 * 机器码，Xposed 够不着。好在这个包**没开 `--obfuscate`**，函数名、类名、
 * `package:capyplayer/...` 的源文件路径全是明文，判定链是照着读出来的而不是猜的。
 *
 * ## 为什么是代码补丁而不是 inline hook
 *
 * Dart AOT 用它自己的寄存器约定（`x15` 是栈指针、`x22` 常驻 `null`、`x27` 是对象
 * 池、`x26` 是 thread），和 AAPCS64 对不上。Dobby 的 replacement 是普通 C 函数，
 * 拿不到 `x22`，而**返回值恰恰要从 `x22` 算**（见下）。
 *
 * 而这里需要的行为只是「恒定返回某个值」，两条指令就够：
 *
 * ```
 * add x0, x22, #0x20   ; x0 = true
 * ret
 * ```
 *
 * 落在函数入口，此时栈帧还没建（第一条指令 `stp x29,x30,[x15,#-0x10]!` 尚未执行）、
 * 返回地址还在 `x30` 里，直接 `ret` 不会留下任何不平衡。用的 `x22` 是目标进程自己
 * 的，不需要我们知道它的值。
 *
 * ## `x22 + 0x20` 为什么是 `true`
 *
 * Dart VM 在 arm64 上把 `null` 常驻 `NULL_REG`（`x22`），`true`/`false` 两个单例
 * 紧跟在 `null` 后面，偏移由 `kObjectAlignment`(16) 决定：`true = null + 0x20`、
 * `false = null + 0x30`。
 *
 * 目标里两个方向都印证过。`PaywallGuard|ensureEntitled` 读完 provider 之后：
 *
 * ```
 * bl   ConsumerStatefulElement.read
 * tbnz w0, #4, <无权益分支>      ; 0x20 的 bit4=0，0x30 的 bit4=1 —— 这条就是在判 bool
 * add  x0, x22, #0x20            ; 有权益 → 返回 true
 * ret
 * <无权益分支>:
 * bl   AppNavigation|goToSubscription
 * add  x0, x22, #0x30            ; → 返回 false
 * ```
 *
 * 「bit4 为 1 的那个走进跳订阅页的分支」和「`0x20` 走放行分支」互相印证，不是从
 * VM 源码推的孤证。
 *
 * ## 只能补同步函数
 *
 * `async` 函数返回的是 `Future`，函数体里那些 `add x0,x22,#0x30` 是喂给 completer
 * 的值，不是返回值。把这种函数改成直接返回 `true`，调用方会拿一个 bool 当 Future
 * 用，立刻崩。所以 [Site] 只收同步函数 —— `IAPService.initialize` 就是因为这条被
 * 排除的（它是 `Future<bool>`）。
 *
 * ## 定位为什么用特征码
 *
 * Dart AOT 的代码段是一整块没有符号的匿名机器码，`FindSymbol` 无从下手，落点只能
 * 用地址表示 —— 而写死地址活不过目标的下一次发版。改用**函数体里的一段字节**做锚
 * 点：重新编译会挪动所有地址，但只要那段代码本身没被改，指令序列原样保留。
 *
 * 锚点一律取自函数体中段，**不覆盖入口那 8 个字节**。重叠的话，补丁打完锚点就没
 * 了，[verify] 再也校验不了，多站点批量应用时还会因为「第 n 个命中」错位而写到别
 * 处去。
 */
internal object DartPatch {

    /** Dart 快照所在的库。Flutter 引擎自己是 `libflutter.so`，与这里无关。 */
    const val IMAGE = "libapp.so"

    // --- 指令常量（arm64，小端）------------------------------------------

    /** `add x0, x22, #0x20` + `ret` —— 恒返回 Dart 的 `true`。 */
    private val RETURN_TRUE = byteArrayOf(
        0xC0.toByte(), 0x82.toByte(), 0x00, 0x91.toByte(), // add x0, x22, #0x20
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),         // ret
    )

    /** `mov x0, x22` + `ret` —— 恒返回 `null`，即同步 `void` 函数的正常出口。 */
    private val RETURN_NULL = byteArrayOf(
        0xE0.toByte(), 0x03, 0x16, 0xAA.toByte(),         // mov x0, x22
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),         // ret
    )

    /**
     * Dart 函数序言：`stp x29, x30, [x15, #-0x10]!` + `mov x29, x15`。
     *
     * 用来确认锚点回退出来的地址真的落在函数入口上。Dart 用 `x15` 当栈指针，这个
     * 序言形状在整个快照里是统一的 —— 对不上就说明 delta 过时了，此时**宁可不打**
     * 也不能往函数中间写两条指令（那等于制造一个随机的跳转目标）。
     */
    private val PROLOGUE = byteArrayOf(
        0xFD.toByte(), 0x79, 0xBF.toByte(), 0xA9.toByte(),
        0xFD.toByte(), 0x03, 0x0F, 0xAA.toByte(),
    )

    /** 打完补丁后长什么样，取决于站点补的是 true 还是 null。 */
    enum class Result { TRUE, NULL }

    /**
     * 一个补丁落点。
     *
     * @param id            日志与错误里用的短名
     * @param dartName      快照里的原始函数名，只为可读性
     * @param anchor        函数体里的一段字节，十六进制。必须在全库唯一，且不与入口
     *                      那 8 字节重叠
     * @param anchorOffset  anchor 相对函数入口的偏移；函数入口 = 命中地址 - 这个值
     * @param result        让它恒返回什么
     */
    data class Site(
        val id: String,
        val dartName: String,
        val anchor: String,
        val anchorOffset: Int,
        val result: Result,
    ) {
        val anchorBytes: ByteArray by lazy { anchor.hexToBytes() }
        val payload: ByteArray get() = if (result == Result.TRUE) RETURN_TRUE else RETURN_NULL
    }

    /** 一次定位的结果。[address] 为 0 表示没找到。 */
    data class Located(val site: Site, val address: Long, val matches: Int)

    /**
     * 找到 [site] 的函数入口。
     *
     * 三道关卡，任何一道不过都返回 `address = 0`，让调用方跳过这个站点而不是硬写：
     * 锚点必须找得到、必须唯一、回退出来的地址必须是函数序言。
     */
    fun locate(site: Site, log: HookerLog): Located {
        val matches = NativeHook.countPattern(IMAGE, site.anchorBytes)
        if (matches <= 0) {
            log.w("${site.id}：特征码在 $IMAGE 里找不到（${site.dartName}）—— 目标版本大概换了")
            return Located(site, 0L, matches)
        }
        if (matches > 1) {
            // 唯一性是这个锚点能用的前提。命中多个说明它不再是标识符，随便挑一个
            // 就是在赌，不如报出来重新取特征。
            log.w("${site.id}：特征码命中 $matches 处，不唯一，跳过")
            return Located(site, 0L, matches)
        }

        val anchorAt = NativeHook.findPattern(IMAGE, site.anchorBytes)
        if (anchorAt == 0L) {
            log.w("${site.id}：计数说有、取地址却拿不到，跳过")
            return Located(site, 0L, matches)
        }

        val entry = anchorAt - site.anchorOffset
        val head = NativeHook.readMemory(entry, PROLOGUE.size)
        if (head == null || !head.contentEquals(PROLOGUE)) {
            log.w(
                "${site.id}：${entry.hex()} 处不是函数序言（读到 ${head?.hex() ?: "null"}）——" +
                    "锚点偏移 ${site.anchorOffset} 已经不对，跳过",
            )
            return Located(site, 0L, matches)
        }
        return Located(site, entry, matches)
    }

    /**
     * 打补丁并回读校验。
     *
     * `patchMemory` 走的是 `DobbyCodePatch`，它自己处理页权限和 icache；这里只负责
     * 确认写进去的确实是要写的东西 —— 页保护恢复失败之类的问题在回读时才看得出来。
     */
    fun apply(located: Located, log: HookerLog): Boolean {
        val site = located.site
        if (located.address == 0L) return false

        // 已经是目标形状（同一进程里重复安装，或热重载走了第二遍）就别再写一次。
        val before = NativeHook.readMemory(located.address, site.payload.size)
        if (before != null && before.contentEquals(site.payload)) {
            log.d("${site.id}：${located.address.hex()} 已是补丁状态，跳过")
            return true
        }

        if (!NativeHook.patchMemory(located.address, site.payload)) {
            log.e("${site.id}：写 ${located.address.hex()} 失败")
            return false
        }
        val after = NativeHook.readMemory(located.address, site.payload.size)
        if (after == null || !after.contentEquals(site.payload)) {
            log.e("${site.id}：写完回读对不上（${after?.hex() ?: "null"}）")
            return false
        }
        log.i("${site.id}：${located.address.hex()} → 恒返回 ${site.result.label()}（${site.dartName}）")
        return true
    }

    private fun Result.label() = if (this == Result.TRUE) "true" else "null"

    private fun Long.hex() = "0x${java.lang.Long.toHexString(this)}"

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val clean = filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex 长度必须是偶数：$this" }
        return ByteArray(clean.length / 2) {
            ((clean[it * 2].digit() shl 4) or clean[it * 2 + 1].digit()).toByte()
        }
    }

    private fun Char.digit(): Int = Character.digit(this, 16).also {
        require(it >= 0) { "不是十六进制字符：$this" }
    }
}
