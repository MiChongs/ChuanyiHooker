package com.chuanyi.hooker.hookers.secretshoot

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 秘拍 · Google 版（`com.weixikeji.secretshoot.googleV2`）—— 后台拍照 / 录像 / 录音，
 * 会员制解锁功能。
 *
 * ## 这个目标的形状
 *
 * 没有加固，没有壳，没有原生保护，没有签名自校验。包里 8 个 `.so` 全是第三方
 * （Fresco / AppLovin / Bmob / 微博 SDK），一个和授权有关的都没有。
 *
 * 会员**由自家服务器裁决**，不是本地算的：
 *
 * ```
 * GooglePayService → Play Billing 查已购
 *   → 上报给自家服务器（GooglePurchaseBean）
 *     → 服务器回一份 GooglePayBean（productId / expiryTimeMillis / systemTimeMillis…）
 *       → 落进加密的 SharedPreferences，之后每次判定都读它
 * ```
 *
 * 而那份凭据**没有自证**：全包搜不到对它的验签，没有回执复验，服务器说什么客户端就
 * 信什么。所有会员判定又都收束在一个进程单例上（4.3.8 里叫 `ij.f`），十一个提问全部
 * 由「取缓存凭据」这一个方法的返回值推导出来 —— 所以整套解锁的落点只有那**一个方法**，
 * 换一张合成的永久凭据进去就够了。细节见 [SecretShoot]。
 *
 * ## 冻结 Google 服务照常可用
 *
 * 这是这个目标最值得说的一点，也是本 hooker 特意补了 [offline] 的原因。
 *
 * 应用原本有一套离线宽限：同步成功记一个时间戳，之后 5 天内拿不到服务器也认缓存。
 * 但它有两处会**主动作废**缓存：
 *
 * - Play 连上了却回「没有已购」→ 权益立刻清空（宽限键归零）
 * - 拿不到用户信息且宽限已过 → 直接登出
 *
 * 冻结 Google 服务时走的是「连不上计费服务」那一支（错误码 3），不触发清空 ——
 * 但为了不依赖这个巧合，[offline] 把宽限窗口钉成恒定有效，并把那次清空整个忽略掉。
 * 于是断网、冻结 Google 服务、卸载 Play 商店，权益都不会掉。
 *
 * 广告也不用单独处理：横幅 / 插屏的开关就是中枢的「非会员且非试用」判定，会员成立
 * 之后它恒为 false，应用自己就不去加载了。[noAds] 只是多一道保险。
 */
class SecretShootHooker : AppHooker {

    override val id = "secretshoot"
    override val displayName = "秘拍"
    override val description = "后台拍摄，解锁永久会员"
    override val targetPackages = setOf(SecretShoot.PACKAGE)

    /** 权益中枢。[onHook] 里解析一次，各功能共用。 */
    @Volatile
    private var vault: Class<*>? = null

