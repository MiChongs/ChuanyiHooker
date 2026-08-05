package com.chuanyi.hooker.hookers.poweramp

import android.util.Base64
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

/**
 * 设置的导出与导入。
 *
 * ## 这是原生侧唯一一处真正设卡的地方
 *
 * 导出文件不是普通 zip：应用先把 zip 转成 base64，再交给原生引擎加密，密文才落盘。
 * 导入是反过来的一步。两步都走 `Sync`：
 *
 * ```
 * 导出  zip → base64 → native "eS" → 文件
 * 导入  文件 → native "dS" → base64 → zip
 * ```
 *
 * 实测（trial 状态，且授权已在 Java 侧按成完整版）：
 *
 * | 命令 | 结果 |
 * |---|---|
 * | `eS` 加密 | 正常返回密文 |
 * | `dS` 解密 | **对任何输入都返回 `error 0x1`** —— 包括它自己上一秒刚加密出来的密文 |
 *
 * 对空串、乱码、截断密文、合法密文，返回的都是同一句 `error 0x1`，说明它在碰输入之前
 * 就拒绝了 —— 是前置的授权门，不是格式校验。这和应用自己的文案对得上：导入设置前会
 * 提示「需要经过验证的完整版」。同一时刻 `ps`（授权状态文本）仍然返回「限时试用」，
 * 说明**原生引擎保留着自己那份授权状态**，Java 侧按成 272 影响不到它。
 *
 * ## 为什么不去解那把锁
 *
 * 密文结构是「32 字节固定头 + 16 字节分组」，同一段明文每次加密结果完全相同，
 * 相同的前 16 字节明文产出相同的密文块 —— 是 ECB。手上只有加密预言机，
 * 没有解密预言机，ECB 又不像流密码那样能用「加密全零求密钥流」的办法反推。
 * 而那个授权门在 `libpowerampcore.so` 里，那个库连 `dS` 这两个字母和 `error 0x`
 * 都不是明文（全库扫不到，运行时才拼出来），要找到那一条分支得先解它的字符串层。
 *
 * 代价与收益不成比例：真正要的是「导出的东西自己能导回来」，那在 Java 这一层
 * 干净得多 —— 加解密的调用点是两个 `(byte[]) -> byte[]` 的静态方法，绕过去即可。
 *
 * ## 绕过之后的格式
 *
 * 导出写的是 `PACY1\n` + base64(zip)。标记头后面是干净的 base64，去掉第一行用任何
 * 工具都能解开成 zip —— 「放开加密」要的正是这个：文件可读、可改、可跨设备。
 *
 * 导入三条路依次试，命中即止：
 *
 * 1. 带标记头 → 去头，base64 解开
 * 2. 没有标记头 → 交给原生解密（已购买的机器上这条仍然有效，能读老的加密备份）
 * 3. 原生拒绝 → 把整个文件当 base64 再试一次（有人手工去掉了标记头）
 *
 * 每一步都用文件头验证结果：这条链上解出来的只可能是 zip 或 SQLite 数据库，
 * 两个都不是就当作失败返回 `null`，让应用照常报「导入失败」——
 * 比把一段垃圾塞进数据库强。
 *
 * ## 代价
 *
 * 导出的文件**未打补丁的 Poweramp 读不了**（它只会把标记头喂给原生解密，然后拿到
 * `error`）。这是必然的：能被原生读回去的密文只有原生自己能造，而造完了它也不肯读。
 * 反方向没有损失 —— 上面第 2 条保留了原生解密这条路。
 */
internal object SettingsBackup {

    /**
     * 明文导出的标记头。
     *
     * 用换行收尾，于是标记之后剩下的是**干净的 base64**：`tail -n +2 file | base64 -d > x.zip`
     * 就能拿到 zip，不需要任何工具认识这个格式。
     */
    private const val MAGIC = "PACY1\n"

    /** 这条链上解出来的东西只有两种，用文件头认。 */
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val SQLITE = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    fun HookScope.installPlainBackup(refs: PowerampDex.Refs) {
        val encrypt = refs.encrypt ?: error("没定位到导出加密方法")
        val decrypt = refs.decrypt ?: error("没定位到导入解密方法")

        encrypt.createBeforeHook("poweramp.backup.export") { param ->
            val payload = param.args.getOrNull(0) as? ByteArray ?: return@createBeforeHook
            param.result = (MAGIC + Base64.encodeToString(payload, Base64.NO_WRAP))
                .toByteArray(Charsets.UTF_8)
            log.i("已导出 ${payload.size} 字节（未加密）")
        }

        // 第 1 条：带标记头的，根本不进原生。
        decrypt.createBeforeHook("poweramp.backup.import") { param ->
            val raw = param.args.getOrNull(0) as? ByteArray ?: return@createBeforeHook
            val marked = decodeMarked(raw) ?: return@createBeforeHook
            param.result = marked
            log.i("已导入 ${marked.size} 字节（未加密）")
        }

        // 第 3 条：原生拒绝时的兜底。第 2 条就是原方法本身。
        decrypt.createAfterHook("poweramp.backup.import.fallback") { param ->
            if ((param.result as? ByteArray)?.isPayload() == true) return@createAfterHook
            val raw = param.args.getOrNull(0) as? ByteArray ?: return@createAfterHook
            val loose = decodeLoose(raw)
            param.result = loose
            if (loose != null) {
                log.i("原生解密未通过，按未加密解出 ${loose.size} 字节")
            } else {
                log.w("这个文件既不是未加密备份，原生解密也没放行")
            }
        }

        log.i("导出改为不加密，导入同时接受加密与未加密的文件")
    }

    /** 排查用：只报每次进出的大小与判定，不改行为。 */
    fun HookScope.installBackupLog(refs: PowerampDex.Refs) {
        refs.encrypt?.createAfterHook("poweramp.backup.log.export") { param ->
            val input = (param.args.getOrNull(0) as? ByteArray)?.size ?: -1
            val output = (param.result as? ByteArray)?.size ?: -1
            log.i("导出：$input 字节 -> $output 字节")
        }
        refs.decrypt?.createAfterHook("poweramp.backup.log.import") { param ->
            val input = (param.args.getOrNull(0) as? ByteArray)?.size ?: -1
            val result = param.result as? ByteArray
            log.i(
                "导入：$input 字节 -> ${result?.size ?: -1} 字节" +
                    "，${if (result?.isPayload() == true) "有效" else "无效"}",
            )
        }
    }

    // -----------------------------------------------------------------------

    private fun decodeMarked(raw: ByteArray): ByteArray? {
        val head = MAGIC.toByteArray(Charsets.US_ASCII)
        if (raw.size <= head.size || !raw.startsWith(head)) return null
        return runCatching {
            Base64.decode(raw, head.size, raw.size - head.size, Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isPayload() }
    }

    private fun decodeLoose(raw: ByteArray): ByteArray? =
        runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isPayload() }

    private fun ByteArray.isPayload(): Boolean = startsWith(ZIP) || startsWith(SQLITE)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
