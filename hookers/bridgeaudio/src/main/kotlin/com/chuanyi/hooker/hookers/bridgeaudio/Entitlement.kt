package com.chuanyi.hooker.hookers.bridgeaudio

import android.content.Context
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权益与试用。
 *
 * ## 这个应用的判定长什么样
 *
 * 整个应用**只有一个**权益真相源：`TrialAccess.isUnlocked(Context)`，读的是
 * `airplay_prefs` 里的 `billing_full_unlock`。其余每一处限制都从它级联下来：
 *
 * ```
 * isUnlocked(ctx)                                 ← 唯一判定
 *   ├─ remainingTrialMs      已解锁 → Long.MAX_VALUE
 *   │    └─ canStartFreeTrial  剩余 > 0 → true
 *   │         ├─ MainActivity.checkPermissionsAndStart   不再弹付费墙
 *   │         ├─ TileDeviceActivity / WidgetStartActivity 磁贴与小组件照常投放
 *   │         └─ AudioCaptureService.startCapture        不再拒绝开始采集
 *   ├─ startTrialLimitIfNeeded  已解锁 → 直接 return，倒计时任务根本不创建
 *   ├─ expireFreeTrial          已解锁 → 直接 return，不会停流
 *   ├─ markTrialProgress        已解锁 → 直接 return，剩余时长不再被扣减
 *   └─ 界面文案               「unlimited streaming active」/「streaming」
 * ```
 *
 * 所以 3 分钟试用不是一条独立的限制，而是「未解锁」的表现之一 —— 按住 [isUnlocked]
 * 一处，倒计时任务连创建都不会创建，不存在「计时器还在跑只是不生效」的残留。
 *
 * ## 只有一次性买断，没有订阅
 *
 * 商品 `bridge_audio_full_unlock` 的类型是 `inapp` 而不是 `subs`，查询与购买两条路
 * 用的都是这个类型；应用里也没有任何过期时间、续订状态或到期回收的概念。
 * 换句话说这个应用的「完整版」天然就是永久的 —— 不需要另外伪造一个终身标记。
 */
internal object Entitlement {

    /** 解锁标记只写一次。 */
    private val recorded = AtomicBoolean(false)

    /**
     * 把权益判定按成已购买。
     *
     * 三处都按，但它们不是三重保险，而是一条链上的三节 —— 单按 [isUnlocked] 就足以
     * 级联到全部限制。之所以另外两处也按住：`remainingTrialMs` 与 `canStartFreeTrial`
     * 各自有直接调用方（界面文案、磁贴、小组件、采集服务），按住之后每一层自己的返回值
     * 就是自洽的，不必绕一圈才对。
     *
     * 用 after 钩子改返回值而不是常量替换：[installPersist] 与 [installLog] 挂在同一个
     * 方法上，替换掉方法体会让它们失去执行机会。
     */
    fun HookScope.installLifetime(refs: BridgeAudioDex.Refs) {
        val unlocked = refs.isUnlocked ?: error("没定位到权益判定，无法解锁")

        unlocked.createAfterHook("bridgeaudio.lifetime.unlocked") { param ->
            if (param.result != true) param.result = true
        }
        log.d("已按住权益判定：${unlocked.declaringClass.name}.${unlocked.name}")

        refs.canStartFreeTrial?.createAfterHook("bridgeaudio.lifetime.can_start") { param ->
            if (param.result != true) param.result = true
        } ?: log.d("canStartFreeTrial 未定位，由权益判定级联")

        // 剩余时长同时是界面文案的数据源。已解锁时应用自己给的就是 Long.MAX_VALUE，
        // 这里保持一致，避免出现「已解锁但显示还剩几分钟」。
        refs.remainingTrialMs?.createAfterHook("bridgeaudio.lifetime.remaining") { param ->
            if (param.result != Long.MAX_VALUE) param.result = Long.MAX_VALUE
        } ?: log.d("remainingTrialMs 未定位，由权益判定级联")

        log.i("完整版已解锁，试用时长限制随之失效")
    }

