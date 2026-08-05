package com.chuanyi.hooker.hookers.bridgeaudio

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isActivityAction
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isContextLong
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isContextPredicate
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isPublicNoArgAction
import com.chuanyi.hooker.hookers.bridgeaudio.BridgeAudio.isUnlockWriter
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 把授权链路上的成员找出来。**两条路，名字优先**。
 *
 * ## 为什么名字优先
 *
 * 1.0.1 (vc11) 的 dex 里 `com.airplay.streamer.**` 完全没被重命名 —— R8 只处理了
 * kotlin stdlib、androidx 与 Play 结算那几个库（它们被压成了 `p034o0` / `A` / `B0`）。
 * 名字既然是真的，直接取就是最准的：不用扫 dex，不用等 DexKit 的 `.so`，启动路径上
 * 一毫秒都不花。
 *
 * ## DexKit 负责的是「名字不再是真的」那一天
 *
 * 退路不按类名找，按**落盘键与商品 ID**找：
 *
 * | 锚点 | 出现在 |
 * |---|---|
 * | `billing_full_unlock` | `isUnlocked` 读它，`setUnlocked` 写它 |
 * | `billing_trial_remaining_ms` | `remainingTrialMs` / `markTrialProgress` |
 * | `Already unlocked` | `launchUnlockPurchase`，用来认出结算封装那个类 |
 * | `BillingState(connected=` | 那个数据类的 `toString()` |
 *
 * 前两个是 SharedPreferences 的键：改一次，所有已购用户下次启动就读不到自己的解锁
 * 状态，代价大到厂商不会顺手动它 —— 这比任何类名都稳。锚点一律用
 * [StringMatchType.Equals]，包含匹配会连上 R8 合并出来的共享类。
 *
 * 命中之后仍然按签名复核一遍（见 [BridgeAudio] 里那组形状判据）：一个串可能被同一个类
 * 里的好几个方法引用，`billing_full_unlock` 就同时属于读和写两端，只有形状分得开它们。
 *
 * 不做扫描结果缓存：这个包只有一个 5.4 MB 的 dex，DexKit 扫完在几十毫秒量级，
 * 缓存省下的时间还不够它自己校验一遍。
 */
internal object BridgeAudioDex {

    /**
     * 授权链路上要用到的全部引用。
     *
     * 除 [isUnlocked] 之外任一为 null 都只让对应功能降级，不连累别的功能 ——
     * [isUnlocked] 是唯一的硬要求，见 [BridgeAudioHooker.onHook]。
     */
    class Refs(
        /** `TrialAccess`，Kotlin `object`。 */
        val trialAccess: Class<*>? = null,
        /** `TrialAccess.isUnlocked(Context): Boolean` —— 全应用权益判定的唯一入口。 */
        val isUnlocked: Method? = null,
        /** `TrialAccess.canStartFreeTrial(Context): Boolean` */
        val canStartFreeTrial: Method? = null,
        /** `TrialAccess.remainingTrialMs(Context): Long` */
        val remainingTrialMs: Method? = null,
        /** `TrialAccess.setUnlocked(Context, Boolean)` —— 写 `billing_full_unlock`。 */
        val setUnlocked: Method? = null,
        /** `BillingRepository` 的 `connect()` 与 `queryPurchases()`。 */
        val billingActions: List<Method> = emptyList(),
        /** `BillingState(Boolean, Boolean, String?, Boolean, String?)`，末位是 `message`。 */
        val billingStateCtor: Constructor<*>? = null,
    ) {
        fun describe(): String = buildString {
            fun line(label: String, value: String?) {
                append("\n  ").append(label).append(' ').append(value ?: "未定位")
            }
            fun line(label: String, m: Method?) =
                line(label, m?.let { "${it.declaringClass.name}.${it.name}" })

            line("权益判定 isUnlocked       ", isUnlocked)
            line("试用可用 canStartFreeTrial", canStartFreeTrial)
            line("试用剩余 remainingTrialMs ", remainingTrialMs)
            line("权益写入 setUnlocked      ", setUnlocked)
            line(
                "结算动作 connect/query    ",
                billingActions.takeIf { it.isNotEmpty() }
                    ?.joinToString { "${it.declaringClass.name}.${it.name}()" },
            )
            line("状态构造 BillingState     ", billingStateCtor?.declaringClass?.name)
        }
    }

    fun resolve(scope: HookScope): Refs {
        val direct = byName(scope)
        if (direct.isUnlocked != null && direct.setUnlocked != null) {
            scope.log.d("按名字直接取到授权链路${direct.describe()}")
            return direct
        }

        scope.log.w("按名字没取全，改扫 dex（应用大概率换了混淆配置）")
        val scanned = byAnchor(scope, direct)
        scope.log.i("授权链路定位结果${scanned.describe()}")
        return scanned
    }

    // -----------------------------------------------------------------------
    // 名字这条路
    // -----------------------------------------------------------------------

