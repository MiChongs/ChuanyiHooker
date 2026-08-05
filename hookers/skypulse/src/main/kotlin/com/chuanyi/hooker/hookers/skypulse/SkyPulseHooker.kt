package com.chuanyi.hooker.hookers.skypulse

import android.content.Context
import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createInterceptHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReturnConstantHook
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SkyPulse（`com.skypulse.weather`）—— Compose 写的天气应用，会员制。
 *
 * 3.5.25 那版是纯本地校验：激活码由内置 HMAC 密钥签发，把密钥挖出来就能自己算一个填进去。
 * 3.5.49 把整套换掉了 —— 激活改成联网领取服务端 RSA 签发的 JWT，本地只留验签：
 *
 * ```
 * 安装包证书校验 → JWT 验签（SHA256withRSA，公钥硬编码）→ payload.device_id == 本机设备码
 * ```
 *
 * 私钥在服务端，伪造不了。但**验签在本地**，公钥就明文躺在 dex 里 —— 换掉它，
 * 模块就能自己签一张 `exp = 0` 的凭证，而 `exp = 0` 正是目标自己定义的「永不过期」。
 *
 * 两个主功能覆盖两种需求，互不依赖，一起开是双保险：
 *
 * | 功能 | 做法 | 特点 |
 * |---|---|---|
 * | [FEATURE_PREMIUM] | 判定函数恒真 | 零写盘，关掉即恢复原样 |
 * | [FEATURE_CREDENTIAL] | 换公钥 + 自签凭证写盘 | 目标内部状态自洽，走的全是它自己的正常分支 |
 *
 * 全部落点由 [SkyPulseDex] 按目标自己的存储键和 JWT 字段名定位，不写死任何类名方法名 ——
 * 3.5.25 到 3.5.49 之间那些名字已经全变过一轮了。
 */
class SkyPulseHooker : AppHooker {

    override val id = "skypulse"
    override val displayName = "SkyPulse 天气"
    override val description = "解锁永久会员"
    override val targetPackages = setOf("com.skypulse.weather")

    /** 在 [isCompatible] 里解析一次，那时候还没有任何东西装上去。 */
    @Volatile
    private var refs: SkyPulseDex.Refs? = null

    /** 判定 hook 由两个功能共用，谁先装谁装。 */
    private val verdictInstalled = AtomicBoolean(false)
    private val credentialAttempted = AtomicBoolean(false)

    /** 凭证真的签发出去了 —— 只有这之后才可以拿模块公钥去顶替目标的。 */
    private val credentialIssued = AtomicBoolean(false)

    private val deviceIdShown = AtomicBoolean(false)
    private val verdictLogged = AtomicBoolean(false)
    private val cachedActivatedAt = AtomicLong(0L)

    /** 两个开关在安装时读一次记下来，hook 回调里不再回头查设置。 */
    @Volatile
    private var forceUnlock = false

    @Volatile
    private var writeCredential = false

    override val features: List<HookFeature> = listOf(
        // 放在最前面：它保护的那个方法是会员判定的第一步，后面每一项都要经过它。
        HookFeature(
            id = FEATURE_REPAIR_STORE,
            title = "修复加密存储（防闪退）",
            summary = "应用的加密存储主密钥一旦失效就会在启动第一帧直接闪退，" +
                "而它自己没有任何补救 —— 别名固定，下次启动照样取到那把死掉的密钥。" +
                "换过锁屏密码、重录过指纹、或卸载重装后都可能触发。" +
                "开启后自动清掉失效密钥并重建，代价是要重新激活一次",
            install = { installStoreGuard() },
        ),
        HookFeature(
            id = FEATURE_PREMIUM,
            title = "解锁永久会员",
            summary = "让应用直接认定永久会员已开通，会员功能全部可用。" +
                "不往应用里写任何数据，关掉就恢复原样",
            install = { installPremium() },
        ),
        HookFeature(
            id = FEATURE_CREDENTIAL,
            title = "写入永久授权凭证",
            summary = "把验签公钥换成模块自己的，再签一张永不过期的凭证写进应用自己的加密存储。" +
                "会员状态、激活时间、桌面小组件走的都是应用原本的分支，不存在漏网的判断点。" +
                "已经是真会员时会自动跳过，不覆盖原有凭证",
            install = { installCredential() },
        ),
        HookFeature(
            id = FEATURE_SKIP_SIGNATURE,
            title = "跳过安装包签名校验",
            summary = "应用启动时会核对自己的签名，对不上就把授权凭证删掉。" +
                "用原版安装包不受影响，装的是重打包版本才需要这项",
            install = { installSignatureBypass() },
        ),
        HookFeature(
            id = FEATURE_SHOW_DEVICE_ID,
            title = "显示本机设备码",
            summary = "在日志里打印一次本机设备码。凭证是绑定设备的，换机或恢复出厂后需要重新签发",
            defaultEnabled = false,
            requiresRestart = false,
            install = { installShowDeviceId() },
        ),
    )