    /**
     * 把解锁标记写进应用自己的存档。
     *
     * 用的是应用自己的写入方法（`setUnlocked(Context, true)`），落到
     * `airplay_prefs` 的 `billing_full_unlock` —— 和真实购买成功后走的是同一句。
     * 写完之后即使停用本模块，应用自己读这个键也是已解锁。
     *
     * 时机挂在权益第一次被问到的时候：那一刻 Context 一定已经就位，不用猜路径，
     * 也不用等 Application 建好。
     *
     * 默认关闭 —— 它改动的是目标的数据，而其余功能全部只作用于运行时。
     */
    fun HookScope.installPersist(refs: BridgeAudioDex.Refs) {
        val unlocked = refs.isUnlocked ?: error("没定位到权益判定，无处挂写入时机")
        val writer = refs.setUnlocked ?: error("没定位到权益写入方法")

        unlocked.createAfterHook("bridgeaudio.persist") { param ->
            if (!recorded.compareAndSet(false, true)) return@createAfterHook
            val context = param.args.getOrNull(0) as? Context
            val owner = param.thisObjectOrNull
            if (context == null || owner == null) {
                log.w("写入解锁标记时拿不到 Context，下次判定再试")
                recorded.set(false)
                return@createAfterHook
            }
            runCatching { writer.invoke(owner, context, true) }
                .onSuccess { log.i("解锁标记已写入 ${BridgeAudio.PREFS}/${BridgeAudio.KEY_FULL_UNLOCK}") }
                .onFailure {
                    log.w("写入解锁标记失败：${it.message}")
                    recorded.set(false)
                }
        }
        log.i("解锁标记写入已就位")
    }

    /**
     * 解锁之后不再连接 Google Play 结算。
     *
     * 冻结 Google 服务时，`BillingClient` 连不上会走「失败 → 发布带错误文案的状态 →
     * 设置页把那段文案弹成 Toast」这条路，每次打开设置页都要弹一次
     * （`Billing unavailable` / `Could not load unlock price` / `Could not restore
     * purchase`）。权益已经不依赖它了，这条链留着只剩噪音与启动时的等待。
     *
     * 两处一起关，缺一不可：
     *
     * * `connect()` 与 `queryPurchases()` —— 拦住发起端，`.so` 与 binder 都不会被碰。
     *   界面拿到的仍然是构造器里那份初始状态，而那份状态的 `unlocked` 字段正是
     *   `TrialAccess.isUnlocked()` 的返回值，所以已解锁会立刻显示出来。
     * * `BillingState` 构造器的末位参数 `message` —— 结算之外也有地方发布状态，
     *   从数据源头抹掉提示文案，比逐个拦 Toast 干净。
     *
     * 只在「完整版解锁」也开着时才装：那一项关掉时用户是想正常走购买流程的，
     * 断掉结算会让购买按钮永远处于加载中。
     */
    fun HookScope.installSkipBilling(refs: BridgeAudioDex.Refs) {
        if (!isEnabled("lifetime")) {
            log.w("「完整版解锁」未启用，保留 Google 结算链路以免购买不可用")
            return
        }

        val actions = refs.billingActions
        if (actions.isEmpty()) {
            log.w("没定位到结算的连接与查询方法，只屏蔽提示文案")
        }
        actions.forEach { action ->
            action.createInterceptHook("bridgeaudio.skip_billing.${action.name}") { null }
            log.d("已跳过 ${action.declaringClass.name}.${action.name}()")
        }

        val ctor = refs.billingStateCtor
        if (ctor == null) {
            log.w("没定位到结算状态构造器，提示文案不受影响")
        } else {
            ctor.createBeforeHook("bridgeaudio.skip_billing.message") { param ->
                param.args[4] = null
            }
            log.d("结算提示文案已从 ${ctor.declaringClass.name} 抹掉")
        }
        log.i("Google 结算链路已跳过，无 Google 服务时不再重试与提示")
    }

    /**
     * 排查用。记的是**最终**判定值 —— [installLifetime] 的钩子挂在同一个方法上，
     * 两个 after 钩子的先后顺序不保证，所以这里读到的可能已经是被按过的值。
     * 要看原始值就把「完整版解锁」关掉再看。
     */
    fun HookScope.installLog(refs: BridgeAudioDex.Refs) {
        refs.isUnlocked?.createAfterHook("bridgeaudio.log.unlocked") { param ->
            log.i("权益判定 isUnlocked() = ${param.result}")
        }
        refs.remainingTrialMs?.createAfterHook("bridgeaudio.log.remaining") { param ->
            log.i("试用剩余 = ${param.result} ms")
        }
        refs.billingStateCtor?.createAfterHook("bridgeaudio.log.billing") { param ->
            val args = param.args
            log.i(
                "结算状态：connected=${args.getOrNull(0)}，unlocked=${args.getOrNull(1)}，" +
                    "price=${args.getOrNull(2)}，productReady=${args.getOrNull(3)}，" +
                    "message=${args.getOrNull(4)}",
            )
        }
        log.i("权益与结算日志已开启")
    }
}
