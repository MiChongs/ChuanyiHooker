package com.chuanyi.hooker.hookers.chuckle

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chuckle（`app.jjyy.chuckle`）—— Compose Multiplatform 写的第三方 X 客户端，年付订阅制。
 *
 * 权益完全是本地判定，链路短得出奇：
 *
 * ```text
 * 权益记录(JSON, Base64, settings_iap.xml)
 *   ↓
 * 判定  = code 非空 && !expired
 *   ↓
 * 三态  = if (判定 && info.freeTrial) 试用 else if (判定) 已订阅 else 免费
 *   ↓
 * 免费才出广告（横幅 / 插屏 / 激励 / 开屏 / 信息流 / 周期性强制激励）
 * ```
 *
 * Google Play 只负责**发起**订阅：付款成功后拿 `purchaseToken` 去自建服务器换一份权益 JSON，
 * 之后所有判断都只读本地那份，**再也不问 Google**。所以
 * [FEATURE_LIFETIME] 天然满足「Google 服务停用后照常可用」——
 * 它压根不在判定路径上。真正会把权益改回去的是自建服务器的周期性复验，那条由
 * [FEATURE_KEEP] 掐掉。
 *
 * 落点以**数据**为主、函数为辅：权益对象自洽之后，判定、三态、去广告、订阅页展示会一起
 * 成立，一个方法都不必接管。[FEATURE_VERDICT] 只是 dex 结构大改、数据层定位失败时的兜底。
 */
class ChuckleHooker : AppHooker {

    override val id = "chuckle"
    override val displayName = "Chuckle"
    override val description = "解锁永久高级版"
    override val targetPackages = setOf("app.jjyy.chuckle")

    @Volatile
    private var refs: ChuckleDex.Refs? = null

    @Volatile
    private var layout: Entitlement.Layout? = null

    /** 写盘只做一次；之后靠读取端改写扛服务端覆盖。 */
    private val installed = AtomicBoolean(false)

    /**
     * 判定兜底可能被两条路触发（数据落点定位失败时的自动降级、用户主动打开那一项），
     * 用它保证那两个方法上只装一次 —— 同一个方法叠两层常量 hook 会直接抛。
     */
    private val verdictHooked = AtomicBoolean(false)

    private val stateLogged = AtomicBoolean(false)

