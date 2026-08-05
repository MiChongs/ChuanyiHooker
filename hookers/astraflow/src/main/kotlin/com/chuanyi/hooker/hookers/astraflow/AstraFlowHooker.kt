package com.chuanyi.hooker.hookers.astraflow

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 星流 AstraFlow (`com.astraflow.tool`) —— 灵动岛 / 流体云增强，自身也是个
 * Xposed 模块，付费功能实际渲染在 SystemUI 与一批 ColorOS 包里。
 *
 * 授权是**两道门，机制不同**：
 *
 * 1. 应用进程 —— 权益保险库。要求服务端签发的设备通行证经原生库验签、设备指纹
 *    （root 读硬件序列号）匹配、7 天内校验过、功能位命中。签名负载伪造不了，
 *    只能绕过：[unlock] 直接压住逐功能的判定。
 * 2. 被注入进程 —— 读一份跨进程共享的权益镜像，用 HmacSHA256 自校验。这一侧
 *    **没有原生验签**，密钥又整个在包里，所以镜像是可以自洽伪造的：[devicePass]
 *    调目标自己的签名函数算出 HMAC 写回去，被注入进程照常校验通过，
 *    我们一行代码都不用注入进 SystemUI。
 *
 * 镜像里的 `lastVerifyAt` 被写成未来时间：那一侧只检查「距上次校验不超过 48 小时」，
 * 未来值让这个差恒为负，于是镜像永不过期，不需要定期续写。
 *
 * 反射统一走 KavaRef（见 [AstraFlow]）。
 */
class AstraFlowHooker : AppHooker {

    override val id = "astraflow"
    override val displayName = "AstraFlow"
    override val description = "灵动岛增强，解锁全部付费功能"
    override val targetPackages = setOf("com.astraflow.tool")

