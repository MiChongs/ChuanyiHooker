package com.chuanyi.hooker.hookers.bridgeaudio

import android.app.Activity
import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标里那些**不该由 Kotlin 侧猜**的名字与形状，集中在这里一份。
 *
 * 分两类，寿命完全不同：
 *
 * * **落盘键与商品 ID** —— 改一次，全体已购用户的解锁状态就读不出来了，所以厂商不会
 *   随便动。[BridgeAudioDex] 的扫描锚点全部取自这一类。
 * * **类名与方法名** —— 1.0.1 (vc11) 里 `com.airplay.streamer.**` 一个字都没被 R8
 *   重命名（被改掉的只有 kotlin stdlib、androidx 和 Play 结算那几个库），所以直接
 *   按名字取是当前最省事也最准的路；它失效时才轮到上面那类锚点。
 */
internal object BridgeAudio {

    const val PKG = "app.bridgeaudio"

    // --- 应用自己的类 -------------------------------------------------------

    /** 权益与试用的唯一真相源，Kotlin `object`，成员都是实例方法。 */
    const val TRIAL_ACCESS = "com.airplay.streamer.billing.TrialAccess"

    /** Play 结算封装：连接、查询、拉起购买。 */
    const val BILLING_REPOSITORY = "com.airplay.streamer.billing.BillingRepository"

    /** 结算状态快照，界面把它的 `message` 直接弹成 Toast。 */
    const val BILLING_STATE = "com.airplay.streamer.billing.BillingState"

    // --- PairIP（Google Play 出包时注入，不参与应用自己的 R8） ---------------

    const val LICENSE_CLIENT = "com.pairip.licensecheck.LicenseClient"

    /** 判定「这次退出是许可校验发起的」用的栈帧前缀。 */
    const val PAIRIP_PREFIX = "com.pairip."

    // --- 落盘键与商品 ID（扫描锚点） ----------------------------------------

    const val PREFS = "airplay_prefs"
    const val KEY_FULL_UNLOCK = "billing_full_unlock"
    const val KEY_TRIAL_REMAINING = "billing_trial_remaining_ms"
    const val PRODUCT_ID = "bridge_audio_full_unlock"

    /** `BillingState.toString()` 的前缀，用来认那个数据类。 */
    const val ANCHOR_BILLING_STATE = "BillingState(connected="

    /** `launchUnlockPurchase` 里那句「已经解锁了」，用来认 [BILLING_REPOSITORY]。 */
    const val ANCHOR_ALREADY_UNLOCKED = "Already unlocked"

    // --- 形状 ---------------------------------------------------------------
    //
    // 混淆之后名字会变，形状不会。每个判据都只描述签名，不含任何名字。

    /** `(Context) -> boolean`：`isUnlocked` / `canStartFreeTrial` / `isDebuggable`。 */
    fun Method.isContextPredicate(): Boolean =
        returnType == Boolean::class.javaPrimitiveType &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == Context::class.java

    /** `(Context) -> long`：`remainingTrialMs` / `fullTrialMs`。 */
    fun Method.isContextLong(): Boolean =
        returnType == Long::class.javaPrimitiveType &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == Context::class.java

    /** `(Context, boolean) -> void`：`setUnlocked` / `setDebugUnlocked`。 */
    fun Method.isUnlockWriter(): Boolean =
        returnType == Void.TYPE &&
            parameterTypes.size == 2 &&
            parameterTypes[0] == Context::class.java &&
            parameterTypes[1] == Boolean::class.javaPrimitiveType

    /** `(Activity) -> void`：`launchUnlockPurchase`。 */
    fun Method.isActivityAction(): Boolean =
        returnType == Void.TYPE &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == Activity::class.java

    /**
     * `public () -> void` 的实例方法。
     *
     * [BILLING_REPOSITORY] 里满足这个形状的**只有** `connect()` 与 `queryPurchases()`
     * 两个 —— 其余公开方法都带参数（`addListener` / `removeListener` /
     * `launchUnlockPurchase`），私有的那些不在 `public` 里。所以「跳过 Google 结算」
     * 不必知道这两个方法叫什么，按形状取一整组即可。
     */
    fun Method.isPublicNoArgAction(): Boolean =
        returnType == Void.TYPE &&
            parameterCount == 0 &&
            Modifier.isPublic(modifiers) &&
            !Modifier.isStatic(modifiers)

    /** `static (Context) -> void`：`LicenseClient.checkLicense`。 */
    fun Method.isStaticContextAction(): Boolean =
        returnType == Void.TYPE &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == Context::class.java &&
            Modifier.isStatic(modifiers)

    /** 这一次调用是不是许可校验发起的 —— 只看调用栈，不认具体类名。 */
    fun isPairipCaller(): Boolean = runCatching {
        Thread.currentThread().stackTrace.any { it.className.startsWith(PAIRIP_PREFIX) }
    }.getOrDefault(false)
}