    @Volatile
    private var forceVerdict = false

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = FEATURE_LIFETIME,
            title = "解锁永久高级版",
            summary = "把权益记录改写成永不过期、且不是试用 —— 高级版判定、三态、去广告、" +
                "订阅页上的「已激活 / 有效期」全部随之成立。" +
                "还没激活过（全新安装或数据被清）时会就地合成一份永久授权。" +
                "判定完全在本地，冻结 Google 服务照常可用",
            install = { installLifetime() },
        ),
        HookFeature(
            id = FEATURE_KEEP,
            title = "阻止服务端撤销权益",
            summary = "应用会定期拿激活码去自建服务器复验，服务端说什么就覆盖本地什么，" +
                "试用到期后正是它把权益改回失效。开启后让复验的节流时间戳永远停在「刚查过」，" +
                "这次联网请求根本不会发出",
            install = { installKeep() },
        ),
        HookFeature(
            id = FEATURE_VERDICT,
            title = "会员判定兜底",
            summary = "直接把高级版判定与权益三态按成「已订阅」。" +
                "正常情况下用不到 —— 权益记录自洽之后判定自然成立；" +
                "留着是为了应用大改结构、数据落点定位失败时仍然可用",
            install = { installVerdict() },
        ),
        HookFeature(
            id = FEATURE_NO_TELEMETRY,
            title = "屏蔽风控埋点上报",
            summary = "应用会把环境检测结果上报成 tamper_signal_fired / strict_mode_risk_probe 两个事件。" +
                "它们只是统计，不影响功能，但会在服务端留下画像。开启后不再发出",
            install = { installNoTelemetry() },
        ),
        HookFeature(
            id = FEATURE_NATIVE_PROBE,
            title = "放行原生完整性探针",
            summary = "应用自带一个原生探针（SecureValueNative），判定环境被改动时会切到严格模式解密内置密钥。" +
                "本机隐藏方案到位时它本来就判得过，所以默认关闭；" +
                "只有出现「启动报错 / 功能异常」且日志指向它时才需要打开",
            defaultEnabled = false,
            install = { installNativeProbe() },
        ),
        HookFeature(
            id = FEATURE_DUMP,
            title = "打印权益详情",
            summary = "在日志里输出当前权益记录的激活码、到期时间、是否试用、已绑定设备，排查用",
            defaultEnabled = false,
            requiresRestart = false,
            install = { installDump() },
        ),
    )

    /**
     * 永远认领这个进程，哪怕一个落点都没定位到。
     *
     * 定位不到的功能会各自 warn 并跳过，框架也允许单个功能失败而不牵连其余 ——
     * 让整个 hooker 因为一处扫描失败就退出，只会把「少一个功能」放大成「什么都没有」。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        // ChuckleDex.resolve 内部已经包了异常屏障 —— 这里跑在目标的
        // handleBindApplication 调用栈上，漏一个 Throwable 就是一次启动崩溃。
        val resolved = ChuckleDex.resolve(scope)
        if (resolved.isEmpty) {
            scope.log.w("没能在 dex 里定位到权益体系 —— 版本 ${scope.versionCode} 可能改了结构")
        } else if (!resolved.hasDataPath) {
            scope.log.w("只定位到判定函数，没定位到权益记录；请打开「会员判定兜底」")
        }
        refs = resolved
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Chuckle ${scope.versionCode} 进程 ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 核心：接管权益记录的读取。
     *
     * 一处 hook 干两件事 —— 第一次读时把磁盘上那份推成永久（没有就合成），
     * 之后每次读都就地把返回的对象改写一遍。
     *
     * 两件都要做：写盘解决「全新安装时 `code` 为空、判定第一关就过不去」，
     * 读取改写解决「服务端复验把 `expired` 刷回 true」。
     */
    private fun HookScope.installLifetime() {
        val subscription = requireRefs().subscription
        if (subscription == null) {
            log.w("没定位到权益记录的读取方法，解锁改走「会员判定兜底」")
            forceVerdict = true
            installVerdictHooks()
            return
        }

        subscription.createAfterHook("chuckle.subscription") { param ->
            val current = param.result
            ensureInstalled(current)

            val known = layout
            if (current != null && known != null) {
                Entitlement.rewrite(current, known)
                if (stateLogged.compareAndSet(false, true)) {
                    log.i("权益已就位：${Entitlement.describe(current, known)}")
                }
            } else if (current == null) {
                // 写盘刚刚发生过，缓存也清了，再读一次就有了。
                param.result = runCatching { subscription.invoke(null) }.getOrNull()
                    ?.also { fresh -> known?.let { Entitlement.rewrite(fresh, it) } }
            }
        }
        log.i("权益记录读取已接管：${subscription.declaringClass.name}.${subscription.name}")

        // 复验和界面上有几处绕过上面那个方法、直接拿存储里的整包对象。
        // 那一层同样要改，否则会读到未经改写的权益。
        installWalletHook()
    }

    /**
     * 存储层的整包读取（`{vfc, ac}`），复验路径走的是这一条。
     *
     * 它是通用的持久化读取方法，什么类型都可能返回，所以按**字段类型**认领：
     * 只有权益整包里会有一个类型正好是权益对象的字段。
     */
    private fun HookScope.installWalletHook() {
        val read = requireRefs().walletRead ?: return
        val subClass = requireRefs().subscriptionClass ?: return

        var walletField: Field? = null
        read.createAfterHook("chuckle.wallet") { param ->
            val wallet = param.result ?: return@createAfterHook
            val field = walletField ?: wallet.javaClass.resolve()
                .optional(silent = true)
                .firstFieldOrNull {
                    modifiers { Modifiers.STATIC !in it }
                    type = subClass
                }?.self?.apply { isAccessible = true }
                ?.also { walletField = it }
                ?: return@createAfterHook

            val known = layout ?: return@createAfterHook
            runCatching { field.get(wallet) }.getOrNull()?.let { Entitlement.rewrite(it, known) }
        }
        log.d("权益整包读取已接管：${read.declaringClass.name}.${read.name}")
    }

    /** 第一次拿到权益对象时把磁盘那份推成永久，只做一次。 */
    private fun HookScope.ensureInstalled(sample: Any?) {
        if (!installed.compareAndSet(false, true)) return
        val context = appContextOrNull()
        if (context == null) {
            // 还没有 Application，下次读的时候再来。
            installed.set(false)
            return
        }
        layout = Entitlement.install(this, requireRefs(), context, sample)
        if (layout == null) {
            log.w("权益字段映射没建立起来，已改用「会员判定兜底」保底")
            forceVerdict = true
            installVerdictHooks()
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 掐掉周期性联网复验。
     *
     * 复验用一个节流时间戳决定该不该发请求：`现在 - 上次 > 间隔` 才发。
     * 把「上次」一直答成「现在」，差值恒为 0，请求永远不会发出 ——
     * 比拦 HTTP 干净，也不需要知道任何域名。
     *
     * 挂在存储基类的 `getLong` 上，按键名认领，其余的键原样放行。
     */
    private fun HookScope.installKeep() {
        // 那个 getLong 定义在权益类的**存储基类**上，所以从权益类往上找。
        val getLong = requireRefs().let { it.isPremium ?: it.subscription }
            ?.declaringClass?.resolve()
            ?.optional(silent = true)
            ?.firstMethodOrNull {
                superclass()
                returnType = Long::class.javaPrimitiveType
                parameters(String::class.java, Long::class.javaPrimitiveType!!)
                isSyntheticNot = true
            }?.self

        if (getLong == null) {
            log.w("没定位到存储的 getLong，无法阻止服务端复验")
            return
        }
        getLong.createAfterHook("chuckle.service_check") { param ->
            if (param.args.getOrNull(0) == ChuckleDex.KEY_SERVICE_CHECK) {
                param.result = System.currentTimeMillis()
            }
        }
        log.i("服务端复验已掐断：${getLong.declaringClass.name}.${getLong.name}")
    }

    // -----------------------------------------------------------------------

    private fun HookScope.installVerdict() {
        forceVerdict = true
        installVerdictHooks()
    }

    /** 判定与三态各恒定一次。两个功能都可能触发它，所以做成幂等。 */
    private fun HookScope.installVerdictHooks() {
        if (!verdictHooked.compareAndSet(false, true)) return

        val known = requireRefs()

        known.isPremium?.let { verdict ->
            verdict.createAfterHook("chuckle.verdict") { param ->
                if (forceVerdict) param.result = true
            }
            log.i("会员判定已接管：${verdict.declaringClass.name}.${verdict.name}")
        } ?: log.d("没定位到会员判定方法")

        val paid = known.tierPaid
        known.tier?.let { tier ->
            if (paid == null) {
                log.w("没认出「已订阅」那个枚举常量，三态保持原样")
                return@let
            }
            tier.createAfterHook("chuckle.tier") { param ->
                if (forceVerdict) param.result = paid
            }
            log.i("权益三态已接管：${tier.declaringClass.name}.${tier.name} -> $paid")
        } ?: log.d("没定位到权益三态方法")
    }

    // -----------------------------------------------------------------------

    /**
     * 两个埋点都是纯统计（`void`），拦掉不影响任何逻辑。
     *
     * 只拦返回 void 的那些：风控探针本身是 suspend 函数，它的返回值参与协程状态机，
     * 拦了会把调用它的那条链挂住。上报不发，检测照跑，代价为零。
     */
    private fun HookScope.installNoTelemetry() {
        val reporters = requireRefs().analytics.filter { it.returnType == Void.TYPE }
        if (reporters.isEmpty()) {
            log.d("没定位到风控埋点的上报方法（可能这版没有）")
            return
        }
        reporters.forEach { reporter ->
            reporter.createInterceptHook("chuckle.telemetry.${reporter.name}") { _ -> null }
            log.d("埋点已屏蔽：${reporter.declaringClass.name}.${reporter.name}")
        }
        log.i("风控埋点已屏蔽 ${reporters.size} 处")
    }

    /**
     * 原生探针按成常量。
     *
     * `diagnoseProbe()I` 和 `wasPathForged()Z` 都是 native 方法 —— Xposed 接管的是
     * Java 侧的桥，够不到方法体，只能在符号层做。两条路都试：
     * 动态注册（`RegisterNatives`）的走注册表替换，静态导出的走 Dobby 直接 inline。
     */
    private fun HookScope.installNativeProbe() {
        if (!NativeHook.isAvailable) {
            log.w("原生层没加载起来（${NativeHook.lastError}），这一项跳过")
            return
        }

        var done = 0
        PROBE_METHODS.forEach { (method, value) ->
            // 动态注册：在 JNINativeMethod 数组过路时换掉指针，目标的 .so 一个字节都不写。
            if (NativeHook.returnConstantOnJniRegister(PROBE_CLASS, method, value)) done++

            // 静态导出兜底：JNI 的名字修饰是固定规则，能直接算出来。
            val symbol = "Java_" + PROBE_CLASS.replace('/', '_') + "_" + method
            val address = NativeHook.findSymbol(null, symbol)
            if (address != 0L && NativeHook.returnConstant(address, value)) {
                done++
                log.d("$method 已按常量 $value（符号 $symbol @ 0x${address.toString(16)}）")
            }
        }
        if (done == 0) log.w("原生探针一个都没接管上") else log.i("原生完整性探针已放行（$done 处）")
    }

    private fun HookScope.installDump() {
        val subscription = requireRefs().subscription
        if (subscription == null) {
            log.w("没定位到权益记录，打印不了")
            return
        }
        subscription.createAfterHook("chuckle.dump") { param ->
            val current = param.result
            if (current == null) {
                log.i("权益详情：当前没有任何记录")
                return@createAfterHook
            }
            val known = layout
            log.i(
                if (known != null) "权益详情：${Entitlement.describe(current, known)}"
                else "权益详情：$current",
            )
        }
    }

    // -----------------------------------------------------------------------

    private fun requireRefs(): ChuckleDex.Refs =
        refs ?: error("refs 尚未解析；isCompatible 一定先于任何功能安装运行")

    companion object {
        const val FEATURE_LIFETIME = "lifetime"
        const val FEATURE_KEEP = "keep_entitlement"
        const val FEATURE_VERDICT = "force_verdict"
        const val FEATURE_NO_TELEMETRY = "no_telemetry"
        const val FEATURE_NATIVE_PROBE = "native_probe"
        const val FEATURE_DUMP = "dump_entitlement"

        private const val PROBE_CLASS = "lib/android/security/internal/SecureValueNative"

        /** 探针方法 → 要它返回的值。0 = 一切正常 / 未被改动。 */
        private val PROBE_METHODS = listOf(
            "diagnoseProbe" to 0L,
            "wasPathForged" to 0L,
        )
    }
}