    private val mirrorWritten = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "unlock",
            title = "解锁全部付费功能",
            summary = "录屏、常驻流体云、歌词滚动、动态背景、手势侧边栏 —— " +
                "让应用一律按已购买处理",
            install = { installUnlock() },
        ),
        HookFeature(
            id = "device_pass",
            title = "写入永久授权凭证",
            summary = "付费功能真正生效的地方在系统界面进程，那边只认一份本地凭证。" +
                "这一项补上一份永不过期的凭证，功能才会真的显示出来。" +
                "凭证会留在应用数据里，关掉本模块后依然有效",
            install = { installDevicePass() },
        ),
        HookFeature(
            id = "lifetime",
            title = "显示为永久版",
            summary = "把账号页的等级与到期时间显示成永久，不再提示订阅",
            install = { installLifetimeTier() },
        ),
        HookFeature(
            id = "stardust",
            title = "星尘无限",
            summary = "把星尘余额显示为极大值，兑换与扣费都不会把它用尽",
            install = { installStarDust() },
        ),
        HookFeature(
            id = "native_verify",
            title = "接管原生授权校验",
            summary = "让应用自己的原生库对凭证给出「通过」，而不是绕开这一步。" +
                "解锁本身上面两项已经做到，这一项默认关闭，" +
                "留给应用更新后判定形状变化的情况",
            defaultEnabled = false,
            install = { installNativeVerify() },
        ),
        HookFeature(
            id = "log_entitlement",
            title = "记录权益判定",
            summary = "排查用：记录每一次功能是否放行的判定结果",
            defaultEnabled = false,
            install = { installEntitlementLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        if (scope.classOrNull(AstraFlow.APP) == null) {
            scope.log.w("找不到 ${AstraFlow.APP}，应用可能已更新")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("AstraFlow ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 应用进程这一侧的门。
     *
     * 保险库上「收一个枚举、返回 boolean」的方法只有一个，就是逐功能的放行判定；
     * 常量化它，等于跳过设备指纹、原生验签、有效期、功能位这一整串检查。
     */
    private fun HookScope.installUnlock() {
        val gate = vaultGate() ?: error("找不到权益判定方法")
        gate.createReturnConstantHook("astraflow.unlock", true)
        log.i("权益判定已常量化：${gate.declaringClass.name}.${gate.name}")
    }

    /**
     * 被注入进程这一侧的门。
     *
     * 那边不认内存状态，只认共享配置里的一份凭证 + HMAC。HMAC 用目标自己的
     * 签名函数算 —— 密钥和消息格式都跟着它走，版本更新也不会算错。
     *
     * 触发点选保险库的取值方法：它一定在应用起来之后、任何功能判定之前被调用，
     * 而且此时共享配置已经就绪。只写一次。
     */
    private fun HookScope.installDevicePass() {
        val getter = vaultGetter() ?: error("找不到权益保险库")
        getter.createAfterHook("astraflow.device_pass") { param ->
            val vault = param.result ?: return@createAfterHook
            if (!mirrorWritten.compareAndSet(false, true)) return@createAfterHook
            runCatching { writeMirror(vault) }
                .onFailure {
                    mirrorWritten.set(false)
                    log.e("写入授权凭证失败", it)
                }
        }
        log.i("授权凭证写入已就绪")
    }

    private fun HookScope.writeMirror(vault: Any) {
        val prefs = AstraFlow.sharedPreferences(vault)
            ?: run { log.w("拿不到共享配置，跳过凭证写入"); return }

        // 这一侧不会把它和本机指纹比对，只作为 HMAC 的输入；拿得到就用真的，
        // 拿不到（未授 root）也不影响校验通过。
        val deviceIdHash = AstraFlow.deviceIdHash(vault) ?: FALLBACK_DEVICE_HASH
        val role = string(KEY_ROLE)?.takeIf { it.isNotBlank() } ?: DEFAULT_ROLE
        val now = System.currentTimeMillis()
        // 未来时间：那一侧只判「now - lastVerifyAt < 48h」，负数恒成立 => 永不过期。
        val lastVerifyAt = now + NEVER_STALE_MS
        val expiresAt = now / 1000 + NEVER_EXPIRES_SEC

        val hmac = AstraFlow.signMirror(
            scope = this,
            passState = AstraFlow.PASS_STATE_PERMANENT,
            passClass = AstraFlow.PASS_CLASS_PERMANENT,
            deviceIdHash = deviceIdHash,
            featureBits = AstraFlow.ALL_FEATURE_BITS,
            expiresAt = expiresAt,
            passGeneration = PASS_GENERATION,
            revocationGen = REVOCATION_GEN,
            role = role,
            lastVerifyAt = lastVerifyAt,
        ) ?: run { log.w("凭证签名失败，跳过写入"); return }

        prefs.edit()
            .putString(AstraFlow.KEY_PASS_STATE, AstraFlow.PASS_STATE_PERMANENT)
            .putString(AstraFlow.KEY_PASS_CLASS, AstraFlow.PASS_CLASS_PERMANENT)
            .putString(AstraFlow.KEY_DEVICE_ID_HASH, deviceIdHash)
            .putInt(AstraFlow.KEY_FEATURE_BITS, AstraFlow.ALL_FEATURE_BITS)
            .putLong(AstraFlow.KEY_PASS_EXPIRES_AT, expiresAt)
            .putInt(AstraFlow.KEY_PASS_GENERATION, PASS_GENERATION)
            .putInt(AstraFlow.KEY_REVOCATION_GEN, REVOCATION_GEN)
            .putString(AstraFlow.KEY_ROLE, role)
            .putLong(AstraFlow.KEY_LAST_VERIFY_AT, lastVerifyAt)
            .putString(AstraFlow.KEY_HMAC, hmac)
            .apply()

        log.i("已写入永久授权凭证（功能位 ${AstraFlow.ALL_FEATURE_BITS}）")
    }

    /** 账号页读到的等级与到期时间，改成永久。 */
    private fun HookScope.installLifetimeTier() {
        val tier = string(KEY_TIER)?.takeIf { it.isNotBlank() } ?: DEFAULT_TIER
        val farFuture = System.currentTimeMillis() / 1000 + NEVER_EXPIRES_SEC

        classOrNull(AstraFlow.ME_RESPONSE)?.let { me ->
            me.declaredConstructors.forEach { ctor ->
                ctor.createAfterHook("astraflow.lifetime.me") { param ->
                    val self = param.thisObjectOrNull ?: return@createAfterHook
                    setField(self, "tier", tier)
                    setField(self, "expiresAt", farFuture)
                }
            }
        } ?: log.w("找不到 ${AstraFlow.ME_RESPONSE}")

        classOrNull(AstraFlow.DEVICE_PASS_INFO)?.let { pass ->
            pass.declaredConstructors.forEach { ctor ->
                ctor.createAfterHook("astraflow.lifetime.pass") { param ->
                    val self = param.thisObjectOrNull ?: return@createAfterHook
                    setField(self, "state", AstraFlow.PASS_STATE_PERMANENT)
                    setField(self, "passClass", AstraFlow.PASS_CLASS_PERMANENT)
                    setField(self, "expiresAt", farFuture)
                    setField(self, "permanentRemainingCost", 0)
                }
            }
        } ?: log.w("找不到 ${AstraFlow.DEVICE_PASS_INFO}")

        log.i("等级显示为 $tier / 永久")
    }

    /**
     * 星尘余额。
     *
     * 改构造之后的字段而不是各个读取点：余额对象是不可变数据类，每次刷新都会
     * 重新构造，覆盖构造结果就覆盖了所有消费方。
     */
    private fun HookScope.installStarDust() {
        val balance = int(KEY_BALANCE, DEFAULT_BALANCE)
        val info = classOrNull(AstraFlow.STARDUST_INFO) ?: error("找不到星尘余额")
        info.declaredConstructors.forEach { ctor ->
            ctor.createAfterHook("astraflow.stardust") { param ->
                val self = param.thisObjectOrNull ?: return@createAfterHook
                setField(self, "balance", balance)
                setField(self, "estimatedMonthlyDebitTotal", 0)
            }
        }
        log.i("星尘余额固定为 $balance")
    }

    /**
     * 原生这一侧的门。
     *
     * `libastraflow_verify.so` 只导出 `JNI_OnLoad`，方法名还是运行时拼出来的
     * （包里搜不到 `nVrfFinal`，只有 `mfin_v1` 这类拼料），所以名字和函数指针
     * 同时存在的地方只有 `RegisterNatives` 那一次调用 —— 拦它，按名字把验签结论
     * 换成常量桩。
     *
     * 换的是 `JNINativeMethod` 数组里的指针，在 ART 绑定之前，**原生库自己一个
     * 字节都没被改** —— 那个库会读 `/proc/self/maps`，少给它一处可看的东西。
     *
     * 必须赶在应用加载那个库之前装上，所以这一项改完要重启应用才生效。
     */
    private fun HookScope.installNativeVerify() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(settings.isVerbose())
        if (!NativeHook.watchJniRegistrations()) error("RegisterNatives 监视装不上")

        val forced = AstraFlow.NATIVE_VERDICTS.filter {
            NativeHook.returnConstantOnJniRegister(AstraFlow.NATIVE_VERIFY, it, JNI_TRUE)
        }
        if (forced.isEmpty()) error("原生验签接管失败")
        log.i("原生验签已挂号：${forced.joinToString()}，等 ${AstraFlow.NATIVE_VERIFY} 加载")
    }

    private fun HookScope.installEntitlementLog() {
        val gate = vaultGate() ?: error("找不到权益判定方法")
        val dumped = AtomicBoolean(false)
        gate.createAfterHook("astraflow.log.gate") { param ->
            log.i("权益判定 ${param.arg(0)} -> ${param.result}")
            // 判定跑过一次，原生库就一定加载过了 —— 这时候捞注册表才有东西。
            if (dumped.compareAndSet(false, true)) logNativeRegistrations()
        }
    }

    private fun HookScope.logNativeRegistrations() {
        val entries = NativeHook.jniRegistrations()
            .filter { it.className == AstraFlow.NATIVE_VERIFY }
        if (entries.isEmpty()) {
            log.d("没有捕获到 ${AstraFlow.NATIVE_VERIFY} 的原生方法（未开启接管原生授权校验？）")
            return
        }
        entries.forEach { log.i("原生方法 ${it.methodName}${it.signature} @ 0x${it.address.toString(16)}") }
    }

    // -----------------------------------------------------------------------

    /** `AstraFlowApp.getEntitlementVault()`，名字没被混淆，是整条链的锚点。 */
    private fun HookScope.vaultGetter(): Method? =
        classOrNull(AstraFlow.APP)
            ?.resolve()
            ?.optional(silent = true)
            ?.firstMethodOrNull { name = "getEntitlementVault"; emptyParameters() }
            ?.self

    /** 保险库类由取值方法的返回类型给出，不用写死重命名后的类名。 */
    private fun HookScope.vaultGate(): Method? =
        vaultGetter()?.returnType?.let(AstraFlow::vaultGate)

    private fun HookScope.setField(target: Any, name: String, value: Any?) {
        val ok = runCatching {
            target.javaClass.resolve()
                .optional(silent = true)
                .firstFieldOrNull { this.name = name }
                ?.of(target)
                ?.apply { set(value) } != null
        }.getOrDefault(false)
        if (!ok) log.d("字段 $name 写入失败（${target.javaClass.simpleName}）")
    }

    private companion object {
        const val KEY_TIER = "tier"
        const val KEY_ROLE = "role"
        const val KEY_BALANCE = "stardust_balance"

        const val DEFAULT_TIER = "supporter"
        const val DEFAULT_ROLE = "user"

        /** 足够大又不至于让界面上的数字排版炸掉。 */
        const val DEFAULT_BALANCE = 999_999

        /** 凭证代数：镜像自校验只把它算进 HMAC，取值本身不参与判定。 */
        const val PASS_GENERATION = 1
        const val REVOCATION_GEN = 0

        /** 约 100 年，秒。 */
        const val NEVER_EXPIRES_SEC = 3_155_760_000L

        /** 约 100 年，毫秒。写进 lastVerifyAt 让「距上次校验」恒为负。 */
        const val NEVER_STALE_MS = 3_155_760_000_000L

        /** 读不到硬件指纹时的占位；那一侧不与本机比对，只当 HMAC 输入。 */
        const val FALLBACK_DEVICE_HASH = "chuanyi-hooker"

        /** JNI 的 `JNI_TRUE`。常量桩把它放进返回寄存器，Java 侧读到的就是 true。 */
        const val JNI_TRUE = 1L
    }
}
