package com.chuanyi.hooker.hookers.zenneko

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * 常量、字符串混淆的还原，以及按**形状**认落点的那几个判据。
 *
 * 这个文件里没有一个被 R8 重命名过的名字 —— 版本一变它们全会改，写死等于每次应用
 * 更新都要重开一次 jadx。类和方法怎么找见 [ZennekoDex]。
 */
internal object Zenneko {

    const val PACKAGE = "io.github.wisyh.zenneko"

    /**
     * 字符串混淆器。包名是 `obfuse`，不在 R8 的重命名范围里，名字是稳的。
     *
     * 实现很短：hex 解码后逐字节 XOR 一个循环密钥，密钥就是类上的 `public static
     * String KEY`（1.0.0-alpha.7 里是 `wisyh`）。见 [fog]。
     */
    private const val STRING_FOG = "obfuse.NPStringFog"

    private const val FALLBACK_KEY = "wisyh"

    /** 目标自己那张 hex 表是大写的，`decode()` 用 `indexOf` 查它——编码必须也大写。 */
    private const val HEX = "0123456789ABCDEF"

    // -----------------------------------------------------------------------
    // 锚点（明文）
    //
    // 全部取自 Kotlin data class 自动生成的 toString()，以及枚举常量名。挑它们的
    // 理由是：这些串**跟着语义走**，不跟着混淆走 —— R8 改的是类名和字段名，
    // toString 里那句 "UserInfoData(id=" 是编译期就拼好的字面量，只有作者改类名
    // 时才会变。而枚举常量名是 `Enum(String,int)` 的构造参数，同理。
    // -----------------------------------------------------------------------

    /** 会员闸门的裁决枚举，四个常量。 */
    const val VERDICT_ALLOWED = "ALLOWED"
    const val VERDICT_NOT_LOGGED_IN = "NOT_LOGGED_IN"
    const val VERDICT_NOT_MEMBER = "NOT_MEMBER"
    const val VERDICT_NETWORK_ERROR = "NETWORK_ERROR"

    /** `UserInfoData(id=…, memberStatus=…, memberExpire=…, isMember=…, admin=…, isBanned=…)` */
    const val ANCHOR_USER_INFO = "UserInfoData(id="
    const val ANCHOR_USER_INFO_STATUS = ", memberStatus="
    const val ANCHOR_USER_INFO_MEMBER = ", isMember="

    /** `MemberCheckResult(isMember=…, status=…, expire=…)` —— `/users/user/check-member` 的回包模型。 */
    const val ANCHOR_MEMBER_CHECK = "MemberCheckResult(isMember="

    /** `ToolItem(id=…, nameRes=…, icon=…, descriptionRes=…, isNew=…, vipOnly=…)` */
    const val ANCHOR_TOOL_ITEM = "ToolItem(id="
    const val ANCHOR_TOOL_ITEM_VIP = ", vipOnly="

    // -----------------------------------------------------------------------
    // 原生层
    // -----------------------------------------------------------------------

    /**
     * 环境自检。JNI 的名字由 `Java_<包>_<类>_<方法>` 这条规则钉死，混淆动不了它 ——
     * 所以这两个名字可以写死，而且是**静态注册**（符号表里就有），Dobby 直接按名解析。
     */
    const val NATIVE_LIBRARY = "libcore.so"
    const val NATIVE_SECURITY_CHECK =
        "Java_io_github_wisyh_zenneko_nativebridge_NativeBridge_checkSecurityEnvironment"

    /** Java 侧的桥，用来读它的真实返回值（诊断项要用）。 */
    const val NATIVE_BRIDGE = "io.github.wisyh.zenneko.nativebridge.NativeBridge"

    // -----------------------------------------------------------------------
    // 会员到期时间
    // -----------------------------------------------------------------------

    /**
     * 应用判「永久」的规则，是**到期时间的前 4 位年份 >= 2099**（`0x833`）：
     *
     * ```
     * expire.trim().take(4).toIntOrNull()?.let { if (it >= 2099) "永久VIP会员 · 永久有效" }
     *   ?: "会员有效期至 ${expire.take(10)}"
     * ```
     *
     * 「我的」页顶栏和会员页各有一份同样的判断。所以到期时间只要落在 2099 年之后，
     * 界面就走永久那一支，而不是显示一个具体日期。
     *
     * 格式必须是 `yyyy-MM-dd HH:mm:ss` —— 应用用这个 pattern 去 `SimpleDateFormat.parse`，
     * 解析不出来会退回字符串截断，那条路虽然也能显示，但「还剩多少小时」的提醒算不出来。
     */
    const val DEFAULT_EXPIRE = "2099-12-31 23:59:59"

