package com.chuanyi.hooker.hookers.chuckle

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.MethodCondition
import com.highcapable.kavaref.condition.type.Modifiers
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 按**特征串**定位权益体系的每一个落点，全程不写死类名方法名。
 *
 * 1.1.0 的包名保留（`app.jjyy.chuckle.*`）但成员名被 R8 重排过一轮 ——
 * 判权益的那几个类落在了无包名的 `defpackage` 里，叫 `d1e` / `iqd` / `a4` / `fpg`。
 * 这种名字下一次构建就会变，写死一个等于给模块设一个到期日。
 *
 * 所以锚点全部选**改一次就会付出代价**的串：
 *
 * | 特征串 | 改了会怎样 |
 * |---|---|
 * | `advanced_user_buy` / `settings_iap` | 全体老用户的权益记录读不出来，等于集体掉线 |
 * | `app.jjyy.purchase.model.*` | kotlinx.serialization 的 `@SerialName`，改了和服务端对不上 |
 * | `freemium_enable` | 服务端下发的开关键，改了整套广告策略失联 |
 *
 * 定位到类之后，方法一律**按形状挑**而不是按名字 —— 形状由 Kotlin 编译器决定，比名字稳。
 *
 * 权益链本身极简，这是它值得用数据驱动而非拦函数的原因：
 *
 * ```text
 * 判定  = code 非空 && !expired
 * 三态  = if (判定 && info.freeTrial) 试用 else if (判定) 已订阅 else 免费
 * 广告  = 三态 == 免费 时才出
 * ```
 *
 * 也就是说：只要权益对象自身自洽（`code` 非空、`expired=false`、`freeTrial=false`），
 * 判定、三态、去广告、订阅页展示会**一起**成立，一个方法都不必接管。
 * 落点因此以「权益对象的读取端」为主，判定函数只作为定位失败时的兜底。
 */
internal object ChuckleDex {

    // --- 特征串。全是目标自己的存储键与序列化模型名 --------------------------

    /** 权益存储所在的 SharedPreferences 文件名。 */
    const val PREFS_IAP = "settings_iap"

    /** 权益记录的键。值是 Base64 过的 JSON，见 [Entitlement]。 */
    const val KEY_WALLET = "advanced_user_buy"

    /** 首启迁移标记，和 [KEY_WALLET] 出现在同一个静态初始化块里。 */
    const val KEY_INIT = "advanced_init"

    /** 联网复验的节流时间戳。 */
    const val KEY_SERVICE_CHECK = "advanced_service_check"

    // kotlinx.serialization 的 @SerialName，和服务端共享，不会随 R8 变。
    const val MODEL_SUBSCRIPTION = "app.jjyy.purchase.model.SubscriptionsBean"
    const val MODEL_INFO = "app.jjyy.purchase.model.SubscriptionsInfoBean"
    const val MODEL_DEVICE = "app.jjyy.purchase.model.DeviceInfo"

    /** 服务端下发的免费增值总开关，只被权益三态那个类读。 */
    const val FLAG_FREEMIUM = "freemium_enable"

    /** 强制激励广告的间隔，和 [FLAG_FREEMIUM] 同类。 */
    const val FLAG_REWARD_INTERVAL = "ad_premium_rewarded_interval_minutes"

    // 两个风控埋点的事件名。
    const val EVENT_TAMPER = "tamper_signal_fired"
    const val EVENT_RISK = "strict_mode_risk_probe"

    /** 权益三态里「已订阅」那个枚举常量的名字。 */
    private const val TIER_PAID = "PAID"

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_CACHE_VERSION = "chuckle.version"
    private const val KEY_CACHE_ENTRIES = "chuckle.entries"

    private val BOOLEAN = Boolean::class.javaPrimitiveType!!

