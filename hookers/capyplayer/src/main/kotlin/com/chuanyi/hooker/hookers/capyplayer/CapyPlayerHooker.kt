package com.chuanyi.hooker.hookers.capyplayer

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook

/**
 * CapyPlayer（`com.feifeiduck.capyplayer`）—— Flutter 写的影视播放器，mpv/ffmpeg
 * 内核，Pro 走 Google Play 订阅 + 自家服务端复核。
 *
 * ## 解锁点在哪
 *
 * 判定**一条都不在 Java 层**：`libapp.so` 里 16 MB 的 Dart AOT 代码，`SubscriptionNotifier`
 * / `SubscriptionSyncService` / `PaywallGuard` 全在里面。好消息是这个包没开
 * `--obfuscate`，函数名和 `package:capyplayer/...` 的源文件路径都是明文，判定链是
 * 读出来的。
 *
 * 全部 16 个 Pro 功能调用点收敛到一个 `PaywallGuard|ensureEntitled` —— 它返回
 * `false` 的分支做的就是把人踢去订阅页。补它一处，Pro 功能全放行。细节见
 * [Entitlement]，补丁原语见 [DartPatch]。
 *
 * ## 无 Play 也成立
 *
 * 解锁走的是 Dart 代码补丁，**不经过 Play、不经过网络、不需要账号**，所以装没装
 * Google Play 一个样。[no_play] 那个开关修的是另一件事：无 Play 时 Play Billing 的
 * 特性探测会抛 `UNAVAILABLE`，让订阅页部分控件走进「本设备不支持」分支 —— 那是显示
 * 问题，不影响功能。
 *
 * ## 为什么不伪造购买
 *
 * 试过的路和放弃的理由，留给下次改这个模块的人：
 *
 * * **注入 Play 的已购回包**（yamby 那套）：这个应用买完会 `POST /subscriptions/verify/google`
 *   让自家服务端复核，假回执过不了，日志里就是
 *   `Server verification failed, clearing local IAP subscription`。
 * * **直接写本地那份订阅缓存**（`flutter.subscription_data`）：Dart 侧是
 *   json_serializable，字段少一个就整份抛异常，`_loadSubscription` 直接失败 ——
 *   比不改还糟。schema 只能靠猜，不划算。
 *
 * 代码补丁绕开了这两条：它改的是「判定」本身，不去伪造喂给判定的输入，所以既不需要
 * 服务端点头，也不依赖任何数据格式。
 */
class CapyPlayerHooker : AppHooker {