    private fun byName(scope: HookScope): Refs {
        val trial = scope.classOrNull(BridgeAudio.TRIAL_ACCESS)
        val repository = scope.classOrNull(BridgeAudio.BILLING_REPOSITORY)
        val state = scope.classOrNull(BridgeAudio.BILLING_STATE)

        // 名字对上还要形状对上：只对上名字说明它已经被重命名成了别的东西。
        fun pick(name: String, shape: (Method) -> Boolean): Method? =
            trial?.declaredMethods?.firstOrNull { it.name == name && shape(it) }

        return Refs(
            trialAccess = trial,
            isUnlocked = pick("isUnlocked") { it.isContextPredicate() },
            canStartFreeTrial = pick("canStartFreeTrial") { it.isContextPredicate() },
            remainingTrialMs = pick("remainingTrialMs") { it.isContextLong() },
            setUnlocked = pick("setUnlocked") { it.isUnlockWriter() },
            billingActions = billingActionsIn(repository),
            billingStateCtor = messageCtorIn(state),
        )
    }

    /**
     * 结算封装里那两个无参动作。
     *
     * 按形状取整组而不是按名字取两个：见 [BridgeAudio.isPublicNoArgAction] 的说明，
     * 这个类里满足该形状的就是 `connect()` 和 `queryPurchases()`。
     */
    private fun billingActionsIn(repository: Class<*>?): List<Method> =
        repository?.declaredMethods?.filter { it.isPublicNoArgAction() }.orEmpty()

    /**
     * `BillingState` 里带 `message` 的那个构造器。
     *
     * 五个参数、最后一个是 `String` —— 另一个是 Kotlin 默认参数生成的合成构造器
     * （多两个参数），不会撞。取构造器而不是 `getMessage()`：那个类有两个
     * `() -> String` 的取值方法（`getPrice` 和 `getMessage`），混淆之后分不开，
     * 而构造器的参数表是有序的，末位就是 `message`。
     */
    private fun messageCtorIn(state: Class<*>?): Constructor<*>? =
        state?.declaredConstructors?.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 5 && types[4] == String::class.java && types[2] == String::class.java
        }

    // -----------------------------------------------------------------------
    // 锚点这条路
    // -----------------------------------------------------------------------

    private fun byAnchor(scope: HookScope, direct: Refs): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return direct
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而 LSPosed
        // 给模块构造的 classloader 下那条路不通。症状有迷惑性：加载当时不报错，等到第一次
        // 调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块自己那套
        // 带绝对路径兜底的加载器装进来；soname 一个进程只加载一次，DexKit 自己那次随后
        // 就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return direct
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return direct

        return bridge.use { dex ->
            fun anchored(anchor: String): List<Method> {
                val hits = runCatching {
                    dex.findMethod { matcher { addUsingString(anchor, StringMatchType.Equals) } }
                }.onFailure { scope.log.w("扫 '$anchor' 失败：${it.message}") }.getOrNull().orEmpty()

                if (hits.isEmpty()) scope.log.w("dex 里找不到引用 '$anchor' 的方法")
                return hits.mapNotNull { hit ->
                    runCatching { hit.getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.d("${hit.descriptor} 反射不出来：${it.message}") }
                        .getOrNull()
                }
            }

            // 一次查询同时拿到读端和写端 —— 全包只有这两处碰 billing_full_unlock。
            val unlockSites = anchored(BridgeAudio.KEY_FULL_UNLOCK)
            val isUnlocked = direct.isUnlocked
                ?: unlockSites.firstOrNull { it.isContextPredicate() }
            val setUnlocked = direct.setUnlocked
                ?: unlockSites.firstOrNull { it.isUnlockWriter() }

            val trial = direct.trialAccess ?: isUnlocked?.declaringClass
            val remaining = direct.remainingTrialMs
                ?: anchored(BridgeAudio.KEY_TRIAL_REMAINING).firstOrNull { it.isContextLong() }

            // canStartFreeTrial 一个字符串都不引用，锚点扫不到它。它级联自 isUnlocked
            // （`isUnlocked(ctx) || remainingTrialMs(ctx) > 0`），所以按住上面两个之后
            // 它自己就是对的 —— 定位不到不影响解锁，只是少一层直接调用方的保险。
            val canStart = direct.canStartFreeTrial ?: trial?.declaredMethods
                ?.filter { it.isContextPredicate() && it != isUnlocked }
                ?.singleOrNull()
                ?: run { scope.log.d("canStartFreeTrial 无锚点可扫，交给 isUnlocked 级联"); null }

            val repository = direct.billingActions.firstOrNull()?.declaringClass
                ?: anchored(BridgeAudio.ANCHOR_ALREADY_UNLOCKED)
                    .firstOrNull { it.isActivityAction() }?.declaringClass
            val state = direct.billingStateCtor?.declaringClass
                ?: anchored(BridgeAudio.ANCHOR_BILLING_STATE).firstOrNull()?.declaringClass

            scope.log.d("dex 扫描用时 ${SystemClock.elapsedRealtime() - startedAt}ms")

            Refs(
                trialAccess = trial,
                isUnlocked = isUnlocked,
                canStartFreeTrial = canStart,
                remainingTrialMs = remaining,
                setUnlocked = setUnlocked,
                billingActions = direct.billingActions.ifEmpty { billingActionsIn(repository) },
                billingStateCtor = direct.billingStateCtor ?: messageCtorIn(state),
            )
        }
    }
}
