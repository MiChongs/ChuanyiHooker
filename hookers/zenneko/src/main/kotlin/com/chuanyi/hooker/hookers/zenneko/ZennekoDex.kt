package com.chuanyi.hooker.hookers.zenneko

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 四个落点解析出来的结果。缺哪个只让**依赖它的那一项**不可用，其余照常。
 */
internal class ZennekoRefs(
    /** 会员闸门：`static Enum <混淆名>(Context, Continuation)`。 */
    val gate: Method?,
    /** 闸门的裁决枚举。 */
    val verdict: Class<*>?,
    /** 用户信息模型（`UserInfoData`）。 */
    val userInfo: Class<*>?,
    /** 实时会员校验的回包模型（`MemberCheckResult`）。 */
    val memberCheck: Class<*>?,
    /** 工具项（`ToolItem`），带 `vipOnly` 标记。 */
    val toolItem: Class<*>?,
) {
    /** 裁决枚举里的「放行」。名字比 ordinal 稳，取不到才退回 ordinal 0。 */
    val allowed: Any? by lazy {
        val enumClass = verdict ?: return@lazy null
        runCatching {
            @Suppress("UNCHECKED_CAST")
            java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, Zenneko.VERDICT_ALLOWED)
        }.getOrNull() ?: enumClass.enumConstants?.firstOrNull()
    }

    fun summary(): String = buildString {
        append("闸门=").append(gate?.let { "${it.declaringClass.name}.${it.name}" } ?: "未找到")
        append("，裁决枚举=").append(verdict?.name ?: "未找到")
        append("，用户信息=").append(userInfo?.name ?: "未找到")
        append("，会员校验=").append(memberCheck?.name ?: "未找到")
        append("，工具项=").append(toolItem?.name ?: "未找到")
    }
}

/**
 * 定位那些被 R8 重命名的落点。
 *
 * 这个应用只有 `io.github.wisyh.zenneko.*` 那几十个类（`MainActivity` /
 * `MyApplication` / `Routes$*` / `NativeBridge` / 序列化模型）保住了名字，会员相关的
 * 全部在 R8 生成的两字母包里 —— 1.0.0-alpha.7 里闸门是 `gb.h3.i`，用户模型是 `db.v`，
 * 下一版就会变。所以一个都不写死。
 *
 * ## 判据
 *
 * | 落点 | 判据 | 为什么稳 |
 * |---|---|---|
 * | 会员闸门 | `static`、返回 `java.lang.Enum`、两个参数、第一个是 `Context` | **全包唯一**一个返回 `java.lang.Enum` 的方法。挂起函数被 R8 抹掉了具体枚举类型，反而成了指纹 |
 * | 裁决枚举 | `<clinit>` 里同时出现 `ALLOWED` / `NOT_LOGGED_IN` / `NOT_MEMBER` / `NETWORK_ERROR` | 枚举常量名是构造参数，语义决定，不随混淆变 |
 * | 用户信息 | `toString()` 里有 `UserInfoData(id=` 与 `, memberStatus=` | data class 的 toString 是编译期拼好的字面量 |
 * | 会员校验 | `toString()` 里有 `MemberCheckResult(isMember=` | 同上 |
 * | 工具项 | `toString()` 里有 `ToolItem(id=` 与 `, vipOnly=` | 同上 |
 *
 * 这些串在 dex 里是 StringFog 密文，进 DexKit 之前要过一遍 [Zenneko.fog]。
 *
 * ## 三条路
 *
 * 1. 设置项覆盖 —— 应急用，不重新出包就能纠正一次误判
 * 2. 上次扫描的结果，按 versionCode 缓存在**目标自己**的数据目录里
 * 3. DexKit 扫 dex
 *
 * 没有第 4 条「写死的候选名」：这个目标的判据本身就是「用了哪些串」和「返回什么类型」，
 * 没有等价的名字可退。DexKit 起不来（本机 ABI 没有 `libdexkit.so`）就是这一次全部
 * 功能不可用，调用方记一行日志并跳过，而不是拿一个过期的名字去挂错地方。
 */
internal object ZennekoDex {