    /** 付费凭据的类，形状查询的锚点。 */
    @Volatile
    private var bean: Class<*>? = null

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "永久会员",
            summary = "接管会员凭据的读取，一律返回一张永不过期的永久会员凭据。" +
                "功能解锁、会员标识、去广告、界面上的「永久会员」显示全部由这一项覆盖",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "offline",
            title = "断网与冻结 Google 服务时保持会员",
            summary = "让会员缓存的有效窗口恒定成立，并忽略「Google 报告未购买」时的清空动作。" +
                "冻结 Google 服务、卸载 Play 商店或完全断网都不会掉级",
            install = { installOffline() },
        ),
        HookFeature(
            id = "no_ads",
            title = "屏蔽广告",
            summary = "直接掐掉横幅与插屏广告的加载。会员成立后应用本来就不请求广告，" +
                "这一项是关掉「永久会员」时的保险",
            install = { installNoAds() },
        ),
        HookFeature(
            id = "skip_login",
            title = "免登录显示会员",
            summary = "让「我的」页不再提示登录，直接显示会员信息。" +
                "解锁本身不需要账号，这一项只影响那一页的显示，默认关闭",
            defaultEnabled = false,
            install = { installSkipLogin() },
        ),
        HookFeature(
            id = "log_entitlement",
            title = "记录权益判定",
            summary = "排查用：记录应用读到的会员凭据，以及每一次会员 / 试用 / 广告判定的结果",
            defaultEnabled = false,
            install = { installEntitlementLog() },
        ),
        HookFeature(
            id = "native_file_log",
            title = "记录文件读取",
            summary = "排查用：记录应用读取了哪些本地数据文件。" +
                "这个应用的配置是加密存的，用它能看清落点在哪个文件",
            defaultEnabled = false,
            requiresRestart = false,
            install = { installNativeFileLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        if (scope.classOrNull(SecretShoot.GOOGLE_PAY_BEAN) == null) {
            scope.log.w("找不到 ${SecretShoot.GOOGLE_PAY_BEAN}，应用可能已更新")
            return false
        }
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("秘拍 ${scope.versionCode} in ${scope.processName}")
        val payBean = scope.classOrNull(SecretShoot.GOOGLE_PAY_BEAN) ?: return
        bean = payBean
        vault = SecretShootDex.vault(scope, payBean)
            ?: run { scope.log.e("定位不到权益中枢，本次全部功能不可用"); null }
    }

    // -----------------------------------------------------------------------

    /**
     * 解锁的全部落点：取缓存凭据的那一个方法。
     *
     * 换掉它的返回值，会员有效 / 是否永久 / 有权益 / 是否显示广告 / 商品号 / 购买令牌 /
     * 剩余时间 / 通知类型 / 是否自动续订 / 会员名称 —— 十一个判定同时成立，
     * 每个功能闸门都不用单独去按。
     *
     * 凭据只造一次：它不含任何随时间变化的量（有效期判定走
     * `max(服务器时间, 本机时间) < 到期时间`，服务器时间那一项填 0 也不影响结论），
     * 所以缓存下来长期复用是安全的，省掉每次调用一轮反射。
     */
    private fun HookScope.installLifetime() {
        val getter = credentialGetter()
        val product = string(KEY_PRODUCT_ID)?.takeIf { it.isNotBlank() }
            ?: SecretShoot.DEFAULT_PRODUCT
        if (!product.contains(PERMANENT_MARK)) {
            log.w("商品号 $product 不含 '$PERMANENT_MARK'，会员会显示成普通会员而不是永久")
        }

        // 造不出来就**不要装** —— 让 b() 返回 null 会让上层在 isVipValid() 上直接 NPE，
        // 比不打这个补丁还糟。
        val credential = SecretShoot.synthesize(this, product)
            ?: error("合成会员凭据失败，未安装（保持应用原样）")

        getter.createReplaceHook("secretshoot.lifetime") { credential }
        log.i("永久会员已生效：${getter.declaringClass.name}.${getter.name}() -> ${SecretShoot.describe(credential)}")
    }

    /**
     * 让权益扛住「Google 服务不可用」和「完全断网」。
     *
     * 两处一起改，缺一处都留着掉级的路：
     *
     * 1. **宽限窗口恒定成立** —— 那个判定是缓存凭据的总开关，返回 false 时上层拿到的
     *    是一个空凭据，等于当场失效。它原本只给 5 天 / 5 次启动。
     * 2. **忽略清空** —— 写回方法收到 null 时做的是清空（把宽限键归零）。Play 连上了
     *    却回「没有已购」就走这一支。只挡 null 那一次，服务器给了真凭据照常写入。
     *
     * 这一项和 [installLifetime] 是**两件事**：那一项管「现在是会员」，这一项管
     * 「应用自己的那份缓存不会被抹掉」—— 所以只开这一项、不开永久会员时，
     * 真实购买的会员也能在冻结 Google 服务后照常用。
     */
    private fun HookScope.installOffline() {
        var patched = 0

        SecretShootDex.graceGate(this)?.let { gate ->
            gate.createReturnConstantHook("secretshoot.offline.grace", true)
            log.i("离线宽限窗口已钉为恒定有效：${gate.declaringClass.name}.${gate.name}")
            patched++
        } ?: log.w("定位不到离线宽限判定，跳过（断网超过 5 天后可能掉级）")

        val vaultClass = vault
        val beanClass = bean
        if (vaultClass != null && beanClass != null) {
            SecretShoot.credentialWriter(vaultClass, beanClass)?.let { writer ->
                writer.createInterceptHook("secretshoot.offline.keep") { chain ->
                    if (chain.getArg(0) == null) {
                        log.d("忽略一次权益清空（Google 报告未购买）")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                log.i("权益清空已拦下：${writer.declaringClass.name}.${writer.name}(null)")
                patched++
            } ?: log.w("定位不到权益写回方法，跳过")
        }

        if (patched == 0) error("两处都没定位到，离线保持未生效")
    }

    /**
     * 横幅与插屏。
     *
     * AppLovin 是第三方库，它自己的 consumer 规则保住了公开 API，名字是明文的 ——
     * 这里不需要按形状找任何东西。
     *
     * **不碰激励视频**：那条路是应用换取试用时长的入口，掐掉会让加载对话框一直转，
     * 而它对会员用户本来就不会出现。
     */
    private fun HookScope.installNoAds() {
        val silenced = silence(SecretShoot.MAX_AD_VIEW, setOf("loadAd", "startAutoRefresh")) +
            silence(SecretShoot.MAX_INTERSTITIAL, setOf("loadAd", "showAd"))
        if (silenced == 0) error("找不到 AppLovin 的广告入口（应用可能换了广告 SDK）")
        log.i("已掐掉 $silenced 个广告入口")
    }

    private fun HookScope.silence(className: String, names: Set<String>): Int {
        val cls = classOrNull(className) ?: run {
            log.d("$className 不在包里，跳过")
            return 0
        }
        var count = 0
        cls.declaredMethods
            .filter { it.name in names }
            .forEach { method ->
                // 同名重载各挂各的，标签带上参数个数才不会撞。
                runCatching {
                    method.createReplaceHook(
                        "secretshoot.no_ads.${cls.simpleName}.${method.name}.${method.parameterCount}",
                    ) { null }
                }.onSuccess { count++ }
                    .onFailure { log.d("${cls.simpleName}.${method.name} 挂不上：${it.message}") }
            }
        return count
    }

    /** 登录态判定钉成 true，「我的」页就走已登录那一支。 */
    private fun HookScope.installSkipLogin() {
        val vaultClass = vault ?: error("定位不到权益中枢")
        val login = SecretShootDex.loginState(this, vaultClass)
            ?: error("定位不到登录态判定")
        login.createReturnConstantHook("secretshoot.skip_login", true)
        log.i("登录态已钉为已登录：${login.declaringClass.name}.${login.name}")
    }

    /**
     * 排查用。
     *
     * 凭据那条优先级压低，让它在 [installLifetime] 之后跑，看到的是**最终**的值。
     * 判定那一组按方法各自去重：这些方法在界面刷新时会被连续问很多次，第一次记
     * INFO，之后同样的结果降到 DEBUG，日志才读得下去。
     */
    private fun HookScope.installEntitlementLog() {
        val vaultClass = vault ?: error("定位不到权益中枢")

        credentialGetter().createAfterHook("secretshoot.log.credential", priority = -100) { param ->
            log.i("会员凭据 -> ${SecretShoot.describe(param.result)}")
        }

        val seen = ConcurrentHashMap<String, Any?>()
        val verdicts = SecretShoot.verdicts(vaultClass)
        verdicts.forEach { method ->
            method.createAfterHook("secretshoot.log.verdict.${method.name}") { param ->
                val line = "判定 ${vaultClass.simpleName}.${method.name}() -> ${param.result}"
                if (seen.put(method.name, param.result) != param.result) log.i(line) else log.d(line)
            }
        }
        log.i("已挂上 ${verdicts.size} 个判定方法的记录")
    }

    /**
     * Dobby hook libc 的 `openat`。
     *
     * 这个目标的权益判定完全在 Java 层，原生层没有可挂的落点 —— 但它的配置是**加密**
     * 存的（键和值都是 base64 密文），想知道某个结论落在哪个文件、有没有被读到，
     * 从文件这一侧看最快。这是原生层在这里唯一站得住的用途。
     */
    private fun HookScope.installNativeFileLog() {
        if (!NativeHook.isAvailable) error("原生层不可用：${NativeHook.lastError}")
        NativeHook.setVerbose(true)
        NativeHook.setOpenatFilters(
            "shared_prefs",
            "secretshoot",
            "secret_shoot",
            "SecretShootConfig",
        )
        if (!NativeHook.install("openat_logger")) error("openat_logger 没装上")
        log.i("原生文件读取记录已生效")
    }

    // -----------------------------------------------------------------------

    private fun HookScope.credentialGetter(): Method {
        val vaultClass = vault ?: error("定位不到权益中枢")
        val beanClass = bean ?: error("定位不到会员凭据类")
        return SecretShoot.credentialGetter(vaultClass, beanClass)
            ?: error("${vaultClass.name} 上找不到取凭据的方法（应用结构可能已变）")
    }

    private companion object {
        /** 覆盖合成凭据用的商品号。必须含 `permanent` 才会被判成永久会员。 */
        const val KEY_PRODUCT_ID = "product_id"

        const val PERMANENT_MARK = "permanent"
    }
}
