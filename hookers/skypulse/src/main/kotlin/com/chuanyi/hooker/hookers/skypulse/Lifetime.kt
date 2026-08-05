package com.chuanyi.hooker.hookers.skypulse

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.skypulse.SkyPulseDex.ownerInstance
import com.chuanyi.hooker.nativehook.NativeHook
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.json.JSONObject

/**
 * 永久授权凭证：把目标验签用的公钥换成模块自己持有私钥的那一把，然后签一张
 * 永不过期的 JWT 写进目标自己的加密存储。
 *
 * 3.5.49 的授权是服务端签发的 RSA JWT，私钥在服务端，伪造不了 —— 但**验签的那一端
 * 在本地**，公钥就明文躺在 dex 里。换掉它，目标自己那套判定逻辑就会从头到尾为真：
 * JWT 真实存在、验签真的过、`device_id` 真的等于本机、`exp` 真的是 0。
 *
 * 这跟「hook 判定函数返回 true」不是同一件事。后者是在最后一道门上说谎，好处是零副作用、
 * 关掉就恢复；前者让目标的**内部状态自洽**，激活时间、续签逻辑、设置页文案、小组件渲染
 * 全都走各自的正常分支，不存在「某处忘了 hook」的漏网口子。两个一起开就是双保险。
 *
 * 永久是 payload 里 `exp = 0` 的直接结果 —— 验签方法只在 `exp > 0` 时才比对当前时间，
 * 写 0 进去就是目标自己定义的「永不过期」，不是把到期时间推到很远。
 *
 * ## 公钥怎么换
 *
 * 两条腿，任一条成功即可，因为它们的失效方式不一样：
 *
 * 1. **[NativeHook] 在 ART 托管堆上等长替换** —— 不依赖任何类名方法名，只依赖
 *    `-----BEGIN PUBLIC KEY-----` 这个形状。R8 把类名方法名全换一遍也不影响它。
 *    ART 把全 ASCII 字符串压缩存储、内联在对象里，等长覆写既不动长度字段也不动对象头。
 * 2. **hook 目标的 `()PublicKey`** —— 靠 DexKit 定位，确定性强，且不受
 *    「`lazy` 已经求过值」影响。
 *
 * 第 1 条有个时序前提：必须赶在目标第一次验签之前。第 2 条没有这个前提，
 * 所以两条都留着 —— 一条管通用，一条管准。
 */
internal object Lifetime {

    /**
     * 模块自己的 RSA-2048 公钥，DER/X.509 的 base64。
     *
     * 之所以能原地换掉目标那把，是因为 RSA-2048 的 SubjectPublicKeyInfo 恒为 294 字节，
     * base64 之后恒为 392 字符，按 64 字符折行后 PEM 总长恒为 450 —— 跟目标那张一个字节不差。
     * 等长是 [NativeHook.replaceAscii] 的硬性前提，这里是天然满足而不是凑出来的。
     */
    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqJQMhQwuz84tIBwCVx3D" +
            "KLp8SDPLNds73hYjp9nC019VfgAgK2EegvqPPi6MKUcNengFi8mYhB4tF1EwOjK9" +
            "715S3Kb0Wwij19Zj8yN72Gfz/vKKXWXKnuyjHK4NsQn/PGKLckhHGZJM1n2yXWwx" +
            "Xq2tt0MaOMDvXrrPiH4Nqk1rRFlbsBpHO36zD6SexcmkNa9XboknoFzXoa2MaSKX" +
            "fl3Nh99W0K4+H1dhrdxxWgn9ePDirFjyTpC16AWjuVnPtKH6614hD8W0y7SfQvPS" +
            "xNel7OCnUoUqj1VTdP0HhiTGx0C3Ba20ubw2ZzNffh1s9Y4Gc45LY3vmFE3GTstz" +
            "gwIDAQAB"

