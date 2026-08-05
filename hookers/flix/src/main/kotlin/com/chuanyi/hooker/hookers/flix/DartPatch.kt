package com.chuanyi.hooker.hookers.flix

import com.chuanyi.hooker.core.HookerLog
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * 往 Dart AOT 快照的代码段里打补丁。
 *
 * Flix 的会员判定一条都不在 Java 层 —— `libapp.so` 里 21 MB 的 Dart 机器码，
 * Xposed 够不着。好在这个包**没开 `--obfuscate`**：类名、函数名、
 * `package:flix/...` 的源文件路径全是明文，2270 条源码路径原样躺在里面，
 * 判定链是照着读出来的，不是猜的。
 *
 * ## 和 capyplayer 那版的区别：改的是指令，不是入口
 *
 * capyplayer 的落点都是「让这个函数恒返回 true」，补入口两条指令就完事。Flix 不行 ——
 * 它的总闸 `VipService._setIsFlixMax` 是个 **setter**：把它整个短路掉，字段就永远
 * 不会被写，反而锁死在 false。这里要的是「让它写进去的值变成 true」，落点必须落在
 * **函数体内部**的某一条指令上。
 *
 * 所以 [Site] 带 [Site.patchOffset]：锚点负责找到函数入口，补丁落在入口加上一个
 * 固定偏移的地方，改写的可能只是一条 4 字节指令。[EntryReturn] 那种「补入口」的老
 * 用法只是 `patchOffset = 0` 的特例。
 *
 * ## 为什么是代码补丁而不是 inline hook
 *
 * Dart AOT 用它自己的寄存器约定（`x15` 是栈指针、`x22` 常驻 `null`、`x27` 是对象池、
 * `x26` 是 thread），和 AAPCS64 对不上。Dobby 的 replacement 是普通 C 函数，进去就
 * 看不见 `x22`，而这里每一处判定的真假**恰恰是从 `x22` 算出来的**（见下）。
 *
 * 直接改指令则完全活在目标自己的世界里：用的寄存器是它的，值也是它的，我们只是把一条
 * 比较的右操作数换掉。
 *
 * ## `x22 + 0x20` 为什么是 `true`
 *
 * Dart VM 在 arm64 上把 `null` 常驻 `NULL_REG`（`x22`），`true`/`false` 两个单例紧跟
 * 在 `null` 后面，偏移由 `kObjectAlignment`(16) 决定：`true = null + 0x20`、
 * `false = null + 0x30`。
 *
 * 目标里两个方向都印证过。`VipService.syncFromPrefs` 读完本地剩余天数之后：
 *
 * ```
 * cmp  x1, #0x0
 * add  x16, x22, #0x20          ; true
 * add  x17, x22, #0x30          ; false
 * csel x0, x16, x17, gt         ; 天数 > 0 ? true : false
 * bl   VipService._setIsFlixMax
 * ```
 *
 * 「天数为正走 `0x20`」和别处 `tbnz w0, #4`（`0x20` 的 bit4=0、`0x30` 的 bit4=1）在判
 * bool，两件事互相印证，不是从 VM 源码推出来的孤证。
 *
 * ## 压缩指针：为什么 32 位比较是对的
 *
 * 目标跑在压缩指针模式下 —— 对象引用在字段里只占 4 字节，还原成完整地址要
 * `add xN, xN, x28, lsl #32`（`x28` 存 heap base 的高 32 位）。反过来说，**heap base
 * 的低 32 位是 0**，于是「完整地址的低 32 位」和「压缩指针」永远相等。
 *
 * 这就是 [Entitlement] 里那处 4 字节补丁成立的前提：把 `cmp w0, w2` 的右操作数换成
 * `x22 + 0x20` 之后，比的是 `w2`（低 32 位），正好等于 `true` 的压缩形式，和左边那个从
 * 字段里读出来的压缩指针是同一把尺子。
 *
 * ## 定位为什么用特征码
 *
 * Dart AOT 的代码段是一整块没有符号的匿名机器码，`FindSymbol` 无从下手，落点只能用地
 * 址表示 —— 而写死地址活不过目标的下一次发版。改用**函数体里的一段字节**做锚点：重新
 * 编译会挪动所有地址，但只要那段代码本身没被改，指令序列原样保留。
 *
 * 锚点一律**不覆盖要改写的那几个字节**。重叠的话补丁一落锚点就没了，[verify] 再也校
 * 验不了；多站点批量应用时还会因为「第 n 个命中」错位而写到别处去。
 */
internal object DartPatch {

    /** Dart 快照所在的库。Flutter 引擎自己是 `libflutter.so`，与这里无关。 */
    const val IMAGE = "libapp.so"