    /** 判成永久的年份下限，与目标里的 `0x833` 对齐。 */
    const val PERMANENT_YEAR = 2099

    /**
     * 会员状态码。界面上只有一处用到它：`memberStatus >= 1` 决定会员卡片显不显示。
     * 没有别的取值语义，1 就够。
     */
    const val DEFAULT_STATUS = 1

    // -----------------------------------------------------------------------

    /**
     * 把一段明文编成目标 dex 里存的那个形式。
     *
     * DexKit 的 `usingStrings` 匹配的是 **dex 里的字面量**，而这个应用的字面量全被
     * StringFog 换成了 hex 密文 —— 直接拿明文去匹配一个都命中不了。所以锚点在源码里
     * 写明文，进 DexKit 之前过一遍这里。
     *
     * 密钥从**目标自己**的 `NPStringFog.KEY` 读，不写死：作者换了密钥，锚点跟着换，
     * 不用重新出包。读不到才退回 1.0.0-alpha.7 的 `wisyh`。
     */
    fun fog(scope: HookScope, plain: String): String {
        val key = fogKey(scope)
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = (byte.toInt() xor key[index % key.length].code) and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0xF])
        }
        return out.toString()
    }

    private fun fogKey(scope: HookScope): String = runCatching {
        scope.classOrNull(STRING_FOG)
            ?.getDeclaredField("KEY")
            ?.apply { isAccessible = true }
            ?.get(null) as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK_KEY

    // -----------------------------------------------------------------------
    // 形状判据
    //
    // 拿到类之后一定要再验一次形状：DexKit 命中、设置项覆盖、上一次的缓存 —— 这三条
    // 路给的都可能是过期的答案。
    // -----------------------------------------------------------------------

    /**
     * 用户信息模型的构造器。
     *
     * `UserInfoData(id, username, nickname, avatar, createdAt, memberStatus,
     * memberExpire, isMember, admin, isBanned)` —— Kotlin data class，字段顺序就是
     * 构造参数顺序，而 toString 的片段顺序又跟字段顺序一致，所以下面按**类型形状**
     * 定位到的下标是可信的：
     *
     * - 唯一的 `int` 参数 = memberStatus
     * - 紧跟其后的 `String` = memberExpire
     * - 第一个 `boolean` = isMember，最后一个 = isBanned
     */
    fun userInfoConstructor(clazz: Class<*>): Constructor<*>? =
        clazz.declaredConstructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 10 &&
                types.count { it == Int::class.javaPrimitiveType } == 1 &&
                types.count { it == Boolean::class.javaPrimitiveType } == 3 &&
                types[0] == Long::class.javaPrimitiveType
        }

    /** `MemberCheckResult(status: Int, expire: String?, isMember: Boolean)`。 */
    fun memberCheckConstructor(clazz: Class<*>): Constructor<*>? =
        clazz.declaredConstructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 3 &&
                types[0] == Int::class.javaPrimitiveType &&
                types[1] == String::class.java &&
                types[2] == Boolean::class.javaPrimitiveType
        }

    /**
     * ToolItem 上那个 `vipOnly` 字段。
     *
     * 这个类只有一个 `boolean` 字段，形状上就是唯一的 —— 不用去猜 R8 给它取了什么名。
     * 构造器里它是由「默认值掩码」算出来的（`(mask & 32) == 0`），改掩码等于依赖那条
     * 折叠出来的算式，不如构造完直接把字段按平。
     */
    fun vipOnlyField(clazz: Class<*>): Field? =
        clazz.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType && !it.isSynthetic }
            .singleOrNull()
            ?.apply { isAccessible = true }

    /** 下标（[userInfoConstructor] 里说明的形状判据）。 */
    fun statusIndex(types: Array<Class<*>>): Int =
        types.indexOfFirst { it == Int::class.javaPrimitiveType }

    fun memberIndex(types: Array<Class<*>>): Int =
        types.indexOfFirst { it == Boolean::class.javaPrimitiveType }

    fun bannedIndex(types: Array<Class<*>>): Int =
        types.indexOfLast { it == Boolean::class.javaPrimitiveType }

    /**
     * 到期时间够不够「永久」。设置项填了个 2099 年之前的日期时提醒一句 ——
     * 那样界面会显示成一个具体到期日，不是永久。
     */
    fun isPermanent(expire: String): Boolean =
        expire.trim().take(4).toIntOrNull()?.let { it >= PERMANENT_YEAR } == true
}