    /** 配对的私钥，PKCS#8 的 base64。只用来签本机这一张凭证。 */
    private const val PRIVATE_KEY_B64 =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQColAyFDC7Pzi0g" +
            "HAJXHcMounxIM8s12zveFiOn2cLTX1V+ACArYR6C+o8+LowpRw16eAWLyZiEHi0X" +
            "UTA6Mr3vXlLcpvRbCKPX1mPzI3vYZ/P+8opdZcqe7KMcrg2xCf88YotySEcZkkzW" +
            "fbJdbDFera23Qxo4wO9eus+Ifg2qTWtEWVuwGkc7frMPpJ7FyaQ1r1duiSegXNeh" +
            "rYxpIpd+Xc2H31bQrj4fV2Gt3HFaCf148OKsWPJOkLXoBaO5Wc+0ofrrXiEPxbTL" +
            "tJ9C89LE16Xs4KdShSqPVVN0/QeGJMbHQLcFrbS5vDZnM19+HWz1jgZzjktje+YU" +
            "TcZOy3ODAgMBAAECggEAAqtphq0v4UGSFwk/2OeolgYHVCYMffCtV4cr5GC/RD/6" +
            "Z3GSu4J2YbucYMSK8AJnNRJz1V1evsTdkO92Xzg7fXAVyYG+qoxjT9aIzv+3r9sh" +
            "kt7TZcboDlvlT13K62FNpe6nklU7rwImmQT05KQtz00vzDr8o3vGrqOFstMliJYF" +
            "BvcdBcAml7CCKn+SuSpov95860i10UBhxj7fTksjt7GSHArpHA4J+niqeRGr6ka5" +
            "DmureyFnZnRkMMBq5UwApWEFhF+DmJ8rtMmOcmAHoFjKuBNnVSRqUKBH2Q5v0jbG" +
            "k9eoVY+lsUDs4D9bEU7bFkH3Qns1sxACDtfG4J2xvQKBgQDQRgL4L03JCZdCtD9G" +
            "Rz51XefDtmWdi7aYGjbmSAvYfPKkH9Tv4B7PSyYBvHUOcrNdVG+4T9REvc7xDxLh" +
            "oK4cZjkDDBHt73jM0zgC6dehtXcs97eDV7ER7w7/vovfFOshZQbAvgnd9ikeIl++" +
            "crIgASTtz4DnmI346kVgHjgq9QKBgQDPNWRUFC5gYzZzBw8KHk+CpCrfNvnzas+E" +
            "e4FIuYB7S5gvoJrUYu+1FeTr6PtEfoL8HUEY5bGla1CYSeU8wrtrRHX8LH859VB7" +
            "8MZ4mSBNaopuTSZTk0pNPQIrwGcoMxUGtMLRrY84goxxpK6iJ8OCnjpuP2DXnkXA" +
            "Y8CwF9mJlwKBgQDKPEFj5uL8JFD5V3bdcg6G/sEuvGzdVy3dcg7++tRtyZ3+ml40" +
            "8oZYORtVsj5j4iKirHuzF+kBOuG4Fy/5YQHP177iY5UBWngNQ8fupExa3I81XfCX" +
            "G853oI8K7GZ7Hp/WlHfDLoT8P6vbc/tOPacTBqlqNgRwXiT1n93voEhgBQKBgQCk" +
            "HVK4cT+GIi5nDskvp3AsNeCq6L0xuYBhGvz738jOXsJLvrXC0BWxZITrAJ2600Tl" +
            "cDo2AP8h2Ix+AdEpvcZ5oECemZOvEQhNEhfwPr/h8SdxU3OusDMiV9bXVk2d/k+B" +
            "/ztqnT+Zb3TTMa8LSdlFJBN9bH9EZ9wxFBDyqAEpQwKBgCzXS6Wkq5S4Fewc3eYO" +
            "iIIoVGwHaDIGd1zUwRXWuiAN2bx0xRUJRdOUV+3w0GwPXdux2nw4tW0TNaxikjC2" +
            "fTE7xMmF+LCKo7S0KXVxa1jiZu+4C/xRbX63p+fqhsSDUUnRykNu3HAFuvXVUewp" +
            "OaG5sIqSmqkhY8dH1Id5HBem"