    /** 闸门的返回类型。R8 把具体枚举收窄标注成了裸 `Enum`，这本身就是指纹。 */
    private const val GATE_RETURN_TYPE = "java.lang.Enum"

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "zenneko.version"

    private const val CACHE_GATE_CLASS = "zenneko.gate.class"
    private const val CACHE_GATE_METHOD = "zenneko.gate.method"
    private const val CACHE_VERDICT = "zenneko.verdict"
    private const val CACHE_USER_INFO = "zenneko.user_info"
    private const val CACHE_MEMBER_CHECK = "zenneko.member_check"
    private const val CACHE_TOOL_ITEM = "zenneko.tool_item"

    /** 设置项：逐个覆盖类名，不重新出包就能纠正。 */
    const val KEY_GATE_CLASS = "gate_class"
    const val KEY_VERDICT_CLASS = "verdict_class"
    const val KEY_USER_INFO_CLASS = "user_info_class"
    const val KEY_MEMBER_CHECK_CLASS = "member_check_class"
    const val KEY_TOOL_ITEM_CLASS = "tool_item_class"

    // -----------------------------------------------------------------------

    fun resolve(scope: HookScope): ZennekoRefs {
        cached(scope)?.let {
            scope.log.d("落点命中缓存：${it.summary()}")
            return it
        }
        val scanned = scan(scope)
        remember(scope, scanned)
        return scanned
    }

    // -----------------------------------------------------------------------

