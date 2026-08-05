package com.chuanyi.hooker.hookers.astraflow

import android.content.SharedPreferences
import com.chuanyi.hooker.core.HookScope
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.lang.reflect.Method

/**
 * 目标内部结构的解析层。
 *
 * `com.astraflow.tool.*` 没有混淆，`defpackage.*` 被 R8 重命名过 —— 所以能按名字
 * 锚定的一律按名字，其余按**形状**从锚点推导出来，尽量不写死重命名后的名字。
 *
 * 反射统一走 KavaRef：条件式查找配 `optional(silent = true)`，找不到就是 null，
 * 不抛异常，调用方决定要不要放弃。
 */
internal object AstraFlow {

    // --- 锚点：这些名字在包里是明文的 -------------------------------------
    const val APP = "com.astraflow.tool.AstraFlowApp"
    const val ME_RESPONSE = "com.astraflow.tool.api.MeResponse"
    const val STARDUST_INFO = "com.astraflow.tool.api.StarDustInfo"
    const val DEVICE_PASS_INFO = "com.astraflow.tool.api.DevicePassInfo"

    /** 原生验签的 Java 门面，方法全是 `RegisterNatives` 动态绑定上去的。 */
    const val NATIVE_VERIFY = "com.astraflow.tool.security.NativeVerify"

    /**
     * `libastraflow_verify.so` 里两个直接给出「验过了」的方法，都返回 boolean。
     *
     * - `nVrfFinal` —— 三步验签的最后一步，`NativeVerify.COM2` 的返回值就是它
     * - `nVerifyEnt` —— 单步版本，同一件事的另一个入口
     *
     * 同一个类上另外几个（`nVrfStep1` / `nVrfStep2` / `nDeviceFpHashV2` /
     * `nApkSigDigest` / `nSignXAfSig`）**不能**这样处理：它们返回 `byte[]` 或
     * `String`，常量桩只能塞一个寄存器宽的值，塞给 Java 就是个伪造的对象引用。
     * 它们照常执行 —— 前两步本来也只是把负载和签名揉成中间量，真正下结论的是
     * `nVrfFinal`。
     */
    val NATIVE_VERDICTS = listOf("nVrfFinal", "nVerifyEnt")

    // --- 权益镜像的键。这些是跨进程约定，写在被注入进程读的共享配置里 ------
    const val KEY_HMAC = "vault_hmac_v2"
    const val KEY_PASS_STATE = "vault_pass_state"
    const val KEY_PASS_CLASS = "vault_pass_class"
    const val KEY_DEVICE_ID_HASH = "vault_device_id_hash"
    const val KEY_FEATURE_BITS = "vault_feature_bits"
    const val KEY_PASS_EXPIRES_AT = "vault_pass_expires_at"
    const val KEY_PASS_GENERATION = "vault_pass_generation"
    const val KEY_REVOCATION_GEN = "vault_revocation_gen"
    const val KEY_ROLE = "vault_role"
    const val KEY_LAST_VERIFY_AT = "vault_last_verify_at"

    /** 永久版的两个取值，镜像校验按字面量比对。 */
    const val PASS_STATE_PERMANENT = "permanent_active"
    const val PASS_CLASS_PERMANENT = "permanent"

    /** 五个付费功能各占一位（第六个是免费的），全开即 0b11111。 */
    const val ALL_FEATURE_BITS = 31

    // -----------------------------------------------------------------------

    /**
     * 权益保险库的实例。
     *
     * 从 `AstraFlowApp.getEntitlementVault()` 取 —— 这个方法名没被混淆，而它的
     * 返回类型就是保险库类，于是重命名后的类名一个都不用写死。
     */
    fun vault(scope: HookScope): Any? {
        val app = scope.appContextOrNull() ?: return null
        val appClass = scope.classOrNull(APP) ?: return null
        if (!appClass.isInstance(app)) return null
        return app.asResolver()
            .optional(silent = true)
            .firstMethodOrNull { name = "getEntitlementVault"; emptyParameters() }
            ?.invoke()
    }