    override val id = "capyplayer"
    override val displayName = "CapyPlayer"
    override val description = "影视播放器，解锁终身 Pro"
    override val targetPackages = setOf("com.feifeiduck.capyplayer")

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime_pro",
            title = "解锁终身 Pro",
            summary = "把 Dart 层的权益判定钉成「已订阅」，全部 Pro 功能立即可用。" +
                "不联网、不需要账号、不写入任何数据，关掉即恢复原样",
            install = { installLifetimePro() },
        ),
        HookFeature(
            id = "no_play",
            title = "无 Google Play 兼容",
            summary = "设备没装 Play 时，让计费组件的能力探测直接通过，" +
                "订阅页不再显示「本设备不支持」。解锁本身不依赖这一项",
            install = { installNoPlayCompat() },
        ),
        HookFeature(
            id = "inject_purchase",
            title = "写入终身买断记录",
            summary = "往 Play 的查询回调里补一条终身买断，让应用按自己的流程把订阅存到本地，" +
                "订阅页会显示会员状态。需要应用能连上 Play；纯解锁不依赖这一项",
            install = { installPurchaseInject() },
        ),
        HookFeature(
            id = "trace_prefs",
            title = "记录本地存储读写（排查用）",
            summary = "把应用读写 SharedPreferences 的键值打进日志。" +
                "只在需要摸清订阅缓存格式时打开，平时留着会刷屏",
            defaultEnabled = false,
            install = { installPrefsTrace() },
        ),
    )

    /**
     * 版本闸门用的是**补丁落点是否还在**，不是 versionCode 白名单。
     *
     * 钉版本号的做法有两个毛病：目标发小版本就得跟着出包，而落点其实没动；反过来
     * 落点真变了、版本号却对得上时，它又什么都拦不住。直接问「三个锚点还找得到吗」
     * 才是真正想知道的事。
     *
     * 这里只**查**不写 —— 真正的补丁在功能开关打开时才落。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        if (!scope.isMainProcess) return false
        if (!NativeHook.isAvailable) {
            scope.log.w("原生层没加载起来（${NativeHook.lastError}），解锁走不了")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("CapyPlayer ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 补丁必须等 `libapp.so` 映射进来才能打，而 Xposed 给我们的时机比那早。
     *
     * `System.loadLibrary("app")` 由 Flutter 引擎在 `FlutterLoader` 初始化时发起，
     * 也就是第一个 Activity 起来前后 —— 比 `PACKAGE_READY` 晚几百毫秒。所以这里不
     * 能直接打，得等它出现。
     *
     * 用 [NativeHook.moduleBase] 轮询而不是 hook `dlopen`：便宜、无副作用，且
     * Flutter 加载引擎的路径在各版本间比 linker 的内部实现稳定得多。
     *
     * **不用担心赶不上**：Dart 快照的代码段是被 `mmap` 进来的**只读镜像**，不像
     * Dart 字符串那样在 isolate 启动时反序列化进堆。补丁落在镜像上，什么时候打都
     * 一样有效 —— 只要赶在用户点开某个 Pro 功能之前，而那至少是几秒之后的事。
     */
    private fun HookScope.installLifetimePro() {
        val log = log
        Thread({
            val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (NativeHook.moduleBase(DartPatch.IMAGE) != 0L) {
                    val ok = Entitlement.installAll(log)
                    if (ok == Entitlement.SITES.size) {
                        log.i("终身 Pro 已解锁（$ok/${Entitlement.SITES.size} 处落点）")
                    } else {
                        log.w(
                            "只补上了 $ok/${Entitlement.SITES.size} 处落点 —— " +
                                "目标版本可能变了，上面的日志有具体是哪一处",
                        )
                    }
                    return@Thread
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            log.e("等了 ${LOAD_TIMEOUT_MS / 1000} 秒也没等到 ${DartPatch.IMAGE}，放弃")
        }, "capyplayer-unlock").apply { isDaemon = true }.start()
    }

    /**
     * `isFeatureSupported` 在 `BillingClient` 为空时**抛异常**而不是返回 false，
     * 所以只能整个替换，after hook 根本走不到。
     */
    private fun HookScope.installNoPlayCompat() {
        val method = Billing.featureSupported(this) ?: run {
            log.w("定位不到计费能力探测方法，无 Play 兼容跳过（不影响解锁）")
            return
        }
        method.createReturnConstantHook("capyplayer.no_play", true)
        log.i("计费能力探测已钉为可用：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 让应用自己把终身买断落盘 —— 详见 [PurchaseInject]。
     *
     * 必须在计费客户端建立之前挂上：hook 的是 `BillingClient` 上的查询方法，而应用在
     * 启动后不久就会发起第一次 `queryPurchasesAsync`。`PACKAGE_READY` 比那早，来得及。
     */
    private fun HookScope.installPurchaseInject() {
        if (PurchaseInject.install(this) == 0) {
            log.w("购买注入没找到落点（不影响解锁本身）")
        }
    }

    /**
     * 把 Flutter 那边的持久化读写打出来。
     *
     * `shared_preferences` 在 Android 上就是普通的 `SharedPreferences`，键名统一带
     * `flutter.` 前缀 —— 所以订阅缓存到底叫什么、存的是哪一层的 JSON，不用从快照里
     * 猜，跑一次就看得见。写入侧一起挂上，是因为「应用自己写进去的那份」才是权威
     * 格式样本。
     */
    private fun HookScope.installPrefsTrace() {
        val prefsImpl = classOrNull("android.app.SharedPreferencesImpl") ?: run {
            log.w("找不到 SharedPreferencesImpl，存储追踪跳过")
            return
        }
        prefsImpl.declaredMethods
            .filter { it.name == "getString" || it.name == "getStringSet" || it.name == "getBoolean" }
            .forEach { method ->
                method.createAfterHook("capyplayer.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (!key.startsWith("flutter.")) return@createAfterHook
                    val value = param.result?.toString()
                    log.i("读 $key = ${value?.take(400) ?: "null"}")
                }
            }

        val editorImpl = classOrNull("android.app.SharedPreferencesImpl\$EditorImpl")
        editorImpl?.declaredMethods
            ?.filter { it.name == "putString" || it.name == "putBoolean" }
            ?.forEach { method ->
                method.createAfterHook("capyplayer.trace.${method.name}") { param ->
                    val key = param.args.getOrNull(0) as? String ?: return@createAfterHook
                    if (!key.startsWith("flutter.")) return@createAfterHook
                    log.i("写 $key = ${param.args.getOrNull(1)?.toString()?.take(400)}")
                }
            }
        log.i("存储追踪已挂上")
    }

    private companion object {
        /** libapp.so 出现的等待上限。冷启动到 Flutter 引擎就绪通常在 1 秒内。 */
        const val LOAD_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
