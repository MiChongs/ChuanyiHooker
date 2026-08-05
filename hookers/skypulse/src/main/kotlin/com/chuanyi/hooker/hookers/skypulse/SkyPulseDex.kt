package com.chuanyi.hooker.hookers.skypulse

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.PublicKey

/**
 * 用 DexKit 按**特征串**定位会员体系的每一个落点，全程不写死任何类名方法名。
 *
 * 3.5.25 那版的策略是本地校验：激活码由内置 HMAC 密钥签发，算一次就能自己填。
 * 3.5.49 换掉了整套 —— 激活改成服务端签发 RSA JWT，本地只做验签：
 *
 * ```
 * 签名校验(Context)Z   APK 证书 SHA-256 比对，不过就把 JWT 删掉
 *        ↓
 * 验签(String)Payload  SHA256withRSA，公钥硬编码；payload = {device_id, activated_at, exp, ver}
 *                      ver 必须 ≥ 2；exp == 0 表示永不过期，exp > 0 且已过则拒
 *        ↓
 * 判定(Context)Z       签名过 && JWT 验过 && payload.device_id == 本机设备码
 * ```
 *
 * 那个**判定**方法是全app唯一的汇合点，三条路都通向它：
 *
 * | 调用方 | 路径 |
 * |---|---|
 * | 仓库构造函数 | 私有 `()Z` → 判定，用来 seed `StateFlow<Boolean> isPremium` |
 * | 三个桌面小组件 | 伴生对象 `(Context)Z` → 判定 |
 * | 设置页文案 | 伴生对象 `(Context)Z` → `旧版标记 && !判定` |
 *
 * 所以拿下判定一个点，设置页、小组件、两个通知 Worker、逐日预报卡片全部到位。
 *
 * 定位靠的全是**协议字段和持久化键**：改一次就会丢掉全体老用户的授权，比 R8 每次
 * 构建都重排的类名稳得多。三条路依次试：设置项覆盖 → 按 versionCode 的缓存 → 全量扫描。
 * 没有「写死名字」的第四条退路 —— 写死一个会漂的字母只会带来误伤。
 */
internal object SkyPulseDex {

    // --- 特征串。全部是目标自己的存储键与 JWT 字段名 ------------------------

    /** 加密存储里放 JWT 的键。 */
    const val KEY_JWT = "membership_jwt"

    /** 激活时间戳的键，设置页直接显示它。 */
    const val KEY_ACTIVATED_AT = "membership_activated_at"

    /** 3.5.25 及更早留下的旧版标记，新版只用它判断「是不是老会员」。 */
    const val KEY_LEGACY_PREMIUM = "membership_premium"

    /** EncryptedSharedPreferences 的文件名。 */
    const val PREFS_NAME = "sky_pulse_membership"

    /** 设备码 = SHA-256(ANDROID_ID + 这个盐)[:8] 大写。 */
    const val DEVICE_SALT = "sp_dev_salt_7f3a"

    // JWT payload 的四个字段，验签方法同时引用它们。
    const val CLAIM_DEVICE_ID = "device_id"
    const val CLAIM_EXP = "exp"
    const val CLAIM_VER = "ver"

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_CACHE_VERSION = "skypulse.version"
    private const val KEY_CACHE_ENTRIES = "skypulse.entries"

    private val BOOLEAN = Boolean::class.javaPrimitiveType!!
    private val LONG = Long::class.javaPrimitiveType!!

    /**
     * 定位结果。每一项都可能为 null —— 少一项只是少一个功能，不该让整个 hooker 罢工。
     */
    class Refs(
        /** `(Context)Z` 会员判定，全部 gate 的汇合点。 */
        val verdict: Method?,
        /** `()J` 激活时间戳读取，设置页显示它，0 会渲染成 1970。 */
        val activatedAt: Method?,
        /** `(Context)SharedPreferences` 目标自己的加密存储工厂。 */
        val securePrefs: Method?,
        /** `(Context)String` 本机设备码。 */
        val deviceId: Method?,
        /** `(String)Payload` JWT 验签，验不过返回 null。 */
        val verifyToken: Method?,
        /**
         * `(Context)void` 验签器的初始化，目标在 `Application.onCreate` 里调它存下
         * application context。
         *
         * 它是全流程**最早**且一定会发生的那个点 —— 会员判定挂在 Hilt `@Singleton` 上，
         * 不进设置页就不会被创建，而桌面小组件和通知 Worker 走的又是另一条路。
         * 想在应用刚起来时就把凭证准备好，只能锚在这里。
         */
        val verifierInit: Method?,
        /** `()PublicKey` 验签用的公钥，通常是一对（lazy 取值 + 真正构造）。 */
        val publicKeys: List<Method>,
        /** `(Context)Z` APK 证书校验，不过就删 JWT。 */
        val signatureGuard: Method?,
    ) {
        val isEmpty: Boolean get() = verdict == null && verifyToken == null && securePrefs == null

        override fun toString(): String = buildString {
            append("判定=").append(verdict.describe())
            append(" 验签=").append(verifyToken.describe())
            append(" 存储=").append(securePrefs.describe())
            append(" 设备码=").append(deviceId.describe())
            append(" 激活时间=").append(activatedAt.describe())
            append(" 公钥=").append(if (publicKeys.isEmpty()) "-" else publicKeys.joinToString("/") { it.name })
            append(" 签名校验=").append(signatureGuard.describe())
        }

        private fun Method?.describe(): String =
            if (this == null) "-" else "${declaringClass.simpleName}.$name"
    }