    // --- 指令常量（arm64，小端）------------------------------------------

    /**
     * Dart 函数序言：`stp x29, x30, [x15, #-0x10]!` + `mov x29, x15`。
     *
     * 用来确认锚点回退出来的地址真的落在函数入口上。Dart 用 `x15` 当栈指针，这个序言
     * 形状在整个快照里是统一的 —— 对不上就说明锚点偏移过时了，此时**宁可不打**也不能
     * 往函数中间写指令（那等于制造一个随机的跳转目标）。
     */
    private val PROLOGUE = byteArrayOf(
        0xFD.toByte(), 0x79, 0xBF.toByte(), 0xA9.toByte(),
        0xFD.toByte(), 0x03, 0x0F, 0xAA.toByte(),
    )

    /** `nop`。用来废掉一条不想要的条件跳转，保持后面所有偏移不变。 */
    val NOP = byteArrayOf(0x1F, 0x20, 0x03, 0xD5.toByte())

    /** `add x0, x22, #0x20` + `ret` —— 恒返回 Dart 的 `true`。 */
    val RETURN_TRUE = byteArrayOf(
        0xC0.toByte(), 0x82.toByte(), 0x00, 0x91.toByte(), // add x0, x22, #0x20
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),         // ret
    )

    /** `mov x0, x22` + `ret` —— 恒返回 `null`，即同步 `void` 函数的正常出口。 */
    val RETURN_NULL = byteArrayOf(
        0xE0.toByte(), 0x03, 0x16, 0xAA.toByte(),         // mov x0, x22
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),         // ret
    )

    // --- 指令编码 ---------------------------------------------------------

    // 这几个 opcode 都 >= 0x80000000。Kotlin 会把这样的字面量推成 Long，直接参与
    // Int 运算编译不过 —— 统一在这里收窄一次，下面就都是 Int 了。
    private const val OP_ADD_IMM = 0x91000000.toInt()
    private const val OP_MOVZ = 0xD2800000.toInt()
    private const val OP_MOVK = 0xF2800000.toInt()
    private const val OP_B = 0x14000000

    /** `add Xd, Xn, #imm12`（64 位，不移位）。 */
    fun addImm(rd: Int, rn: Int, imm12: Int): ByteArray {
        require(imm12 in 0..0xFFF) { "imm12 越界：$imm12" }
        return le32(OP_ADD_IMM or (imm12 shl 10) or (rn shl 5) or rd)
    }

    /** `add Xd, x22, #0x20` —— 把 Dart 的 `true` 放进 Xd。 */
    fun loadTrue(rd: Int): ByteArray = addImm(rd, NULL_REG, 0x20)

    /**
     * 无条件 `b`，相对本条指令的字节偏移。
     *
     * 用来把一条条件跳转改成必跳 —— 保留原目标地址，只是不再问条件。
     */
    fun branch(byteOffset: Int): ByteArray {
        require(byteOffset % 4 == 0) { "跳转偏移必须 4 字节对齐：$byteOffset" }
        val imm26 = byteOffset / 4
        require(imm26 in -(1 shl 25) until (1 shl 25)) { "跳转偏移越界：$byteOffset" }
        return le32(OP_B or (imm26 and 0x03FFFFFF))
    }

    /**
     * `movz/movk Xd, #value` + `ret` —— 恒返回一个 Dart 整数。
     *
     * 返回的是 **Smi**（small integer）：Dart 把小整数直接编码进指针里，`值 << 1`，最
     * 低位 0 作为「这不是堆对象」的标记。所以要返回整数 n，寄存器里得放 `n * 2`。
     *
     * 按 [value] 实际用到的 16 位块数量生成 `movz` + 若干 `movk`，用不到的块不发指令。
     */
    fun returnInt(value: Long): ByteArray {
        require(value >= 0) { "只支持非负数：$value" }
        val smi = value shl 1
        require(smi ushr 62 == 0L) { "超出 Smi 范围：$value" }

        // 非零的 16 位块。全零（value == 0）时退化成单条 movz x0, #0。
        val chunks = (0..3)
            .map { shift -> shift to ((smi ushr (shift * 16)) and 0xFFFFL).toInt() }
            .filter { (_, chunk) -> chunk != 0 }
            .ifEmpty { listOf(0 to 0) }

        val out = java.io.ByteArrayOutputStream(4 * chunks.size + 4)
        chunks.forEachIndexed { index, (shift, chunk) ->
            val op = if (index == 0) OP_MOVZ else OP_MOVK
            out.write(le32(op or (shift shl 21) or (chunk shl 5)))
        }
        out.write(byteArrayOf(0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte())) // ret
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /** Dart 在 arm64 上常驻 `null` 的寄存器。`true`/`false` 由它加固定偏移得到。 */
    const val NULL_REG = 22

    // --- 落点 -------------------------------------------------------------

    /**
     * 一个补丁落点。
     *
     * @param id           日志与错误里用的短名
     * @param dartName     快照里的原始函数名，只为可读性
     * @param anchor       函数体里的一段字节，十六进制。必须在全库唯一，且**不与
     *                     [patchOffset] 起算的那几个字节重叠**
     * @param anchorOffset anchor 相对函数入口的偏移；函数入口 = 命中地址 - 这个值
     * @param patchOffset  补丁相对函数入口的偏移。0 表示改写入口本身
     * @param payload      写进去的字节。延迟求值，因为有的站点要按设置项现算
     * @param note         这一处改了之后发生什么，写进日志
     */
    data class Site(
        val id: String,
        val dartName: String,
        val anchor: String,
        val anchorOffset: Int,
        val patchOffset: Int,
        val note: String,
        val payload: () -> ByteArray,
    ) {
        val anchorBytes: ByteArray by lazy { anchor.hexToBytes() }

        /**
         * 锚点与补丁重叠 = 打完补丁锚点就没了，之后再也校验不了，多站点批量应用时还会
         * 按「第 n 个命中」错位写到别处。这是设计约束，不是运行期状况 —— 站点表写错了
         * 就该当场抛，不要降级成一条日志。
         *
         * [payload] 有的要按设置项现算，所以校验放在真正拿到字节之后，而不是构造期。
         */
        fun checkNoOverlap(payloadSize: Int) {
            val patchEnd = patchOffset + payloadSize
            val anchorEnd = anchorOffset + anchorBytes.size
            require(patchEnd <= anchorOffset || patchOffset >= anchorEnd) {
                "$id：锚点 [$anchorOffset,$anchorEnd) 与补丁 [$patchOffset,$patchEnd) 重叠"
            }
        }
    }

    /** 一次定位的结果。[entry] 为 0 表示没找到。 */
    data class Located(val site: Site, val entry: Long, val matches: Int) {
        val target: Long get() = if (entry == 0L) 0L else entry + site.patchOffset
    }

    /**
     * 找到 [site] 的函数入口。
     *
     * 三道关卡，任何一道不过都返回 `entry = 0`，让调用方跳过这个站点而不是硬写：
     * 锚点必须找得到、必须唯一、回退出来的地址必须是函数序言。
     */
    fun locate(site: Site, log: HookerLog): Located {
        val matches = NativeHook.countPattern(IMAGE, site.anchorBytes)
        if (matches <= 0) {
            log.w("${site.id}：特征码在 $IMAGE 里找不到（${site.dartName}）—— 目标版本大概换了")
            return Located(site, 0L, matches)
        }
        if (matches > 1) {
            // 唯一性是这个锚点能用的前提。命中多个说明它不再是标识符，随便挑一个就是在
            // 赌，不如报出来重新取特征。
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
                "${site.id}：${entry.hex()} 处不是函数序言（读到 ${head?.hex() ?: "null"}）—— " +
                    "锚点偏移 ${site.anchorOffset} 已经不对，跳过",
            )
            return Located(site, 0L, matches)
        }
        return Located(site, entry, matches)
    }

    /**
     * 打补丁并回读校验。
     *
     * `patchMemory` 走的是 `DobbyCodePatch`，它自己处理页权限和 icache；这里只负责确认
     * 写进去的确实是要写的东西 —— 页保护恢复失败之类的问题在回读时才看得出来。
     */
    fun apply(located: Located, log: HookerLog): Boolean {
        val site = located.site
        if (located.entry == 0L) return false
        val payload = site.payload()
        site.checkNoOverlap(payload.size)
        val at = located.target

        // 已经是目标形状（同一进程里重复安装，或热重载走了第二遍）就别再写一次。
        val before = NativeHook.readMemory(at, payload.size)
        if (before != null && before.contentEquals(payload)) {
            log.d("${site.id}：${at.hex()} 已是补丁状态，跳过")
            return true
        }

        if (!NativeHook.patchMemory(at, payload)) {
            log.e("${site.id}：写 ${at.hex()} 失败")
            return false
        }
        val after = NativeHook.readMemory(at, payload.size)
        if (after == null || !after.contentEquals(payload)) {
            log.e("${site.id}：写完回读对不上（${after?.hex() ?: "null"}）")
            return false
        }
        log.i("${site.id}：${at.hex()} ← ${payload.hex()}　${site.note}")
        return true
    }

    /** 定位加打补丁，一步到位。 */
    fun install(site: Site, log: HookerLog): Boolean = apply(locate(site, log), log)

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
