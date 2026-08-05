package com.chuanyi.hooker.hookers.airmusic

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** 目标里那几个明文落点。 */
internal object AirMusic {
    const val PACKAGE = "app.airmusic.trial"

    /** 音频代理。类名与 JNI 方法名都是明文，是这个包上最稳的锚点。 */
    const val AUDIO_PROXY = "app.airmusic.proxy.AudioProxy"

    /** `AudioProxy` 上的原生验签方法，`(String signedData, String signature) -> boolean`。 */
    const val NATIVE_VERIFY = "h"

    const val COMMON_UTILS = "app.airmusic.util.CommonUtils"
}

/**
 * AirMusic Trial（`app.airmusic.trial`）—— 把音频投到 AirPlay / DLNA / Chromecast /
 * Sonos / Heos 等接收端的投送工具。
 *
 * ## 试用版限制到底是什么
 *
 * 只有一条，而且应用自己在 `trial_message` 里写明了：
 *
 * > *This is a **trial**-version and some noise will be added to the audio after
 * > **10 minutes** of playback. You can start another session by simply restarting this app.*
 *
 * 没有功能阉割、没有时长上限、没有设备数限制 —— 十分钟后往音频里掺正弦噪音，仅此而已。
 * 所以这个 hooker 要做的事也只有两件：**把那段噪音去掉**，以及**让授权判定恒为已授权**
 * （后者顺带消掉「验证失败」弹窗，并让应用在没有 Google 服务时也不卡在授权上）。
 *
 * ## 两道锁在两侧，缺一不可
 *
 * 这个包的授权不是一处开关，而是 Java 与原生各握一半，且**互不代理**：
 *
 * ```text
 *  Java 侧                                   原生侧 (libaudioproxy.so)
 *  ───────                                   ────────────────────────
 *  Policy.allowAccess()  ──验签──▶  AudioProxy.h(data, sig)
 *      │                                          │ 通过时
 *      │ true                                     ▼
 *      ▼                                    授权标志 = 1
 *  y6.c = TRUE                                    │
 *  （UI：不弹「验证失败」）                        ▼
 *                                          transfer() 不混噪音
 * ```
 *
 * 关键在于：**Java 侧钉住 `allowAccess()` 并不会让原生那个标志变成 1**。
 * 因为标志的唯一写入点在 `AudioProxy.h` 的原生实现内部，而 `allowAccess()` 被钉成 true
 * 之后，那次验签要么没跑、要么跑了也没通过。所以只做 Java 侧，界面是干净的，
 * 十分钟后照样出噪音 —— 这是这个目标最容易踩空的地方。
 *
 * 反过来只做原生侧也不行：噪音是没了，但 Play 连不上时应用会弹「验证失败」，
 * 并给出「关闭 AirMusic」的选项。
 *
 * 两侧的实现分别在 [AirMusicDex]（DexKit 按形状找那个被 R8 改名的裁决方法）
 * 和 [NativeGate]（AArch64 指令解码算出三个 `.data` 落点）。
 *
 * ## 不改包
 *
 * 全程走 Xposed，不重签名。这不只是省事：原生侧那道签名闸比对的是 APK 签名的 CRC，
 * 改包重签会**新增**一个它不认识的值。不动包，那道闸的语义就还是原样。
 */
class AirMusicHooker : AppHooker {

    override val id = "airmusic"
    override val displayName = "AirMusic"
    override val description = "音频投送，解锁完整版并消除试用噪音"
    override val targetPackages = setOf(AirMusic.PACKAGE)