    // -----------------------------------------------------------------------

    fun resolve(scope: HookScope): Refs {
        configured(scope)?.let {
            scope.log.i("会员体系来自设置项：$it")
            return it
        }
        cached(scope)?.let {
            scope.log.d("会员体系命中缓存：$it")
            return it
        }
        val scanned = scan(scope)
        if (!scanned.isEmpty) {
            remember(scope, scanned)
            scope.log.i("会员体系扫描到：$scanned")
        }
        return scanned
    }

    // -----------------------------------------------------------------------

    /**
     * 应急通道：目标大改到扫描也找不着时，不重新出包就能靠设置项把类名喂进来。
     * 只认类名，方法仍按形状在类里挑 —— 形状比名字稳，没必要连方法名一起填。
     */
    private fun configured(scope: HookScope): Refs? {
        val repository = scope.string(SkyPulseHooker.KEY_REPOSITORY_CLASS)?.takeIf { it.isNotBlank() }
        val verifier = scope.string(SkyPulseHooker.KEY_VERIFIER_CLASS)?.takeIf { it.isNotBlank() }
        if (repository == null && verifier == null) return null

        val repoClass = repository?.let { scope.classOrNull(it) }
        val verifierClass = verifier?.let { scope.classOrNull(it) }
        if (repository != null && repoClass == null) scope.log.w("设置项里的 $repository 不存在")
        if (verifier != null && verifierClass == null) scope.log.w("设置项里的 $verifier 不存在")

        return fromClasses(repoClass, verifierClass, null)
    }

    /**
     * 扫一次要几百毫秒，而它就发生在应用的启动路径上 —— 结果按 versionCode 缓存。
     *
     * 缓存写在**目标自己**的数据目录：模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cached(scope: HookScope): Refs? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_CACHE_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val entries = prefs.getString(KEY_CACHE_ENTRIES, null)?.takeIf { it.isNotBlank() } ?: return null

        val names = entries.split('|')
        val repoClass = names.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { scope.classOrNull(it) }
        val verifierClass = names.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { scope.classOrNull(it) }
        val guardClass = names.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { scope.classOrNull(it) }

        // 覆盖安装但 versionCode 没变时名字可能已经对不上，当没缓存重扫。
        val refs = fromClasses(repoClass, verifierClass, guardClass)
        return if (refs.isEmpty) null else refs
    }

    private fun remember(scope: HookScope, refs: Refs) {
        val prefs = cache(scope) ?: return
        val entries = listOf(
            refs.verdict?.declaringClass?.name.orEmpty(),
            refs.verifyToken?.declaringClass?.name.orEmpty(),
            refs.signatureGuard?.declaringClass?.name.orEmpty(),
        ).joinToString("|")
        runCatching {
            prefs.edit()
                .putLong(KEY_CACHE_VERSION, scope.versionCode)
                .putString(KEY_CACHE_ENTRIES, entries)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    // -----------------------------------------------------------------------

    /**
     * 一次打开 DexKit，四组查询拿齐全部落点。
     *
     * 查询只按特征串发，形状分类留到 Kotlin 里做 —— 比给每个 matcher 塞参数类型快，
     * 也省得为「无参」这种条件跟 DSL 较劲。不限包：`l2` 这种顶层短包名本身就是 R8
     * 的产物，下一版就会变，限定它等于把写死类名换个地方写。
     */
    private fun scan(scope: HookScope): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return empty()
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而在 LSPosed
        // 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，等到
        // 第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块
        // 自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载一次，DexKit
        // 自己那次随后就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return empty()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return empty()