    /**
     * 保险库上的逐功能门控：唯一一个「收一个枚举、返回 boolean」的方法。
     *
     * 按形状认而不是按名字认 —— 名字是 R8 生成的，形状不是。
     */
    fun vaultGate(vaultClass: Class<*>): Method? =
        vaultClass.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                returnType = Boolean::class.javaPrimitiveType
                parameterCount = 1
                parameters { it.size == 1 && it[0].isEnum }
            }?.self

    /**
     * 设备指纹哈希的取值函数。
     *
     * 保险库持有若干个「无参 invoke()」的小对象，其中一个返回设备指纹字符串
     * （原生实现，靠 root 读硬件序列号）。挨个试，谁返回 String 谁就是。
     *
     * 拿不到也不影响解锁：镜像里的 deviceIdHash 只参与 HMAC 计算，被注入进程
     * 那一侧并不会拿它和本机比对。
     */
    fun deviceIdHash(vault: Any): String? = suppliersOf(vault, String::class.java)
        .firstNotNullOfOrNull { it as? String }
        ?.takeIf { it.isNotBlank() }

    /**
     * 被注入进程读的那份共享配置。
     *
     * 保险库里有两份 SharedPreferences：一份是本地的（走 Lazy.getValue()），
     * 一份是跨进程共享的（走无参 invoke()）。这里只要后者。
     */
    fun sharedPreferences(vault: Any): SharedPreferences? =
        suppliersOf(vault, SharedPreferences::class.java)
            .firstNotNullOfOrNull { it as? SharedPreferences }

    /**
     * 遍历保险库的字段，把每个「无参 invoke() 且返回 [type]」的结果取出来。
     *
     * 目标是 Kotlin 写的，这些字段都是 function 对象；名字混淆了，但
     * 「无参 invoke()」这个形状没有。
     */
    private fun suppliersOf(vault: Any, type: Class<*>): List<Any?> =
        vault.javaClass.declaredFields.mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                val holder = field.get(vault) ?: return@runCatching null
                val invoke = holder.javaClass.resolve()
                    .optional(silent = true)
                    .firstMethodOrNull { name = "invoke"; emptyParameters() }
                    ?: return@runCatching null
                invoke.of(holder).invoke()?.takeIf { type.isInstance(it) }
            }.getOrNull()
        }

    /**
     * 用目标自己的实现算镜像 HMAC。
     *
     * 密钥是两个静态数组逐字节异或出来的，整个在 dex 里 —— 但没必要把它抄过来：
     * 直接调它自己的函数，密钥、消息格式、截断长度就都不会随版本漂移。
     *
     * 特征：`static String (String, String, String, int, long, int, int, String, long)`，
     * 全包唯一。
     */
    fun signMirror(
        scope: HookScope,
        passState: String,
        passClass: String,
        deviceIdHash: String,
        featureBits: Int,
        expiresAt: Long,
        passGeneration: Int,
        revocationGen: Int,
        role: String,
        lastVerifyAt: Long,
    ): String? {
        val signer = mirrorSigner(scope) ?: return null
        return runCatching {
            signer.invoke(
                null,
                passState, passClass, deviceIdHash, featureBits, expiresAt,
                passGeneration, revocationGen, role, lastVerifyAt,
            ) as? String
        }.getOrNull()
    }

    /** 缓存：一个进程里只定位一次。定位过程见 [AstraFlowDex]。 */
    @Volatile
    private var cachedSigner: Method? = null

    private fun mirrorSigner(scope: HookScope): Method? {
        cachedSigner?.let { return it }
        return AstraFlowDex.signer(scope)?.also { cachedSigner = it }
    }

    /**
     * 在 [holder] 里按形状取签名函数：那个九参数的静态方法，全包唯一。
     *
     * 供 [AstraFlowDex] 在「已经知道类名」的几条路上复用 —— 类名可能来自设置项、
     * 缓存或写死的候选，但确认它确实是那个函数的判据只有形状。
     */
    fun signerIn(holder: Class<*>): Method? =
        holder.resolve()
            .optional(silent = true)
            .firstMethodOrNull {
                returnType = String::class.java
                parameters(
                    String::class.java, String::class.java, String::class.java,
                    Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    String::class.java, Long::class.javaPrimitiveType!!,
                )
            }?.self

    /** 覆盖 [AstraFlowDex] 的定位结果，不重新出包就能纠正一次误判。 */
    const val KEY_SIGNER_CLASS = "signer_class"
}