    /** 原生闸打开过一次就不再重试。 */
    private val gateOpened = AtomicBoolean(false)

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "no_noise",
            title = "消除试用噪音",
            summary = "试用版播放满 10 分钟后会往音频里掺噪音，这一项从根上关掉它，" +
                "不必再靠重启应用来重新计时",
            install = { installNoNoise() },
        ),
        HookFeature(
            id = "lifetime",
            title = "解锁完整版（终身）",
            summary = "让应用自己的授权判定恒为「已购买」。不弹「验证失败」，" +
                "也不再需要连接 Google Play —— 服务被冻结、停用或离线都照常可用",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "hide_trial_dialog",
            title = "隐藏试用版提示",
            summary = "去掉每次进主界面弹的「Trial Version」对话框。" +
                "副作用是应用会当自己是正式版，可能出现评分邀请",
            install = { installHideTrial() },
        ),
        HookFeature(
            id = FEATURE_PIN_GATES,
            title = "钉住原生校验（增强）",
            summary = "用 Dobby 额外把原生层的包名与签名两道校验钉成通过。" +
                "默认不需要开；只在关掉噪音后仍然听到噪音时试它",
            defaultEnabled = false,
            // 开关状态在 tryOpenGate 落补丁那一刻直接读设置，这里不用做事 ——
            // 否则就依赖了「这一项排在消除噪音之后安装」这个顺序。
            install = {},
        ),
        HookFeature(
            id = "log_state",
            title = "记录授权状态",
            summary = "排查用：把原生层三个校验位的地址与前后取值写进日志，" +
                "用来确认解锁是否真的落地",
            defaultEnabled = false,
            install = { installStateLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        val proxy = scope.classOrNull(AirMusic.AUDIO_PROXY)
        if (proxy == null) {
            scope.log.w("找不到 ${AirMusic.AUDIO_PROXY}，不是预期的 AirMusic 结构")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("AirMusic ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 关掉试用噪音。
     *
     * 落点全在 `libaudioproxy.so` 的 `.data` 上，所以这里唯一要解决的是**时机**：
     * hooker 跑在 Application 创建之前，那时 so 还没被 `System.loadLibrary` 装进来，
     * [NativeGate.resolve] 必然拿不到基址。
     *
     * 所以不在这里补，而是挂在几个「一定发生在类初始化之后」的静态方法上，
     * 第一次进入时再补。`AudioProxy` 的类初始化块就是加载那两个 so 的地方，
     * 它的任何一个静态方法被调用过，就说明 so 已经在了。
     *
     * 选的这几个方法都是低频的：
     *
     * * `a()` —— 返回连接状态标记文件，`AirMusicApplication.onCreate()` 里就会调，是最早的时机；
     * * `transferRaw/Mp3/Alac(boolean)` —— 切换编码格式，投送开始前必然经过，作为兜底。
     *
     * **刻意不挂 `transfer(byte[], int, int)`**：那是真正的数据通路，44.1kHz 立体声下
     * 每 8 毫秒一次，挂上去等于给每一帧音频加一层 Xposed 转发。上面那几个够用了 ——
     * 噪音要到第 10 分钟才出现，补丁早就落好了。
     */
    private fun HookScope.installNoNoise() {
        val proxy = classOrNull(AirMusic.AUDIO_PROXY) ?: error("${AirMusic.AUDIO_PROXY} not found")
        val triggers = proxy.gateTriggers()
        if (triggers.isEmpty()) error("${AirMusic.AUDIO_PROXY} 上找不到可用的触发点")

        val scope = this
        // 热重载场景下 so 可能已经在了，先试一次，成功就不必等触发。
        tryOpenGate(scope)

        triggers.forEach { method ->
            method.createBeforeHook("airmusic.gate.${method.name}") {
                tryOpenGate(scope)
            }
        }

        log.i("已挂上 ${triggers.joinToString { "${it.name}()" }}，等 $LIBRARY_LABEL 加载后关闭噪音")
    }

    /**
     * `AudioProxy` 上适合当触发点的静态方法。
     *
     * 按形状取而不是按名字：`a()` 这种单字母名字是 R8 给的，下一版可能就变了，
     * 而「无参返回 File」和「收一个 boolean 返回 int」这两个形状是由调用方的用法决定的。
     */
    private fun Class<*>.gateTriggers(): List<Method> {
        val int = Int::class.javaPrimitiveType
        val bool = Boolean::class.javaPrimitiveType
        return declaredMethods.filter { method ->
            if (!Modifier.isStatic(method.modifiers)) return@filter false
            val params = method.parameterTypes
            when {
                // a(): 连接状态标记文件
                params.isEmpty() && method.returnType == java.io.File::class.java -> true
                // transferRaw/Mp3/Alac(boolean): 切换编码格式
                params.size == 1 && params[0] == bool && method.returnType == int -> true
                else -> false
            }
        }
    }

    /** 补丁只需要成功一次；没成功就下个触发点再来。 */
    private fun tryOpenGate(scope: HookScope) {
        if (gateOpened.get()) return
        val layout = NativeGate.resolve(scope.log) ?: return
        val pin = scope.isEnabled(FEATURE_PIN_GATES, default = false)
        if (NativeGate.openGate(scope.log, layout, pin)) {
            gateOpened.set(true)
        }
    }

    /**
     * 让授权判定恒为「已购买」。
     *
     * 钉的是 `Policy.allowAccess()` —— LVL 三条路径（Play 正常应答 / 绑不上服务 /
     * 压根没发起绑定）的共同收敛点，见 [AirMusicDex.allowAccess]。钉住它一处，
     * 「Google 服务被冻结也能正常」就自然成立：`y6.onReceive` 里那句
     * `if (policy.allowAccess())` 会直接走已授权分支，`bindService` 根本不会发出去。
     *
     * 用 after hook 而不是替换：原方法照跑，副作用只有一次原生验签。验签真通过的话，
     * 原生那个授权标志会被应用**自己**置位 —— 那是最理想的情况，
     * [installNoNoise] 那边就成了空操作。通不过也无所谓，我们把返回值改掉。
     */
    private fun HookScope.installLifetime() {
        val allowAccess = AirMusicDex.allowAccess(this)
            ?: error("找不到授权裁决方法（LVL Policy.allowAccess），应用结构可能已变")

        allowAccess.createAfterHook("airmusic.lifetime.allow_access") { param ->
            if (param.result == true) return@createAfterHook
            param.result = true
        }

        log.i("授权裁决 ${allowAccess.declaringClass.name}.${allowAccess.name}() 已钉为已授权")
    }

    /**
     * 隐藏试用提示。
     *
     * 应用靠 `R.bool.is_trial` 这个资源判断自己是不是试用版，读它的地方被 R8 缩成了
     * `CommonUtils.e()`。按成 false 之后那个「Trial Version」对话框就不再构造。
     *
     * 这个判定还管着另外四处，全是评分邀请相关（`AppRate` 的初始化与计数），
     * 所以关掉之后可能会出现评分邀请 —— 那本来就是正式版的行为，不影响功能。
     */
    private fun HookScope.installHideTrial() {
        val isTrial = AirMusicDex.isTrialCheck(this)
            ?: error("找不到试用版判定方法")

        isTrial.createAfterHook("airmusic.hide_trial") { param ->
            if (param.result == false) return@createAfterHook
            param.result = false
        }

        log.i("试用版判定 ${isTrial.declaringClass.name}.${isTrial.name}() 已按为否")
    }

    /**
     * 只读诊断。
     *
     * 把原生三个校验位的解析结果和取值打出来。这几个量是「解锁到底有没有落地」的
     * 唯一事实来源 —— 界面上看不出区别，噪音又要等十分钟才出现。
     */
    private fun HookScope.installStateLog() {
        val proxy = classOrNull(AirMusic.AUDIO_PROXY) ?: return
        val log = this.log
        proxy.gateTriggers().firstOrNull()?.createAfterHook("airmusic.log.state") {
            val layout = NativeGate.resolve(log)
            if (layout == null) {
                log.i("$LIBRARY_LABEL 尚未加载，无法读取校验位")
                return@createAfterHook
            }
            val state = NativeGate.read(layout)
            if (state == null) {
                log.i("校验位读不出来")
                return@createAfterHook
            }
            log.i(
                "原生校验位：授权标志=${state.authFlag} 包名闸=${state.pkgCache} " +
                    "签名闸=${state.sigCache} → ${if (state.isOpen) "已放行（无噪音）" else "未放行（10 分钟后会有噪音）"}",
            )
        }

        log.i("授权状态日志已开启")
    }

    private companion object {
        const val FEATURE_PIN_GATES = "pin_native_gates"
        const val LIBRARY_LABEL = NativeGate.LIBRARY
    }
}
