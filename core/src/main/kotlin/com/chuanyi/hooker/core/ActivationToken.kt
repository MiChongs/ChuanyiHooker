package com.chuanyi.hooker.core

/**
 * 令牌里那些**不是秘密**的字段怎么读。
 *
 * 令牌的机密性全部落在 MAC 上（密钥只存在于壳内代码解密后的匿名内存里），前 16 字节
 * 是明文的元数据。撤销链路要用其中一项 —— 签发方 —— 来回答「这枚令牌是不是我发的」，
 * 而这个问题在 Java 侧回答就够了：读错了也伪造不出有效令牌，最多是撤销打不中。
 *
 * ```
 * [0..3]   magic 'CYT1'
 * [4..7]   签发日          u32 LE
 * [8..11]  nonce           u32 LE
 * [12..15] 签发方包名哈希  u32 LE     <- 这里
 * [16..23] MAC             u64 LE
 * ```
 *
 * 布局与 `:native` 的 `cpp/payload/payload_abi.h` 一致，改一边就要改另一边。
 */
object ActivationToken {

    /** 令牌的 hex 长度。24 字节。 */
    const val HEX_LENGTH: Int = 48

    /** 签发方字段在令牌里的字节偏移。 */
    private const val SOURCE_HASH_OFFSET = 12

    /**
     * 包名的 FNV-1a（32 位）。
     *
     * 存哈希而不是包名，只是因为令牌要定长 —— 它不承担任何安全职责，碰撞的后果也
     * 只是「另一个客户端也能撤销这枚令牌」，而能走到那一步的前提是它已经能读到框架
     * 配置，也就是它本来就被注入了。
     *
     * Kotlin 的 `Int` 是 32 位环绕算术，位模式和 C 侧的 `uint32_t` 一致。
     */
    fun hashOf(packageName: String): Int {
        var hash = -2128831035 // 2166136261u
        for (index in packageName.indices) {
            hash = hash xor (packageName[index].code and 0xff)
            hash *= 16777619
        }
        return hash
    }

    /** 令牌里记的签发方哈希；格式不对返回 null。 */
    fun sourceHashOf(tokenHex: String?): Int? {
        if (tokenHex == null || tokenHex.length != HEX_LENGTH) return null
        var value = 0
        for (index in 0 until 4) {
            val at = (SOURCE_HASH_OFFSET + index) * 2
            val byte = tokenHex.substring(at, at + 2).toIntOrNull(16) ?: return null
            value = value or (byte shl (index * 8))
        }
        return value
    }

    /** [tokenHex] 是不是 [packageName] 签发的。 */
    fun issuedBy(tokenHex: String?, packageName: String): Boolean =
        sourceHashOf(tokenHex) == hashOf(packageName)
}