    /**
     * 一次打开、四条查询、一次关闭。
     *
     * 扫描发生在应用**启动路径**上，开四次 bridge 就是四次把整个 dex 读进去 ——
     * 1.0.0-alpha.7 的 `classes.dex` 有 10 MB，那个代价是看得见的卡顿。
     */
    private fun scan(scope: HookScope): ZennekoRefs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return empty(scope)
        }
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return empty(scope)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return empty(scope)

        val refs = bridge.use { dex ->
            ZennekoRefs(
                gate = scanGate(scope, dex) ?: configuredGate(scope),
                verdict = scanVerdict(scope, dex) ?: configuredClass(scope, KEY_VERDICT_CLASS, ::verifyVerdict),
                userInfo = scanUserInfo(scope, dex)
                    ?: configuredClass(scope, KEY_USER_INFO_CLASS, ::verifyUserInfo),
                memberCheck = scanMemberCheck(scope, dex)
                    ?: configuredClass(scope, KEY_MEMBER_CHECK_CLASS, ::verifyMemberCheck),
                toolItem = scanToolItem(scope, dex)
                    ?: configuredClass(scope, KEY_TOOL_ITEM_CLASS, ::verifyToolItem),
            )
        }
        scope.log.i("dex 扫描用时 ${SystemClock.elapsedRealtime() - startedAt}ms：${refs.summary()}")
        return refs
    }

    /**
     * 会员闸门。
     *
     * 判据只有三条，却在全包里唯一：静态、返回 `java.lang.Enum`、两个参数。
     *
     * 它是个挂起函数（`suspend fun (Context): 裁决`），编译后签名成了
     * `(Context, Continuation) -> Object`；R8 又把返回类型收窄标注成了 `java.lang.Enum`
     * 而不是具体枚举 —— 全包 4900 多个类里只有这一个方法这么长。
     */
    private fun scanGate(scope: HookScope, dex: DexKitBridge): Method? {
        val hits = runCatching {
            dex.findMethod {
                matcher {
                    modifiers = Modifier.STATIC
                    returnType = GATE_RETURN_TYPE
                    paramCount = 2
                }
            }
        }.onFailure { scope.log.w("会员闸门扫描失败：${it.message}") }.getOrNull() ?: return null

        val candidates = hits.filter { it.paramTypeNames.firstOrNull() == "android.content.Context" }
        if (candidates.isEmpty()) {
            scope.log.w("找不到「静态 + 返回 Enum + (Context, Continuation)」的方法，应用可能已改结构")
            return null
        }
        if (candidates.size > 1) {
            scope.log.w("会员闸门命中 ${candidates.size} 个，取第一个：${candidates.joinToString { it.descriptor }}")
        }
        return runCatching { candidates.first().getMethodInstance(scope.classLoader) }
            .onFailure { scope.log.w("会员闸门反射不出来：${it.message}") }
            .getOrNull()
    }

    /** 裁决枚举：四个常量名都在它的 `<clinit>` 里。 */
    private fun scanVerdict(scope: HookScope, dex: DexKitBridge): Class<*>? = scanClass(
        scope, dex, "裁决枚举", ::verifyVerdict,
        Zenneko.fog(scope, Zenneko.VERDICT_ALLOWED),
        Zenneko.fog(scope, Zenneko.VERDICT_NOT_LOGGED_IN),
        Zenneko.fog(scope, Zenneko.VERDICT_NOT_MEMBER),
        Zenneko.fog(scope, Zenneko.VERDICT_NETWORK_ERROR),
    )

    private fun scanUserInfo(scope: HookScope, dex: DexKitBridge): Class<*>? = scanClass(
        scope, dex, "用户信息模型", ::verifyUserInfo,
        Zenneko.fog(scope, Zenneko.ANCHOR_USER_INFO),
        Zenneko.fog(scope, Zenneko.ANCHOR_USER_INFO_STATUS),
        Zenneko.fog(scope, Zenneko.ANCHOR_USER_INFO_MEMBER),
    )

    private fun scanMemberCheck(scope: HookScope, dex: DexKitBridge): Class<*>? = scanClass(
        scope, dex, "会员校验模型", ::verifyMemberCheck,
        Zenneko.fog(scope, Zenneko.ANCHOR_MEMBER_CHECK),
    )

    private fun scanToolItem(scope: HookScope, dex: DexKitBridge): Class<*>? = scanClass(
        scope, dex, "工具项模型", ::verifyToolItem,
        Zenneko.fog(scope, Zenneko.ANCHOR_TOOL_ITEM),
        Zenneko.fog(scope, Zenneko.ANCHOR_TOOL_ITEM_VIP),
    )

    /**
     * 按「类里用到了这些串」找类。
     *
     * 走 `findMethod` 而不是 `findClass`：命中之后要的是**类名**（`MethodData.className`），
     * 拿它去目标的 classloader 里 `Class.forName`。枚举那一条尤其需要这样 ——
     * 它的锚点在 `<clinit>` 里，而 `<clinit>` 是反射不出 `Method` 的。
     */
    private fun scanClass(
        scope: HookScope,
        dex: DexKitBridge,
        label: String,
        verify: (Class<*>) -> Boolean,
        vararg anchors: String,
    ): Class<*>? {
        val hits = runCatching {
            dex.findMethod { matcher { usingStrings(*anchors) } }
        }.onFailure { scope.log.w("$label 扫描失败：${it.message}") }.getOrNull()

        if (hits.isNullOrEmpty()) {
            scope.log.w("$label：dex 里找不到（锚点可能已随版本改变）")
            return null
        }

        val names = hits.map { it.className }.distinct()
        if (names.size > 1) {
            scope.log.w("$label 命中 ${names.size} 个类：${names.joinToString()}，逐个验形状")
        }
        return names.firstNotNullOfOrNull { name ->
            scope.classOrNull(name)?.takeIf { candidate ->
                verify(candidate).also { ok ->
                    if (!ok) scope.log.w("$label 候选 $name 形状不对，弃用")
                }
            }
        } ?: run {
            scope.log.w("$label：命中的类没有一个形状对得上")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 形状复核。缓存和设置项给的都是**上一次**的答案，必须再验一遍。
    // -----------------------------------------------------------------------

    private fun verifyVerdict(clazz: Class<*>): Boolean =
        clazz.isEnum && (clazz.enumConstants?.size ?: 0) >= 3

    private fun verifyUserInfo(clazz: Class<*>): Boolean =
        Zenneko.userInfoConstructor(clazz) != null

    private fun verifyMemberCheck(clazz: Class<*>): Boolean =
        Zenneko.memberCheckConstructor(clazz) != null

    private fun verifyToolItem(clazz: Class<*>): Boolean =
        Zenneko.vipOnlyField(clazz) != null && clazz.declaredConstructors.isNotEmpty()

    // -----------------------------------------------------------------------
    // 设置项覆盖
    // -----------------------------------------------------------------------

    private fun configuredClass(
        scope: HookScope,
        key: String,
        verify: (Class<*>) -> Boolean,
    ): Class<*>? = scope.string(key)
        ?.takeIf { it.isNotBlank() }
        ?.let { scope.classOrNull(it) }
        ?.takeIf(verify)
        ?.also { scope.log.i("$key 来自设置项：${it.name}") }

    /** 闸门的覆盖只给类名，方法按同一条形状判据在类里挑。 */
    private fun configuredGate(scope: HookScope): Method? = scope.string(KEY_GATE_CLASS)
        ?.takeIf { it.isNotBlank() }
        ?.let { scope.classOrNull(it) }
        ?.let(::gateIn)
        ?.also { scope.log.i("会员闸门来自设置项：${it.declaringClass.name}.${it.name}") }

    private fun gateIn(clazz: Class<*>): Method? = clazz.declaredMethods.firstOrNull { method ->
        Modifier.isStatic(method.modifiers) &&
            // 按名字比而不是 `java.lang.Enum::class.java`：返回类型就是**裸的** Enum
            // （R8 抹掉了具体枚举），Kotlin 侧引用那个类会被判成不推荐用法。
            method.returnType.name == GATE_RETURN_TYPE &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == Context::class.java
    }

    // -----------------------------------------------------------------------
    // 缓存
    //
    // 扫一次 10 MB 的 dex 要几百毫秒，而它在启动路径上。缓存写在**目标自己**的数据
    // 目录：模块的远端配置对被 hook 的进程是只读的。
    // -----------------------------------------------------------------------

    private fun cached(scope: HookScope): ZennekoRefs? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null

        val gateClass = prefs.getString(CACHE_GATE_CLASS, null) ?: return null
        val gateMethod = prefs.getString(CACHE_GATE_METHOD, null) ?: return null
        val gate = scope.classOrNull(gateClass)
            ?.let(::gateIn)
            ?.takeIf { it.name == gateMethod }
            ?: return null

        return ZennekoRefs(
            gate = gate,
            verdict = load(scope, prefs, CACHE_VERDICT, ::verifyVerdict),
            userInfo = load(scope, prefs, CACHE_USER_INFO, ::verifyUserInfo),
            memberCheck = load(scope, prefs, CACHE_MEMBER_CHECK, ::verifyMemberCheck),
            toolItem = load(scope, prefs, CACHE_TOOL_ITEM, ::verifyToolItem),
        )
    }

    private fun load(
        scope: HookScope,
        prefs: SharedPreferences,
        key: String,
        verify: (Class<*>) -> Boolean,
    ): Class<*>? = prefs.getString(key, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { scope.classOrNull(it) }
        ?.takeIf(verify)

    private fun remember(scope: HookScope, refs: ZennekoRefs) {
        // 闸门是缓存的主键 —— 它没扫到就别落盘，否则下次会拿一份残缺的缓存跳过扫描。
        val gate = refs.gate ?: return
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(CACHE_GATE_CLASS, gate.declaringClass.name)
                .putString(CACHE_GATE_METHOD, gate.name)
                .putString(CACHE_VERDICT, refs.verdict?.name)
                .putString(CACHE_USER_INFO, refs.userInfo?.name)
                .putString(CACHE_MEMBER_CHECK, refs.memberCheck?.name)
                .putString(CACHE_TOOL_ITEM, refs.toolItem?.name)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /** DexKit 用不了时，设置项覆盖仍然有效 —— 那是不依赖 dex 扫描的唯一一条路。 */
    private fun empty(scope: HookScope) = ZennekoRefs(
        gate = configuredGate(scope),
        verdict = configuredClass(scope, KEY_VERDICT_CLASS, ::verifyVerdict),
        userInfo = configuredClass(scope, KEY_USER_INFO_CLASS, ::verifyUserInfo),
        memberCheck = configuredClass(scope, KEY_MEMBER_CHECK_CLASS, ::verifyMemberCheck),
        toolItem = configuredClass(scope, KEY_TOOL_ITEM_CLASS, ::verifyToolItem),
    )
}