    /**
     * 永远认领这个进程，哪怕一个落点都没定位到。
     *
     * 一度这里在 `refs.isEmpty` 时返回 false，结果是**防闪退功能跟着一起没装** ——
     * dex 扫描失败（`libdexkit.so` 没加载、目标结构大改）的那一次，恰恰是应用最需要
     * 被兜住的一次。解锁不了顶多是没解锁，崩溃是完全不能用，两者不该共享同一个开关。
     *
     * 定位不到的功能会各自 warn 并跳过，框架也允许单个功能失败而不牵连其余。
     */
    override fun isCompatible(scope: HookScope): Boolean {
        val resolved = SkyPulseDex.resolve(scope)
        if (resolved.isEmpty) {
            scope.log.w(
                "没能在 dex 里定位到会员体系 —— 版本 ${scope.versionCode} 可能改了结构；" +
                    "可以用设置项 '$KEY_REPOSITORY_CLASS' / '$KEY_VERIFIER_CLASS' 把类名喂进来。" +
                    "防闪退仍然有效",
            )
        }
        refs = resolved
        return true
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("SkyPulse ${scope.versionCode} 进程 ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * 给加密存储的工厂方法加护栏。
     *
     * 挂在**方法**上而不是包在模块自己的调用外面，这样应用自己那些调用也一并被接住 ——
     * 崩溃发生在它自己的路径上（判定 → 打开存储），模块只在旁边看着没有意义。
     */
    private fun HookScope.installStoreGuard() {
        // 先体检。这一步不需要 DexKit，也不需要 Context —— 扫描失败时它是唯一还在岗的。
        SecureStore.verifyOrRepair(this)

        // 再挂接住式护栏，覆盖运行期间才失效、或别名不是默认值的情况。
        val factory = refs?.securePrefs
        if (factory == null) {
            log.d("没定位到加密存储工厂方法，只有开工前那次体检生效")
            return
        }
        SecureStore.guard(this, factory)
    }

    /**
     * 判定函数恒真。
     *
     * 那个 `(Context)Z` 是全app唯一的汇合点：仓库构造函数用它 seed `isPremium` 这个
     * `StateFlow`，三个桌面小组件绕过 StateFlow 直接调它，设置页的文案也从它派生。
     * 拿下这一个点，设置页、小组件、两个通知 Worker、逐日预报卡片一起到位。
     *
     * 顺带把激活时间戳补上。3.5.49 的界面其实没有把它显示出来 —— 那个 `()J` 一路
     * 转发到 ViewModel 就没有下文了 —— 但目标既然留着这个 public getter 就有消费的打算，
     * 补一个合理值的成本是一行，比哪天它重新显示出来时露出 1970 划算。
     */
    private fun HookScope.installPremium() {
        forceUnlock = true
        installVerdictHook()

        val activatedAt = requireRefs().activatedAt
        if (activatedAt == null) {
            log.d("没定位到激活时间读取方法；当前版本界面并不显示它，不影响使用")
            return
        }
        activatedAt.createAfterHook("skypulse.activated_at") { param ->
            if ((param.result as? Long ?: 0L) == 0L) param.result = activationSeconds()
        }
    }

    /**
     * 换公钥 + 自签凭证。
     *
     * 公钥的顶替**必须**等到凭证真的签发之后才生效（[credentialIssued]）：真付费用户的
     * 凭证是服务端私钥签的，提前换掉公钥会把人家本来有效的会员判成无效。
     * [ensureCredential] 里那次 `hasValidToken` 正是在这个 hook 还没生效时做的，
     * 用的是目标原装的公钥。
     */
    private fun HookScope.installCredential() {
        writeCredential = true
        installVerdictHook()

        // 主路：应用刚起来就把凭证准备好。
        //
        // 光靠判定 hook 是不够的 —— 会员判定挂在 Hilt `@Singleton` 的仓库上，不进设置页
        // 根本不会被创建，于是桌面小组件和通知 Worker 那两条路会读到「未激活」。锚在验签器
        // 的初始化上就没有这个问题：它在 `Application.onCreate` 里，一定发生、且早于一切。
        val init = requireRefs().verifierInit
        if (init != null) {
            init.createAfterHook("skypulse.credential.early") { param ->
                (param.args.getOrNull(0) as? Context)?.let { ensureCredential(it) }
            }
            log.d("凭证写入已锚在 ${init.declaringClass.name}.${init.name}")
        } else {
            log.d("没定位到验签器初始化方法，凭证要等会员判定第一次发生时才写")
        }

        val publicKeys = requireRefs().publicKeys
        if (publicKeys.isEmpty()) {
            log.d("没定位到公钥读取方法，只靠内存替换那条路")
            return
        }
        // 通常是一对：Kotlin lazy 的取值包装、和真正 KeyFactory 构造的那个。
        // 两个都挂上，谁先被调到都算数，也省得判断 lazy 求过值没有。
        publicKeys.forEach { getter ->
            getter.createAfterHook("skypulse.publickey.${getter.name}") { param ->
                if (credentialIssued.get()) param.result = Lifetime.publicKey
            }
        }
        log.d("公钥顶替已挂在 ${publicKeys.joinToString { it.name }}")
    }

    /**
     * 一个 hook，两个开关。
     *
     * 两个功能都作用在同一个方法上，各装各的会在一个方法上叠两层常量。这里合成一处：
     * 先给写凭证的机会（判定方法的入参就是 `Context`，时机天然正确 —— 目标正要去验 JWT，
     * 我们抢在它前面把公钥换掉、凭证写好），再让它自己判一次；它自己判出 true 才是
     * 状态自洽的结果，判不出来才轮到强制解锁兜底。
     */
    private fun HookScope.installVerdictHook() {
        if (!verdictInstalled.compareAndSet(false, true)) return

        val verdict = requireRefs().verdict
        if (verdict == null) {
            log.w("没定位到会员判定方法，解锁无法生效")
            return
        }

        verdict.createInterceptHook("skypulse.verdict") { chain ->
            if (writeCredential) {
                (chain.getArg(0) as? Context)?.let { ensureCredential(it) }
            }

            val real = chain.proceed() as? Boolean ?: false
            val answer = real || forceUnlock

            // 「应用自己判出来是真的」和「靠强制解锁兜的」是两种不同的成功，
            // 排查时区别很大 —— 前者说明凭证真的生效了。第一次各记一条。
            if (verdictLogged.compareAndSet(false, true)) {
                log.i(if (real) "会员判定：应用自己判定为已开通（凭证已生效）" else "会员判定：强制解锁生效")
            }
            answer
        }
        log.i("会员判定已接管：${verdict.declaringClass.name}.${verdict.name}")
    }

    /**
     * 签一张永不过期的凭证写进目标自己的加密存储。只做一次。
     *
     * 顺序是有讲究的：**先**用目标原装的公钥验一遍现有凭证，有效就原样退出 ——
     * 真付费用户不该被模块改成自签的。确认无效之后才换公钥、才签。
     */
    private fun HookScope.ensureCredential(context: Context) {
        if (!credentialAttempted.compareAndSet(false, true)) return
        val license = requireRefs()

        // 开不开得了不能假设 —— 主密钥失效时这里会先修一次再开。
        val prefs = SecureStore.openOrRepair(this, license, context)
        if (prefs == null) {
            log.w("拿不到应用的加密存储，写不了凭证；仍可用「解锁永久会员」")
            return
        }

        // 真付费用户的凭证是服务端私钥签的，用目标原装的公钥能验过 —— 一个字都别动。
        if (Lifetime.hasValidToken(license, prefs)) {
            log.i("现有授权凭证本来就是有效的，保持原样")
            return
        }

        // 上一次自己签的还在。冷启动时内存是新的、公钥又变回原装那把，所以上面那次必然
        // 判否 —— 照着走就会每次启动重签一张。这里只把公钥换回去，凭证留着。
        if (Lifetime.hasOwnToken(prefs)) {
            Lifetime.swapPublicKeyInMemory(this)
            credentialIssued.set(true)
            log.i("已有本模块签发的永久凭证，公钥已就位")
            return
        }

        val deviceId = Lifetime.deviceId(this, license, context)
        if (deviceId.isNullOrBlank()) {
            log.w("算不出本机设备码，写不了凭证")
            return
        }

        // 内存里那条腿：不依赖任何类名，赶在目标第一次验签之前把公钥换掉。
        Lifetime.swapPublicKeyInMemory(this)

        // 秒，跟 JWT 里的 exp / activated_at 同一把尺子 —— 服务端下发的那份也是秒，
        // 混用毫秒会让这两个值差三个数量级，虽然当前界面看不出来。
        val activatedAt = activationSeconds()
        val token = runCatching { Lifetime.issueToken(deviceId, activatedAt) }
            .onFailure { log.e("签发凭证失败", it) }
            .getOrNull() ?: return

        val stored = runCatching {
            prefs.edit()
                .putString(SkyPulseDex.KEY_JWT, token)
                .putLong(SkyPulseDex.KEY_ACTIVATED_AT, activatedAt)
                .commit()
        }.onFailure { log.e("凭证写入失败", it) }.getOrDefault(false)

        if (!stored) {
            log.w("凭证没能写进加密存储，仍可用「解锁永久会员」")
            return
        }
        credentialIssued.set(true)
        log.i("已为设备 $deviceId 签发并写入永不过期的授权凭证")
    }

    /**
     * 证书校验不过时目标会主动把凭证删掉，所以重打包版本装上就等于每次启动自毁一次。
     * 原版安装包走不到这条分支，这一项对它是纯粹的空转。
     */
    private fun HookScope.installSignatureBypass() {
        val guard = requireRefs().signatureGuard
        if (guard == null) {
            log.d("没定位到签名校验方法；用原版安装包本来就不需要这一项")
            return
        }
        guard.createReturnConstantHook("skypulse.signature", true)
        log.d("签名校验已恒真：${guard.declaringClass.name}.${guard.name}")
    }

    private fun HookScope.installShowDeviceId() {
        val getter = requireRefs().deviceId
        if (getter == null) {
            log.w("没定位到设备码读取方法")
            return
        }
        getter.createAfterHook("skypulse.device_id") { param ->
            val value = param.result as? String ?: return@createAfterHook
            if (deviceIdShown.compareAndSet(false, true)) log.i("本机设备码：$value")
        }
    }

    // -----------------------------------------------------------------------

    /**
     * refs 一定非 null（[isCompatible] 总会赋值，且它一定先于任何功能安装运行），
     * 但里面的每一项都可能是 null —— 调用方各自检查，少一项只少一个功能。
     */
    private fun requireRefs(): SkyPulseDex.Refs =
        refs ?: error("refs 尚未解析；isCompatible 一定先于任何功能安装运行")

    /**
     * 「什么时候激活的」的一个稳定答案，**秒**。
     *
     * 用安装时间而不是 `now`：这个值会写进凭证，每次冷启动都往前跳既没道理也不像真的。
     */
    private fun HookScope.activationSeconds(): Long {
        cachedActivatedAt.get().let { if (it != 0L) return it }
        val installedMillis = runCatching {
            appContextOrNull()?.packageManager?.getPackageInfo(packageName, 0)?.firstInstallTime
        }.getOrNull()?.takeIf { it > 0L } ?: System.currentTimeMillis()
        cachedActivatedAt.compareAndSet(0L, installedMillis / 1000)
        return cachedActivatedAt.get()
    }

    companion object {
        const val FEATURE_REPAIR_STORE = "repair_secure_store"
        const val FEATURE_PREMIUM = "premium"
        const val FEATURE_CREDENTIAL = "lifetime_credential"
        const val FEATURE_SKIP_SIGNATURE = "skip_signature_check"
        const val FEATURE_SHOW_DEVICE_ID = "show_device_id"

        /**
         * 应急设置项：目标大改到连特征串扫描都落空时，不重新出包就能把类名喂进来。
         * 只需要类名 —— 方法仍按形状在类里挑，形状比名字稳。
         */
        const val KEY_REPOSITORY_CLASS = "repository_class"
        const val KEY_VERIFIER_CLASS = "verifier_class"
    }
}