        return bridge.use { dex ->
            fun usingStrings(vararg needles: String): List<Method> =
                runCatching {
                    dex.findMethod { matcher { usingStrings(needles.toList()) } }
                        .mapNotNull { hit ->
                            runCatching { hit.getMethodInstance(scope.classLoader) }
                                .onFailure { scope.log.d("${hit.descriptor} 反射不出来：${it.message}") }
                                .getOrNull()
                        }
                }.onFailure { scope.log.w("扫 ${needles.joinToString()} 失败：${it.message}") }
                    .getOrDefault(emptyList())

            // 引用 JWT 键的有三处：判定（读+删）、激活（写）、续签（读+写）。
            // 判定是其中唯一的 (Context)Z。
            val jwtUsers = usingStrings(KEY_JWT)
            val verdict = jwtUsers.singleOrNull { it.matches(BOOLEAN, Context::class.java) }
                ?: jwtUsers.firstOrNull { it.matches(BOOLEAN, Context::class.java) }

            // 验签方法同时引用 payload 的三个字段名；应用里没有第二处会这么干。
            val verifyToken = usingStrings(CLAIM_DEVICE_ID, CLAIM_EXP, CLAIM_VER)
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }

            // 加密存储文件名只被它自己的工厂方法引用。
            val securePrefs = usingStrings(PREFS_NAME)
                .firstOrNull { it.matches(SharedPreferences::class.java, Context::class.java) }

            // 设备盐同理，只被设备码计算引用。
            val deviceId = usingStrings(DEVICE_SALT)
                .firstOrNull { it.matches(String::class.java, Context::class.java) }

            // 激活时间：写它的有两处（激活、续签），读它的只有一个无参 ()J。
            val activatedAt = usingStrings(KEY_ACTIVATED_AT)
                .firstOrNull { it.matches(LONG) }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            scope.log.d("dex 扫描用时 ${elapsed}ms")

            val refs = merge(
                verdict = verdict,
                activatedAt = activatedAt,
                securePrefs = securePrefs,
                deviceId = deviceId,
                verifyToken = verifyToken,
                verifierClass = verifyToken?.declaringClass,
                guardClass = null,
                scope = scope,
            )
            if (refs.isEmpty) scope.log.w("dex 里找不到会员体系的任何落点（${elapsed}ms）")
            refs
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 从已知的类回填全部方法，缓存与设置项两条路共用。
     */
    private fun fromClasses(
        repositoryClass: Class<*>?,
        verifierClass: Class<*>?,
        guardClass: Class<*>?,
    ): Refs {
        // 判定挂在伴生对象上，而伴生对象是仓库类的静态字段之一。
        val verdictOwners = listOfNotNull(repositoryClass) + companionsOf(repositoryClass)
        val verdict = verdictOwners.firstNotNullOfOrNull { owner ->
            owner.methods { it.matches(BOOLEAN, Context::class.java) }.singleOrNull()
        }
        val securePrefs = verdictOwners.firstNotNullOfOrNull { owner ->
            owner.methods { it.matches(SharedPreferences::class.java, Context::class.java) }.singleOrNull()
        }
        val deviceId = verdictOwners.firstNotNullOfOrNull { owner ->
            owner.methods { it.matches(String::class.java, Context::class.java) }.singleOrNull()
        }
        val activatedAt = repositoryClass?.methods { it.matches(LONG) }?.singleOrNull()
        val verifyToken = verifierClass?.methods {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && !it.returnType.isPrimitive
        }?.singleOrNull()

        return merge(
            verdict = verdict,
            activatedAt = activatedAt,
            securePrefs = securePrefs,
            deviceId = deviceId,
            verifyToken = verifyToken,
            verifierClass = verifierClass,
            guardClass = guardClass,
            scope = null,
        )
    }

    /**
     * 补齐两项要靠别的落点才能定位的东西，然后组装。
     *
     * * **公钥**：验签类里返回 [PublicKey] 的无参方法。通常有两个 —— Kotlin `lazy`
     *   的取值包装和真正 `KeyFactory.generatePublic` 的那个 —— 两个都收下，
     *   [SkyPulseHooker] 会一起 hook：谁先被调到都算数，也省得判断 lazy 求过值没有。
     * * **签名校验**：判定方法体里调的第一个 `(Context)Z`。按调用关系找而不是按那串
     *   硬编码的证书哈希找，因为哈希会随签名轮换变，调用关系不会。
     */
    private fun merge(
        verdict: Method?,
        activatedAt: Method?,
        securePrefs: Method?,
        deviceId: Method?,
        verifyToken: Method?,
        verifierClass: Class<*>?,
        guardClass: Class<*>?,
        scope: HookScope?,
    ): Refs {
        val publicKeys = verifierClass
            ?.methods { it.parameterCount == 0 && PublicKey::class.java.isAssignableFrom(it.returnType) }
            ?: emptyList()

        val verifierInit = verifierClass
            ?.methods { it.matches(Void.TYPE, Context::class.java) }
            ?.singleOrNull()

        val signatureGuard = guardClass
            ?.methods { it.matches(BOOLEAN, Context::class.java) }?.singleOrNull()
            ?: findSignatureGuard(verdict, verifierClass, scope)

        return Refs(
            verdict = verdict?.accessible(),
            activatedAt = activatedAt?.accessible(),
            securePrefs = securePrefs?.accessible(),
            deviceId = deviceId?.accessible(),
            verifyToken = verifyToken?.accessible(),
            verifierInit = verifierInit?.accessible(),
            publicKeys = publicKeys.map { it.accessible() },
            signatureGuard = signatureGuard?.accessible(),
        )
    }