    /** PEM 的头尾，同时也是内存里找目标那张公钥的特征前缀。 */
    const val PEM_HEADER = "-----BEGIN PUBLIC KEY-----"
    private const val PEM_FOOTER = "-----END PUBLIC KEY-----"

    /** 目标验签方法要求 `ver >= 2`，低于这个直接判无效。 */
    private const val TOKEN_VERSION = 2

    /** `exp = 0` 是目标自己定义的「永不过期」：只有 `exp > 0` 才会去比当前时间。 */
    private const val NEVER_EXPIRES = 0L

    /** 设备码取 SHA-256 十六进制的前 8 位，大写。 */
    private const val DEVICE_ID_LENGTH = 8

    // -----------------------------------------------------------------------

    /** 模块公钥，拼成和目标一模一样的 PEM 排版。 */
    val publicKeyPem: String by lazy {
        buildString {
            append(PEM_HEADER).append('\n')
            append(PUBLIC_KEY_B64.chunked(64).joinToString("\n"))
            append('\n').append(PEM_FOOTER)
        }
    }

    val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
    }

    private val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(PRIVATE_KEY_B64, Base64.DEFAULT)))
    }

    // -----------------------------------------------------------------------

    /**
     * 在 ART 托管堆里把目标的公钥 PEM 换成模块的。
     *
     * 先按前缀把内存里所有 PEM 捞出来，逐张比对：已经是模块这张就当成功（重复调用是常态，
     * 每次冷启动都会走一遍），是别人的就等长覆写。捞出来再换而不是直接拿写死的旧 PEM 去换，
     * 是因为目标下次轮换密钥时前者仍然有效，后者会静默失效 —— 而静默失效的补丁比
     * 没打补丁更难查。
     *
     * @return 换掉了几张；0 表示没找到可换的，-1 表示原生层不可用
     */
    fun swapPublicKeyInMemory(scope: HookScope): Int {
        if (!NativeHook.isAvailable) {
            scope.log.w("原生层没加载（${NativeHook.lastError}），跳过内存换公钥")
            return -1
        }

        val found = NativeHook.findAscii(
            prefix = PEM_HEADER,
            minLength = PEM_HEADER.length + PEM_FOOTER.length,
            maxLength = 2048,
            scope = NativeHook.Scope.MANAGED_HEAP,
        )
        if (found.isEmpty()) {
            scope.log.w("托管堆里没找到公钥 PEM —— 验签类可能还没初始化，或者公钥不是 PEM 形式")
            return 0
        }

        val mine = publicKeyPem
        var swapped = 0
        for (pem in found) {
            // findAscii 停在第一个非可打印字节，所以尾部可能带上紧随其后的内容；
            // 只取到 footer 为止，长度才对得上。
            val end = pem.indexOf(PEM_FOOTER)
            if (end < 0) continue
            val candidate = pem.substring(0, end + PEM_FOOTER.length)

            if (candidate == mine) {
                scope.log.d("公钥已经是模块这张，无需再换")
                swapped++
                continue
            }
            if (candidate.length != mine.length) {
                scope.log.w("目标公钥 ${candidate.length} 字节，模块的 ${mine.length} 字节，长度不等不能原地换")
                continue
            }
            // 同一个字面量在托管堆里往往有好几份（intern 表、常量池解析出来的实例），
            // 全换掉才算数 —— 漏掉一份就等于留了一条会拿原始公钥去验签的路。
            val writes = NativeHook.replaceAscii(candidate, mine, scope = NativeHook.Scope.MANAGED_HEAP)
            if (writes > 0) {
                swapped++
                scope.log.i("公钥已在托管堆中换成模块的（$writes 份副本）")
            } else {
                scope.log.w("公钥覆写失败（$writes）—— 可能刚好被 GC 搬走了")
            }
        }
        return swapped
    }

    // -----------------------------------------------------------------------

    /**
     * 给 [deviceId] 签一张永不过期的凭证。
     *
     * `activated_at` 用调用方给的时间而不是 `now`：设置页把它当激活日期显示，
     * 每次冷启动都往前跳会很显眼，用安装时间既稳定又说得通。
     */
    fun issueToken(deviceId: String, activatedAtSeconds: Long): String {
        val header = JSONObject()
            .put("alg", "RS256")
            .put("typ", "JWT")
            .toString()
        val payload = JSONObject()
            .put(SkyPulseDex.CLAIM_DEVICE_ID, deviceId)
            .put("activated_at", activatedAtSeconds)
            .put(SkyPulseDex.CLAIM_EXP, NEVER_EXPIRES)
            .put(SkyPulseDex.CLAIM_VER, TOKEN_VERSION)
            .toString()

        val signingInput = "${header.b64url()}.${payload.b64url()}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
        return "$signingInput.${signature.b64url()}"
    }

    /**
     * 本机设备码。
     *
     * 优先问目标自己要 —— 盐和摘要方式都可能随版本改，问它永远对。拿不到才自己算，
     * 用的是 3.5.49 的算法：`SHA-256(ANDROID_ID + 盐)` 取十六进制前 8 位大写。
     */
    fun deviceId(scope: HookScope, refs: SkyPulseDex.Refs, context: Context): String? {
        refs.deviceId?.let { getter ->
            runCatching { getter.invoke(getter.ownerInstance(), context) as? String }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return runCatching {
            val androidId = Settings.Secure.getString(context.contentResolver, "android_id") ?: "unknown"
            MessageDigest.getInstance("SHA-256")
                .digest((androidId + SkyPulseDex.DEVICE_SALT).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(DEVICE_ID_LENGTH)
                .uppercase()
        }.onFailure { scope.log.w("算不出设备码：${it.message}") }.getOrNull()
    }

    /**
     * 现在这张 JWT 在目标眼里是不是有效的。
     *
     * 换公钥**之前**必须问一次：真付费用户的凭证是服务端私钥签的，换掉公钥会把它作废。
     * 返回 true 就什么都别做 —— 人家本来就是会员。
     */
    fun hasValidToken(refs: SkyPulseDex.Refs, prefs: SharedPreferences?): Boolean {
        val verify = refs.verifyToken ?: return false
        val token = storedToken(prefs) ?: return false
        return runCatching { verify.invoke(verify.ownerInstance(), token) != null }.getOrDefault(false)
    }

    /**
     * 存着的凭证是不是本模块签的。
     *
     * 每次冷启动内存都是全新的，公钥又变回目标原装那把，所以 [hasValidToken] 必然对
     * 自签凭证判否 —— 照着那个结论走就会每次启动都重签一张，白写一次盘。
     *
     * 这里用**模块自己的公钥**单独验一次：验得过就说明上一次的凭证还在，只要把公钥
     * 换回去就行，不必重签。这也顺带把两种「验不过」区分开了 —— 是别人签的（真付费
     * 用户，别动），还是压根没有（第一次，去签一张）。
     */
    fun hasOwnToken(prefs: SharedPreferences?): Boolean {
        val token = storedToken(prefs) ?: return false
        val parts = token.split('.')
        if (parts.size != 3) return false

        return runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
                verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            }
        }.getOrDefault(false)
    }

    private fun storedToken(prefs: SharedPreferences?): String? =
        runCatching { prefs?.getString(SkyPulseDex.KEY_JWT, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    // -----------------------------------------------------------------------

    private fun String.b64url(): String = toByteArray(Charsets.UTF_8).b64url()

    private fun ByteArray.b64url(): String =
        Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
