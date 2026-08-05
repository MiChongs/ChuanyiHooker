package com.chuanyi.hooker.hookers.bridgeaudio

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.hookers.bridgeaudio.Entitlement.installLifetime
import com.chuanyi.hooker.hookers.bridgeaudio.Entitlement.installLog
import com.chuanyi.hooker.hookers.bridgeaudio.Entitlement.installPersist
import com.chuanyi.hooker.hookers.bridgeaudio.Entitlement.installSkipBilling
import com.chuanyi.hooker.hookers.bridgeaudio.LicenseGate.installLicenseGate

/**
 * Bridge Audio（`app.bridgeaudio`）1.0.1 (vc11) —— 把手机音频投到 AirPlay 音箱。
 *
 * ## 结论先写
 *
 * 这个包**没有加壳、没有服务端复核、没有对购买凭证验签、原生层没有一行授权逻辑**。
 * 唯一的 dex 是明文的，R8 只重命名了第三方库，`com.airplay.streamer.**` 一个字没动。
 *
 * 收费只有一件商品 —— `bridge_audio_full_unlock`，类型 `inapp`（一次性买断，不是订阅）。
 * 应用里没有任何过期、续订或到期回收的概念，所以「完整版」本身就是永久的，
 * 不需要另外伪造终身标记。
 *
 * 限制只有一处：未购买时每次投放累计 3 分钟（`R.integer.free_trial_seconds = 180`），
 * 用完即停流。它不是独立机制，而是「未解锁」的表现 —— 判定链见 [Entitlement]。
 *
 * ## 两个互不相干的问题，必须分开解
 *
 * ```
 * ① 权益          TrialAccess.isUnlocked()  ← 全应用唯一判定，读 SharedPreferences
 *                 └── 3 分钟试用、付费墙、磁贴、小组件、界面文案全从这里级联
 *
 * ② 能不能启动    com.pairip.licensecheck   ← Google Play 出包时注入
 *                 └── attachBaseContext 第一句就去 bind com.android.vending，
 *                     绑不上就弹错误框 + 30 秒后 System.exit(0)
 * ```
 *
 * **②与买没买无关**：即使真实购买过、即使解锁标记已经写进存档，只要 Play 商店
 * 不可绑定，应用照样起不来。所以「解锁」和「冻结 Google 也能用」是两项功能，
 * 不是一项。详见 [LicenseGate]。
 *
 * 结算本身（`BillingRepository`）在 Google 不可用时只会发布带错误文案的状态并被设置页
 * 弹成 Toast，不会崩 —— 属于噪音，由「跳过 Google 结算」处理。
 *
 * ## 为什么没有原生 hook
 *
 * `libairplay2_jni.so` 是 Rust 写的 AirPlay 2 发送端（符号里能直接看到
 * `tracing-subscriber` 与 crates.io 的构建路径），导出的 6 个 JNI 方法全是音频管线：
 * `nativeStart` / `nativeStop` / `nativePushPcm` / `nativeSetVolume` /
 * `nativeSendFeedback` / `nativeLastError`。整个 `.so` 里没有 license / trial /
 * unlock / purchase 相关的任何字符串，也没有签名或完整性校验。
 *
 * 既然原生侧没有判定点，就不该为了用 Dobby 而去补一个 —— 模块仍然依赖 `:native`，
 * 但只用它那条带绝对路径兜底的库加载器把 DexKit 的 `.so` 装起来（见 [BridgeAudioDex]）。
 */
class BridgeAudioHooker : AppHooker {

    override val id = "bridgeaudio"
    override val displayName = "Bridge Audio"
    override val description = "解锁完整版，去除试用时长限制，无 Google 服务也可用"
    override val targetPackages = setOf(BridgeAudio.PKG)

    @Volatile
    private var refs: BridgeAudioDex.Refs = BridgeAudioDex.Refs()

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "解锁完整版",
            summary = "把权益判定按成已购买。投放不再限时，付费墙、磁贴与小组件的拦截一并解除。" +
                "该商品为一次性买断，解锁即永久。只作用于运行时，不改动应用数据",
            install = { installLifetime(refs) },
        ),
        HookFeature(
            id = "license",
            title = "豁免许可校验",
            summary = "应用启动时会连接 Google Play 校验许可，连不上则弹出错误提示并在 30 秒后退出。" +
                "这一项让校验不再发起，冻结 Google 服务时仍可正常启动",
            install = { installLicenseGate() },
        ),
        HookFeature(
            id = "skip_billing",
            title = "跳过 Google 结算",
            summary = "解锁后不再连接 Play 结算，也不再显示「结算不可用」「无法载入价格」等提示。" +
                "「解锁完整版」关闭时本项自动不生效，以免购买流程不可用",
            install = { installSkipBilling(refs) },
        ),
        HookFeature(
            id = "persist",
            title = "写入解锁标记",
            summary = "用应用自己的写入方法把已购买记入其存档，停用本模块后依然解锁。" +
                "这是唯一会改动应用数据的一项，默认关闭",
            defaultEnabled = false,
            install = { installPersist(refs) },
        ),
        HookFeature(
            id = "log",
            title = "记录权益与结算",
            summary = "排查用。记录每一次权益判定的结果、试用剩余时长，以及结算状态的发布内容",
            defaultEnabled = false,
            install = { installLog(refs) },
        ),
    )

    override fun onHook(scope: HookScope) {
        scope.log.i("Bridge Audio ${scope.versionCode} in ${scope.processName}")
        refs = BridgeAudioDex.resolve(scope)

        // 权益判定是唯一的硬要求：它定位不到，「解锁完整版」和「写入解锁标记」都无从谈起。
        // 「豁免许可校验」不依赖它，所以这里不直接 error —— 抛出会让整个 hooker 停摆，
        // 连带把「冻结 Google 也能启动」一起丢掉，而那一项恰恰是应用起不来时唯一还管用的。
        if (refs.isUnlocked == null) {
            scope.log.e("没定位到权益判定 —— 解锁类功能会失败，许可豁免不受影响${refs.describe()}")
        }
    }
}