    /**
     * 证书校验器不引用任何存储键，没有自己的特征串可查。
     *
     * 但它被判定和验签**两处**调用，而且形状固定是 `(Context)Z` —— 于是反过来找：
     * 全 app 里除判定自己以外，还有谁是 `(Context)Z` 且被这两个类引用。这里用最省事的
     * 一条：验签类和判定类都持有它的话，它必然出现在两者之一的静态字段类型里。
     *
     * 找不到也无所谓 —— 我们不改包，证书本来就是原装的，这个 hook 只是给重打包版兜底。
     */
    private fun findSignatureGuard(
        verdict: Method?,
        verifierClass: Class<*>?,
        scope: HookScope?,
    ): Method? {
        val owners = listOfNotNull(verdict?.declaringClass, verifierClass)
        val candidates = owners.asSequence()
            .flatMap { it.declaredFields.asSequence() }
            .map { it.type }
            .distinct()
            .filter { it != verdict?.declaringClass && it != verifierClass }
            .mapNotNull { type ->
                type.methods { it.matches(BOOLEAN, Context::class.java) }.singleOrNull()
            }
            .toList()

        if (candidates.size > 1) {
            scope?.log?.d("证书校验器有 ${candidates.size} 个候选，形状分不出来，放弃")
            return null
        }
        return candidates.firstOrNull()
    }

    private fun empty() = Refs(null, null, null, null, null, null, emptyList(), null)

    // --- 小工具 -------------------------------------------------------------

    /**
     * Kotlin 会为被 lambda 捕获的私有成员生成 `public static synthetic` 访问器，
     * 形状和真正的目标一模一样。synthetic 和 bridge 永远不是我们要找的东西。
     */
    private fun Class<*>.methods(predicate: (Method) -> Boolean): List<Method> =
        declaredMethods.filter { !it.isSynthetic && !it.isBridge && predicate(it) }

    private fun Method.matches(returns: Class<*>, vararg params: Class<*>): Boolean =
        returnType == returns && parameterTypes.contentEquals(arrayOf(*params))

    private fun Method.accessible(): Method = apply { isAccessible = true }

    /**
     * 反射调用一个已定位的方法时，`this` 该传谁。
     *
     * 三种情况，顺序不能反：
     *
     * 1. **静态方法** → null。
     * 2. **Kotlin `object`** → 单例在**它自己**的静态字段里（`q.INSTANCE`）。
     * 3. **Kotlin `companion object`** → 单例在**外部类**的静态字段里
     *    （`l2.o.INSTANCE`，类型是 `l2.o$a`）。伴生类自己身上没有这个字段。
     *
     * 第 3 条是这里唯一的坑：会员判定、加密存储工厂、设备码全挂在伴生对象上，
     * 只查 `declaringClass` 的字段会一个都找不到，`invoke` 随后报 `null receiver`。
     *
     * 外部类优先用 [Class.getEnclosingClass]，它返回 null 时按名字截 —— R8 丢
     * InnerClasses 属性丢得很勤，而 `l2.o$a` → `l2.o` 这个形状是编译器定的，不会漂。
     */
    fun Method.ownerInstance(): Any? {
        if (Modifier.isStatic(modifiers)) return null
        val owner = declaringClass

        owner.singletonOf(owner)?.let { return it }

        val enclosing = runCatching { owner.enclosingClass }.getOrNull()
            ?: owner.name.substringBeforeLast('$', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() && it != owner.name }
                ?.let { runCatching { Class.forName(it, false, owner.classLoader) }.getOrNull() }

        return enclosing?.singletonOf(owner)
    }

    /** [holder] 的静态字段里那个类型为 [type] 的单例。 */
    private fun Class<*>.singletonOf(type: Class<*>): Any? =
        declaredFields
            .firstOrNull { Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }
            ?.let { runCatching { it.apply { isAccessible = true }.get(null) }.getOrNull() }

    /**
     * 伴生对象藏在静态字段里。与其相信字段名或 `declaredClasses`（R8 丢
     * InnerClasses 属性丢得很勤），不如把所有非原始类型的静态字段类型都当候选。
     */
    private fun companionsOf(clazz: Class<*>?): List<Class<*>> =
        clazz?.declaredFields
            ?.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            ?.map { it.type }
            ?.distinct()
            .orEmpty()
}
