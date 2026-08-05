package com.chuanyi.hooker.hookers.zenneko

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zenneko（`io.github.wisyh.zenneko`）—— 一个装了一百多个小工具的合集应用，
 * 其中 7 个标了 VIP 专属。
 *
 * ## 这个目标的形状
 *
 * Compose + Navigation 3 + okhttp，整包 R8 重命名，字符串过了一层 StringFog
 * （hex + XOR，密钥 `wisyh` 就在 `obfuse.NPStringFog.KEY` 上明着放着）。原生层只有
 * 一个自家的 `libcore.so`，其余是 ffmpeg-kit 和 OpenSSL。
 *
 * 会员**由自家服务器裁决**：
 *
 * ```
 * GET https://zenneko.top/users/user/check-member   Authorization: Bearer <auth_token>
 *   -> { code:200, data:{ status:int, expire:"yyyy-MM-dd HH:mm:ss", is_member:bool } }
 * ```
 *
 * 而那个回包**没有自证** —— 全包搜不到对它的验签，没有回执复验，也没有任何本地凭据。
 * 服务器说什么客户端就信什么，所以整套解锁不需要伪造任何签名。
 *
 * ## 两道门，都在客户端
 *
 * 点一个工具时走的是这条路（`ToolItem` → 列表页的点击回调）：
 *
 * ```
 * if (!tool.vipOnly) { 直接导航 }
 * else launch { when (会员闸门(context)) {          // suspend, 返回一个四值枚举
 *          ALLOWED       -> 导航
 *          NOT_LOGGED_IN -> 弹登录
 *          NOT_MEMBER    -> 弹开通会员
 *          NETWORK_ERROR -> 提示「网络异常，会员状态验证失败」
 *      } }
 * ```
 *
 * 两道门是**串联**的，而且各有一个唯一的落点：`vipOnly` 这个标记只在 `ToolItem` 的
 * 构造器里赋值一次（工具表里 103 处构造全走它），闸门那个挂起函数全包只有一个调用点。
 * 所以 [unlock] 和 [vip_gate] 分开做成两项 —— 任何一项单独打开都能解锁，两项都开
 * 就是两套互不依赖的机制，应用改了其中一条路另一条还在。
 *
 * [unlock] 是更靠前的那道：它让点击根本不进闸门，于是**不登录、不联网**也能用，
 * 也不会有那一次网络往返的停顿。
 *
 * ## 会员身份显示
 *
 * [lifetime] 管的是另一件事：界面上「我的」页和会员页显示什么。判「永久」的规则是
 * **到期时间的年份 >= 2099**（见 [Zenneko.DEFAULT_EXPIRE]），不是某个状态码 ——
 * 所以把到期时间写成 2099 年之后，那两页就显示「永久VIP会员 · 永久有效」而不是一个
 * 具体日期。
 *
 * ## 服务端这一侧
 *
 * 说清楚边界：7 个 VIP 工具全部是云端能力（智能抠图 / AI 识图 / AI 超分 / AI 消除 /
 * 豆包画图 / 视频信息 / 聚合解析），请求打到 `zenneko.top`，okhttp 拦截器会带上
 * `Authorization: Bearer <auth_token>`。**本模块解的是客户端这一侧的闸门**；服务端
 * 若对同一个接口再按账号校验一次会员，那一层不在客户端的可达范围内。
 * 打开 [log_member] 能看到每次判定的实际结果，用来区分「被客户端拦了」还是
 * 「服务端拒绝了」。
 */
class ZennekoHooker : AppHooker {

    override val id = "zenneko"
    override val displayName = "Zenneko"
    override val description = "工具合集，解锁 VIP 工具与永久会员"
    override val targetPackages = setOf(Zenneko.PACKAGE)

    /** [onHook] 里解析一次，各功能共用。 */
    @Volatile
    private var refs: ZennekoRefs? = null