    /**
     * 定位结果。每一项都可能为 null —— 少一项只少一个功能，不该让整个 hooker 罢工。
     */
    class Refs(
        /** `()Z` 权益判定：`code` 非空且未过期。全部 gate 的第一道。 */
        val isPremium: Method?,
        /** `()L<权益对象>;` 取当前权益记录，没有则 null。 */
        val subscription: Method?,
        /** 权益对象的类，即 `SubscriptionsBean`。 */
        val subscriptionClass: Class<*>?,
        /** `()L<三态枚举>;` 免费 / 试用 / 已订阅，广告与功能门控全从它派生。 */
        val tier: Method?,
        /** 三态枚举里的 `PAID` 常量。 */
        val tierPaid: Any?,
        /** 持久化读取方法（`()Ljava/lang/Object;`），权益对象的唯一公共入口。 */
        val walletRead: Method?,
        /**
         * 权益存储实例所在的**静态字段**，不是实例本身。
         *
         * 这里存字段而不是取值，是因为定位发生在 `handleBindApplication` 期间：
         * 读一个静态字段会强制触发它所在类的 `<clinit>`，而目标那个类的初始化第一件事
         * 就是 `getSharedPreferences`，走的是它自己的 ContextProvider ——
         * 那玩意儿要等 `Application#onCreate` 才 setApplication。
         * 提前碰它会让类初始化抛异常，而**类初始化失败是不可逆的**：
         * 该类从此被标记为 erroneous，应用后面每次用到它都拿到 NoClassDefFoundError，
         * 表现就是一启动就闪退。取值一律推迟到 hook 回调里，那时 clinit 早已完成。
         */
        val walletHolderField: Field?,
        /** 缓存字段，写盘合成后要清掉它才会重新反序列化。 */
        val walletCache: Field?,
        /** 两个风控埋点的上报方法。 */
        val analytics: List<Method>,
        /** Google Play 连接失败时弹提示的那个方法。 */
        val billingFailure: Method?,
    ) {
        /** 数据层可用 —— 只要这个成立，判定兜底就是多余的。 */
        val hasDataPath: Boolean get() = subscription != null && subscriptionClass != null

        val isEmpty: Boolean get() = subscription == null && isPremium == null && tier == null

        override fun toString(): String = buildString {
            append("判定=").append(isPremium.describe())
            append(" 权益=").append(subscription.describe())
            append(" 三态=").append(tier.describe())
            append(" 存储=").append(walletRead.describe())
            append(" 埋点=").append(if (analytics.isEmpty()) "-" else analytics.size.toString())
            append(" 计费提示=").append(billingFailure.describe())
        }

        private fun Method?.describe(): String =
            if (this == null) "-" else "${declaringClass.simpleName}.$name"
    }

    // -----------------------------------------------------------------------

    /**
     * 定位跑在 `handleBindApplication` 里 —— 和目标的 `Application#onCreate` 是同一条线程、
     * 同一次调用栈。这里漏出去任何 [Throwable] 都会**直接变成目标应用的启动崩溃**，
     * 所以整段包一层屏障：定位失败最多是模块这一轮不工作，不能连累应用起不来。
     */
    fun resolve(scope: HookScope): Refs = runCatching {
        cached(scope)?.let {
            scope.log.d("权益体系命中缓存：$it")
            return@runCatching it
        }
        scan(scope).also { scanned ->
            if (!scanned.isEmpty) {
                remember(scope, scanned)
                scope.log.i("权益体系扫描到：$scanned")
            }
        }
    }.onFailure {
        scope.log.e("权益体系定位失败，本轮不接管（应用本身不受影响）", it)
    }.getOrElse { empty() }

    // -----------------------------------------------------------------------