    private val nativeLogged = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "unlock",
            title = "解锁全部 VIP 工具",
            summary = "把工具表里的「VIP 专属」标记全部按掉。点进去直接就用，" +
                "不登录、不联网也可以，列表上的 VIP 角标也一并消失",
            install = { installUnlock() },
        ),
        HookFeature(
            id = "vip_gate",
            title = "会员校验直通",
            summary = "接管那个会员闸门，一律返回「放行」。和上一项是两套互不依赖的机制，" +
                "任一项生效就能解锁；两项都开时，应用改了其中一条路另一条还在",
            install = { installVipGate() },
        ),
        HookFeature(
            id = "lifetime",
            title = "永久会员",
            summary = "把用户信息和实时会员校验的结果都改成永久会员，到期时间写到 2099 年。" +
                "「我的」页与会员页会显示「永久VIP会员 · 永久有效」",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "native_env",
            title = "原生环境自检置正常",
            summary = "让 libcore.so 的环境自检直接返回正常值。它会读 /proc/self/maps 和签名摘要，" +
                "在 Xposed 环境下可能判异常并让内部的公钥取不出来（影响去水印）。" +
                "默认关闭：这一项会跳过该函数的全部副作用，只在确认需要时再开",
            defaultEnabled = false,
            install = { installNativeEnv() },
        ),
        HookFeature(
            id = "log_member",
            title = "记录会员判定",
            summary = "排查用：记录每一次闸门裁决、服务器返回的会员校验结果、用户信息里的会员字段，" +
                "以及原生环境自检的真实返回值",
            defaultEnabled = false,
            install = { installLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        // 这个类是 JNI 契约钉住的名字，混淆动不了它。它不在，说明装的根本不是这个应用。
        if (scope.classOrNull(Zenneko.NATIVE_BRIDGE) == null) {
            scope.log.w("找不到 ${Zenneko.NATIVE_BRIDGE}，应用结构可能已大改")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Zenneko ${scope.versionCode} in ${scope.processName}")
        refs = ZennekoDex.resolve(scope)
    }

    // -----------------------------------------------------------------------

    /**
     * 第一道门：`ToolItem.vipOnly`。
     *
     * 这个标记只在构造器里赋值，而工具表里 103 处构造全部走同一个构造器 ——
     * 挂它一处就覆盖了首页、搜索、最近使用、收藏这几个列表页各自的点击回调，
     * 每一页原本都要各判一次 `vipOnly`。
     *
     * 改的是**构造完之后的字段**，不是构造参数：那个值在构造器里是由 Kotlin 的
     * 默认值掩码算出来的（`(mask & 32) == 0`），改掩码等于依赖 R8 这一次折叠出来的
     * 算式；而「这个类只有一个 boolean 字段」是形状，换版本也成立。
     */
    private fun HookScope.installUnlock() {
        val toolItem = refs?.toolItem ?: error("定位不到工具项模型，未安装")
        val field = Zenneko.vipOnlyField(toolItem)
            ?: error("${toolItem.name} 上找不到唯一的 boolean 字段（结构可能已变）")

        var hooked = 0
        toolItem.declaredConstructors.forEach { ctor ->
            runCatching {
                ctor.createAfterHook("zenneko.unlock.${ctor.parameterCount}") { param ->
                    field.setBoolean(param.thisObject, false)
                }
            }.onSuccess { hooked++ }
                .onFailure { log.w("${toolItem.simpleName} 的构造器挂不上：${it.message}") }
        }
        if (hooked == 0) error("${toolItem.name} 的构造器一个都没挂上")
        log.i("VIP 专属标记已按掉：${toolItem.name}.${field.name}（$hooked 个构造器）")
    }

    /**
     * 第二道门：那个挂起的会员闸门。
     *
     * 直接返回枚举而不是「挂起」是合法的 —— 挂起函数的调用约定里，返回值不等于
     * `COROUTINE_SUSPENDED` 就表示「没挂起就完成了」，协程机器码本来就有这条快路。
     * 于是既不发那次 `check-member` 请求，也不要求已登录。
     */
    private fun HookScope.installVipGate() {
        val gate = refs?.gate ?: error("定位不到会员闸门，未安装")
        val allowed = refs?.allowed
            ?: error("取不到裁决枚举的「${Zenneko.VERDICT_ALLOWED}」，未安装")

        gate.createReplaceHook("zenneko.vip_gate") { allowed }
        log.i("会员闸门已直通：${gate.declaringClass.name}.${gate.name} -> $allowed")
    }

    /**
     * 会员身份本身。
     *
     * 两个模型都改，它们喂的是不同的界面：
     *
     * - **用户信息**（`/users/user/info` 的解析结果）→「我的」页顶栏、会员页的身份卡片。
     *   顺带把封禁位按掉：那一位为真时应用会走账号异常那一支。
     * - **实时会员校验**（`/users/user/check-member` 的解析结果）→ 闸门的判据。
     *   [vip_gate] 关掉时，这一项让闸门自己也能判出「是会员」。
     *
     * 改的是**构造参数**不是字段：这两个都是 data class，字段全是 final，
     * 而参数是一一对应赋进去的，在 before 里改参数等价、且不用去写 final 字段。
     * 下标由参数类型的形状推出来，见 [Zenneko.userInfoConstructor]。
     */
    private fun HookScope.installLifetime() {
        val expire = string(KEY_EXPIRE)?.takeIf { it.isNotBlank() } ?: Zenneko.DEFAULT_EXPIRE
        val status = int(KEY_STATUS, Zenneko.DEFAULT_STATUS)
        if (!Zenneko.isPermanent(expire)) {
            log.w("到期时间 $expire 的年份早于 ${Zenneko.PERMANENT_YEAR}，界面会显示成具体到期日而不是永久")
        }

        var patched = 0
        patched += patchUserInfo(expire, status)
        patched += patchMemberCheck(expire, status)
        if (patched == 0) error("两个会员模型都没定位到，永久会员未生效")
        log.i("永久会员已生效：到期时间 $expire，状态码 $status")
    }

    private fun HookScope.patchUserInfo(expire: String, status: Int): Int {
        val clazz = refs?.userInfo ?: run {
            log.w("定位不到用户信息模型，「我的」页仍会显示普通用户")
            return 0
        }
        val ctor = Zenneko.userInfoConstructor(clazz) ?: run {
            log.w("${clazz.name} 的构造器形状不对，跳过")
            return 0
        }
        val types = ctor.parameterTypes
        val statusIndex = Zenneko.statusIndex(types)
        val expireIndex = statusIndex + 1
        val memberIndex = Zenneko.memberIndex(types)
        val bannedIndex = Zenneko.bannedIndex(types)
        if (statusIndex < 0 || types.getOrNull(expireIndex) != String::class.java) {
            log.w("${clazz.name} 的会员字段认不出来，跳过")
            return 0
        }

        ctor.createBeforeHook("zenneko.lifetime.user_info") { param ->
            param.args[statusIndex] = status
            param.args[expireIndex] = expire
            param.args[memberIndex] = true
            param.args[bannedIndex] = false
        }
        log.i("用户信息已改写：${clazz.name}（status@$statusIndex, expire@$expireIndex, isMember@$memberIndex）")
        return 1
    }

    private fun HookScope.patchMemberCheck(expire: String, status: Int): Int {
        val clazz = refs?.memberCheck ?: run {
            log.w("定位不到会员校验模型，服务器仍会被如实转达为非会员")
            return 0
        }
        val ctor = Zenneko.memberCheckConstructor(clazz) ?: run {
            log.w("${clazz.name} 的构造器形状不对，跳过")
            return 0
        }

        ctor.createBeforeHook("zenneko.lifetime.member_check") { param ->
            param.args[0] = status
            param.args[1] = expire
            param.args[2] = true
        }
        log.i("会员校验结果已改写：${clazz.name}")
        return 1
    }

    /**
     * `libcore.so` 的环境自检。
     *
     * 它做三件事：把 APK 签名摘要和一个写死的 SHA-256 比对、读 `/proc/self/status`、
     * 读 `/proc/self/maps`。Java 侧**丢掉了它的返回值**，所以它不会直接让应用退出；
     * 它真正的作用是决定同一个库里 `getSnapwcPublicKey()` 还给不给出那把公钥 ——
     * 那把公钥是去水印那条链上用的。
     *
     * Dobby 按名字解析：这个函数是**静态注册**的 JNI（`Java_…_checkSecurityEnvironment`
     * 就在导出符号表里），不需要 `RegisterNatives` 监视那一套。
     *
     * 默认关闭，因为常量返回会**连同副作用一起跳过**。先用 [log_member] 看它在本机
     * 的真实返回值，确认确实判了异常再开。
     */
    private fun HookScope.installNativeEnv() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        val value = int(KEY_NATIVE_OK, DEFAULT_NATIVE_OK).toLong()

        val address = NativeHook.findSymbol(Zenneko.NATIVE_LIBRARY, Zenneko.NATIVE_SECURITY_CHECK)
        if (address == 0L) {
            error("${Zenneko.NATIVE_LIBRARY} 里解析不到 ${Zenneko.NATIVE_SECURITY_CHECK}（库可能还没加载）")
        }
        if (!NativeHook.returnConstant(address, value)) {
            error("环境自检没挂上（Dobby 返回失败）")
        }
        log.i("原生环境自检已钉为 $value @0x${address.toString(16)}")
    }

    /**
     * 排查用。
     *
     * 优先级压低，让它跑在上面几项**之后** —— 看到的是最终值，而不是应用原本的值。
     */
    private fun HookScope.installLog() {
        val current = refs
        current?.gate?.createAfterHook("zenneko.log.gate", priority = -100) { param ->
            log.i("闸门裁决 -> ${param.result}")
        } ?: log.w("定位不到会员闸门，裁决记录不可用")

        current?.memberCheck?.let { clazz ->
            Zenneko.memberCheckConstructor(clazz)?.createAfterHook(
                "zenneko.log.member_check",
                priority = -100,
            ) { param ->
                log.i("会员校验 -> ${param.thisObject}")
            }
        }

        current?.userInfo?.let { clazz ->
            Zenneko.userInfoConstructor(clazz)?.createAfterHook(
                "zenneko.log.user_info",
                priority = -100,
            ) { param ->
                log.i("用户信息 -> ${param.thisObject}")
            }
        }

        logNativeCheck()
    }

    /**
     * 原生自检的真实返回值，以及那把公钥取不取得出来。
     *
     * 只记一次：应用在 `Application.onCreate` 里调它，之后不再调；而公钥是懒取的，
     * 第一次用到去水印时才会问。所以这里主动各问一次，把两个答案一起摆出来 ——
     * 这正是判断 [native_env] 该不该开的依据。
     */
    private fun HookScope.logNativeCheck() {
        if (!nativeLogged.compareAndSet(false, true)) return
        val bridge = classOrNull(Zenneko.NATIVE_BRIDGE) ?: run {
            log.w("找不到 ${Zenneko.NATIVE_BRIDGE}，原生自检无法记录")
            return
        }
        val instance = runCatching {
            bridge.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()

        val verdict = runCatching {
            bridge.findMethodOrNull { name("checkSecurityEnvironment"); noParams() }
                ?.apply { isAccessible = true }
                ?.invoke(instance)
        }.getOrElse { "调用失败：${it.cause?.message ?: it.message}" }

        val key = runCatching {
            bridge.findMethodOrNull { name("getSnapwcPublicKeySafe"); noParams() }
                ?.apply { isAccessible = true }
                ?.invoke(instance) as? String
        }.getOrNull()

        log.i(
            "原生环境自检 -> $verdict；内置公钥 " +
                if (key.isNullOrEmpty()) "取不到（去水印可能受影响，可开启「原生环境自检置正常」）"
                else "正常（${key.length} 字符）",
        )
    }

    private companion object {
        /** 会员到期时间，`yyyy-MM-dd HH:mm:ss`。年份 >= 2099 才会显示成永久。 */
        const val KEY_EXPIRE = "member_expire"

        /** 会员状态码。界面只判 `>= 1`。 */
        const val KEY_STATUS = "member_status"

        /** 原生环境自检要返回的值。 */
        const val KEY_NATIVE_OK = "native_ok"
        const val DEFAULT_NATIVE_OK = 0
    }
}