    /**
     * 扫一次要几百毫秒，而它就发生在启动路径上 —— 结果按 versionCode 缓存。
     *
     * 缓存写在**目标自己**的数据目录：模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cached(scope: HookScope): Refs? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_CACHE_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val entries = prefs.getString(KEY_CACHE_ENTRIES, null)?.takeIf { it.isNotBlank() } ?: return null

        val names = entries.split('|')
        val gate = names.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { scope.classOrNull(it) }
        val tier = names.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { scope.classOrNull(it) }

        // 覆盖安装但 versionCode 没变时名字可能已经对不上，当没缓存重扫。
        val refs = fromClasses(gate, tier, emptyList(), null, scope)
        return if (refs.isEmpty) null else refs
    }

    private fun remember(scope: HookScope, refs: Refs) {
        val prefs = cache(scope) ?: return
        val entries = listOf(
            refs.isPremium?.declaringClass?.name ?: refs.subscription?.declaringClass?.name.orEmpty(),
            refs.tier?.declaringClass?.name.orEmpty(),
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
     * 不限包：`defpackage` 这种无包名类本身就是 R8 的产物，限定它等于把写死类名换个地方写。
     */
    private fun scan(scope: HookScope): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return empty()
        }

        // DexKit 在自己的 static 初始化里调 System.loadLibrary("dexkit")，而在 LSPosed
        // 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，等到
        // 第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块
        // 自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载一次。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return empty()
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return empty()

        return bridge.use { dex ->
            /**
             * 引用了这些串的**类**。
             *
             * 只要类不要方法，是因为最关键的那两个串（权益存储键、prefs 文件名）出现在
             * `<clinit>` 和 `<init>` 里 —— 类初始化器根本没有对应的 `Method`，
             * `getMethodInstance` 对它必然失败。之前那版把结果 `mapNotNull` 掉，
             * 于是整个权益类静悄悄地扫不出来，只剩三态那一个落点。
             */
            fun classesUsing(vararg needles: String): List<Class<*>> =
                runCatching {
                    dex.findMethod { matcher { usingStrings(needles.toList()) } }
                        .mapNotNull { hit -> scope.classOrNull(hit.className) }
                        .distinct()
                }.onFailure { scope.log.w("扫 ${needles.joinToString()} 失败：${it.message}") }
                    .getOrDefault(emptyList())

            fun methodsUsing(vararg needles: String): List<Method> =
                runCatching {
                    dex.findMethod { matcher { usingStrings(needles.toList()) } }
                        .mapNotNull { hit ->
                            runCatching { hit.getMethodInstance(scope.classLoader) }.getOrNull()
                        }
                }.getOrDefault(emptyList())

            // 权益存储键与 prefs 文件名都落在权益类的静态初始化里。同名串也被旧版迁移用的
            // 那个 prefs 包装引用，所以还要按形状认领 —— 只有权益类同时具备
            // 「无参返回 boolean」和「无参返回自有对象」两个静态方法。
            val gateClass = (classesUsing(KEY_WALLET) + classesUsing(PREFS_IAP) + classesUsing(KEY_INIT))
                .distinct()
                .firstOrNull { looksLikeGate(it) }

            // 免费增值开关只被权益三态那个类读。
            val tierClass = (classesUsing(FLAG_FREEMIUM) + classesUsing(FLAG_REWARD_INTERVAL))
                .distinct()
                .firstOrNull { looksLikeTier(it) }

            // 两个风控埋点：事件名是字面量，各自只有一处调用，都是普通方法。
            val analytics = (methodsUsing(EVENT_TAMPER) + methodsUsing(EVENT_RISK)).distinct()

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            scope.log.d("dex 扫描用时 ${elapsed}ms，权益类=${gateClass?.name ?: "-"} 三态类=${tierClass?.name ?: "-"}")

            val refs = fromClasses(gateClass, tierClass, analytics, null, scope)
            if (refs.isEmpty) scope.log.w("dex 里找不到权益体系的任何落点（${elapsed}ms）")
            refs
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 从已知的两个类回填全部方法，缓存与扫描两条路共用。
     *
     * 权益类（`gateClass`）里要的三样：
     *
     * * `()Z` —— 判定。这个类里只有它一个无参返回 boolean 的静态方法。
     * * `()L<非 String 对象>;` —— 取权益记录。返回类型顺带就是权益对象的类。
     * * 类型不是自己的那个静态字段 —— 权益存储的实例，[Entitlement] 靠它清缓存。
     */
    private fun fromClasses(
        gateClass: Class<*>?,
        tierClass: Class<*>?,
        analytics: List<Method>,
        billingFailure: Method?,
        scope: HookScope?,
    ): Refs {
        // 判定：本类里唯一一个无参返回 boolean 的静态方法。
        val isPremium = gateClass?.staticMethods {
            emptyParameters()
            returnType = BOOLEAN
        }?.singleOrNull()

        // 取权益记录：无参、静态、返回一个应用自己的对象类型（不是 String/基本类型）。
        val subscription = gateClass?.staticMethods {
            emptyParameters()
            returnTypeCondition = {
                !it.isPrimitive && it != String::class.java && it != Void.TYPE
            }
        }?.singleOrNull()
        val subscriptionClass = subscription?.returnType

        // 权益三态：无参、静态、返回枚举。
        val tier = tierClass?.staticMethods {
            emptyParameters()
            returnTypeCondition = { it.isEnum }
        }?.singleOrNull()
        val tierPaid = tier?.returnType?.let { enumType ->
            // 枚举的 clinit 只造常量、不碰 Context，这里触发它是安全的。
            val constants = runCatching { enumType.enumConstants }.getOrNull().orEmpty()
            constants.firstOrNull { (it as? Enum<*>)?.name == TIER_PAID }
                ?: constants.firstOrNull()   // PAID 是 ordinal 0
        }

        // 权益存储实例：权益类的静态字段里，类型不是它自己的那一个。
        // 只认字段，**不读值** —— 读值会触发 gateClass 的 clinit，见 Refs.walletHolderField。
        val holderField = gateClass?.resolve()
            ?.optional(silent = true)
            ?.field {
                modifiers { Modifiers.STATIC in it }
                typeCondition = { !it.isPrimitive && it != gateClass }
            }?.firstOrNull()?.self

        // 存储读取：从字段的**声明类型**往上找。`superclass()` 让 KavaRef 自己走完
        // 继承链 —— 这个方法是定义在存储基类上的，子类只覆盖了编解码那两个。
        // 同形状的还有一个抽象的「默认值工厂」，用 ABSTRACT 排掉。
        val walletRead = holderField?.type?.resolve()
            ?.optional(silent = true)
            ?.method {
                superclass()
                emptyParameters()
                returnType = Any::class.java
                isSyntheticNot = true
                isBridgeNot = true
                modifiers { Modifiers.ABSTRACT !in it }
            }?.firstOrNull()?.self

        // 反序列化后的缓存字段：和读取方法同一个类里，类型为 Object 的实例字段。
        val walletCache = walletRead?.declaringClass?.resolve()
            ?.optional(silent = true)
            ?.field {
                modifiers { Modifiers.STATIC !in it }
                type = Any::class.java
            }?.firstOrNull()?.self

        if (scope != null && gateClass != null && subscription == null) {
            scope.log.d("在 ${gateClass.name} 里没挑出取权益记录的方法，形状可能变了")
        }

        return Refs(
            isPremium = isPremium,
            subscription = subscription,
            subscriptionClass = subscriptionClass,
            tier = tier,
            tierPaid = tierPaid,
            walletRead = walletRead,
            walletHolderField = holderField,
            walletCache = walletCache,
            analytics = analytics,
            billingFailure = billingFailure,
        )
    }

    private fun empty() = Refs(null, null, null, null, null, null, null, null, emptyList(), null)

    /**
     * 权益类的形状：一个 `()Z` 判定，加一个返回自有对象的 `()L?;` 取记录。
     *
     * 那两个特征串还被旧版迁移用的 prefs 包装引用，光靠串会认错人；
     * 这两个静态方法的组合在全 app 里只此一家。
     */
    private fun looksLikeGate(type: Class<*>): Boolean {
        val hasVerdict = type.staticMethods {
            emptyParameters()
            returnType = BOOLEAN
        }.isNotEmpty()
        val hasRecord = type.staticMethods {
            emptyParameters()
            returnTypeCondition = {
                !it.isPrimitive && it != String::class.java && it != Void.TYPE
            }
        }.isNotEmpty()
        return hasVerdict && hasRecord
    }

    /** 三态类的形状：一个无参返回枚举的静态方法。 */
    private fun looksLikeTier(type: Class<*>): Boolean =
        type.staticMethods {
            emptyParameters()
            returnTypeCondition = { it.isEnum }
        }.isNotEmpty()

    // --- 小工具 -------------------------------------------------------------

    /**
     * 本类里符合条件的静态方法。
     *
     * 一律排掉 synthetic 与 bridge：Kotlin 会为被 lambda 捕获的私有成员生成
     * `public static synthetic` 访问器，形状和真正的目标一模一样，
     * 不排掉的话 `singleOrNull()` 会因为「撞见两个」而返回 null。
     *
     * `optional(silent = true)` 让查不到变成空列表 —— 这里跑在目标 Application 的
     * 调用栈上，抛异常等于让应用起不来。
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun Class<*>.staticMethods(
        crossinline condition: MethodCondition<Any>.() -> Unit,
    ): List<Method> = (this as Class<Any>).resolve()
        .optional(silent = true)
        .method {
            modifiers { Modifiers.STATIC in it }
            isSyntheticNot = true
            isBridgeNot = true
            condition()
        }
        .map { it.self }
}
