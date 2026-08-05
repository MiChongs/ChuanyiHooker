package com.chuanyi.hooker.hookers.instashot

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import com.chuanyi.hooker.hookers.instashot.InShot.cerCheckIn
import com.chuanyi.hooker.hookers.instashot.InShot.factoryIn
import com.chuanyi.hooker.hookers.instashot.InShot.isContextPredicate
import com.chuanyi.hooker.hookers.instashot.InShot.isInstanceContextPredicate
import com.chuanyi.hooker.hookers.instashot.InShot.isNoArgPredicate
import com.chuanyi.hooker.hookers.instashot.InShot.isPurchaseApply
import com.chuanyi.hooker.hookers.instashot.InShot.isUnlockWriter
import com.chuanyi.hooker.hookers.instashot.InShot.writerIn
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 按**落盘键名**定位授权链路上的方法，不认 R8 重命名后的类名。
 *
 * 键名是应用自己的存档格式：改一次，全体老用户的购买状态就读不出来了。所以它比
 * 任何类名都稳 —— 这也是这里不给「写死名字」留退路的原因，写死一个每次构建都会变的
 * 字母只会带来误伤。
 *
 * 三条路依次试：
 *
 * 1. `user_manager_class` 设置项 —— 应急用，扫描失灵时不重新出包就能纠正
 * 2. 上次扫描的结果，按 versionCode 缓存在**目标自己**的数据目录里
 * 3. DexKit 扫描
 *
 * 一次 `DexKitBridge` 会话里发 6 条查询：全包 38 MB 的 dex，开销主要在打开和建索引，
 * 多发几条查询比多开几次会话便宜得多。查询只给锚点串（[StringMatchType.Equals]），
 * 形状分类放到 Kotlin 里做 —— 省得为「无参」「第三个参数是 List」这种条件跟 DSL 较劲，
 * 也让每一步筛选在日志里看得见。
 */
internal object InShotDex {

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "instashot.version"

    /**
     * 授权链路上要用到的全部引用。任一为 null 表示没定位到，
     * 对应功能自己降级，不连累别的功能。
     */
    class Refs(
        /** `UnlockPreferences.isSubscribePro(Context)` —— 级联总闸 */
        val isSubscribePro: Method? = null,
        /** `UnlockPreferences.setUnlocked(Context, String, boolean)` */
        val setUnlocked: Method? = null,
        /** `UpdateBilling.applyPurchases(Context, BillingResult, List, Runnable)` */
        val applyPurchases: Method? = null,
        /** `UpdateBilling.shouldShowProUnavailable(Context)` */
        val proUnavailable: Method? = null,
        /** `UserManager.isPro()` */
        val userIsPro: Method? = null,
        /** `UserManager.putFlag(String, boolean)` —— 直写 `iab` 档 */
        val putFlag: Method? = null,
        /** `UserManager.get(Context)` 单例工厂 */
        val userManagerOf: Method? = null,
        /** `IAPBindHelper.isPro(Context)` */
        val iapIsPro: Method? = null,
        /** `IAPBindHelper.isBindSupported(Context)` */
        val iapBindSupported: Method? = null,
        /** `CerChecker.check(Context)` —— 签名完整性，卡住 12 个 AI 原生能力 */
        val cerCheck: Method? = null,
    ) {
        val userManagerClass: Class<*>? get() = (userIsPro ?: putFlag)?.declaringClass

        fun describe(): String = buildString {
            fun line(label: String, m: Method?) {
                append("\n  ").append(label).append(' ')
                append(m?.let { "${it.declaringClass.name}.${it.name}" } ?: "未定位")
            }
            line("总闸 UnlockPreferences.isSubscribePro", isSubscribePro)
            line("写入 UnlockPreferences.setUnlocked   ", setUnlocked)
            line("结算 UpdateBilling.applyPurchases    ", applyPurchases)
            line("弹窗 UpdateBilling.proUnavailable    ", proUnavailable)
            line("判定 UserManager.isPro               ", userIsPro)
            line("写入 UserManager.putFlag             ", putFlag)
            line("工厂 UserManager.get                 ", userManagerOf)
            line("账号 IAPBindHelper.isPro             ", iapIsPro)
            line("绑定 IAPBindHelper.isBindSupported   ", iapBindSupported)
            line("签名 CerChecker.check                ", cerCheck)
        }
    }

    fun resolve(scope: HookScope): Refs {
        val cached = cached(scope)
        if (cached != null) {
            scope.log.d("授权链路命中缓存${cached.describe()}")
            return cached
        }
        val scanned = scan(scope)
        remember(scope, scanned)
        scope.log.i("授权链路定位结果${scanned.describe()}")
        return scanned
    }

    // -----------------------------------------------------------------------

    /**
     * 扫一次几百毫秒，而它发生在应用启动路径上 —— 结果按 versionCode 缓存。
     * 缓存写在**目标自己**的数据目录：模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cached(scope: HookScope): Refs? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null

        fun load(slot: String, shape: (Method) -> Boolean): Method? {
            val fqn = prefs.getString(slot, null)?.takeIf { it.isNotBlank() } ?: return null
            val owner = fqn.substringBeforeLast('#', "").takeIf { it.isNotEmpty() } ?: return null
            val name = fqn.substringAfterLast('#', "").takeIf { it.isNotEmpty() } ?: return null
            // 覆盖安装但 versionCode 没变时名字可能已经对不上，形状再验一遍。
            return scope.classOrNull(owner)?.declaredMethods
                ?.firstOrNull { it.name == name && shape(it) }
        }

        val userIsPro = load("isPro") { it.isNoArgPredicate() }
        // 总闸丢了就当缓存整体失效 —— 其余的都靠它所在的那次扫描一起得出。
        val isSubscribePro = load("subscribePro") { it.isContextPredicate() } ?: return null

        return Refs(
            isSubscribePro = isSubscribePro,
            setUnlocked = load("setUnlocked") { it.isUnlockWriter() },
            applyPurchases = load("applyPurchases") { it.isPurchaseApply() },
            proUnavailable = load("proUnavailable") { it.isInstanceContextPredicate() },
            userIsPro = userIsPro,
            putFlag = userIsPro?.declaringClass?.let(::writerIn),
            userManagerOf = userIsPro?.declaringClass?.let(::factoryIn),
            iapIsPro = load("iapIsPro") { it.isContextPredicate() },
            iapBindSupported = load("iapBind") { it.isContextPredicate() },
            cerCheck = scope.classOrNull(InShot.CER_CHECKER)?.let(::cerCheckIn),
        )
    }

    private fun remember(scope: HookScope, refs: Refs) {
        val prefs = cache(scope) ?: return
        fun Method?.slot(): String? = this?.let { "${it.declaringClass.name}#${it.name}" }
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString("subscribePro", refs.isSubscribePro.slot())
                .putString("setUnlocked", refs.setUnlocked.slot())
                .putString("applyPurchases", refs.applyPurchases.slot())
                .putString("proUnavailable", refs.proUnavailable.slot())
                .putString("isPro", refs.userIsPro.slot())
                .putString("iapIsPro", refs.iapIsPro.slot())
                .putString("iapBind", refs.iapBindSupported.slot())
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    // -----------------------------------------------------------------------

    private fun scan(scope: HookScope): Refs {
        val cerCheck = scope.classOrNull(InShot.CER_CHECKER)?.let(::cerCheckIn)

        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return Refs(cerCheck = cerCheck)
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而在 LSPosed
        // 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，等到
        // 第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块
        // 自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载一次，DexKit
        // 自己那次随后就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return Refs(cerCheck = cerCheck)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return Refs(cerCheck = cerCheck)

        return bridge.use { dex ->
            /** 按锚点串精确查，命中的方法反射出来交给调用方按形状分。 */
            fun byAnchor(anchor: String, vararg packages: String): List<Method> {
                val hits = runCatching {
                    dex.findMethod {
                        if (packages.isNotEmpty()) searchPackages(*packages)
                        matcher { addUsingString(anchor, StringMatchType.Equals) }
                    }
                }.onFailure { scope.log.w("扫 '$anchor' 失败：${it.message}") }.getOrNull().orEmpty()

                if (hits.isEmpty()) scope.log.w("dex 里找不到引用 '$anchor' 的方法")
                return hits.mapNotNull { hit ->
                    runCatching { hit.getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.d("${hit.descriptor} 反射不出来：${it.message}") }
                        .getOrNull()
                }
            }

            // 一次查询同时拿到「读总闸」和「写总闸」—— 全包只有这两处碰 SubscribePro。
            val subscribePro = byAnchor(InShot.KEY_SUBSCRIBE_PRO, InShot.BILLING_PACKAGE)
            val unlocked = byAnchor(InShot.KEY_UNLOCKED_PREFIX, InShot.BILLING_PACKAGE)
            val unavailable = byAnchor(InShot.ANCHOR_PRO_UNAVAILABLE, InShot.BILLING_PACKAGE)

            // UserManager 先认类，再在类内按形状挑成员。IAPBindHelper 的包被 R8
            // 重命名过（`Jg`），不能收窄搜索范围，只能靠 Equals + 签名。
            val userManager = byAnchor(InShot.ANCHOR_USER_MANAGER, InShot.BILLING_PACKAGE)
                .firstOrNull { it.isNoArgPredicate() }?.declaringClass
                ?: configuredUserManager(scope)
            val userIsPro = userManager?.let { owner ->
                byAnchor(InShot.ANCHOR_IS_PRO)
                    .firstOrNull { it.declaringClass == owner && it.isNoArgPredicate() }
            }
            val iap = byAnchor(InShot.ANCHOR_IAP_IS_PRO).filter { it.isContextPredicate() }
            val bind = byAnchor(InShot.ANCHOR_BIND_SUPPORTED).filter { it.isContextPredicate() }

            scope.log.d("dex 扫描用时 ${SystemClock.elapsedRealtime() - startedAt}ms")

            Refs(
                isSubscribePro = subscribePro.firstOrNull { it.isContextPredicate() },
                setUnlocked = unlocked.firstOrNull { it.isUnlockWriter() },
                applyPurchases = subscribePro.firstOrNull { it.isPurchaseApply() },
                proUnavailable = unavailable.firstOrNull { it.isInstanceContextPredicate() },
                userIsPro = userIsPro,
                putFlag = userManager?.let(::writerIn),
                userManagerOf = userManager?.let(::factoryIn),
                // 两个候选都是 `static (Context)Z`，靠各自的锚点串区分，不会互相串。
                iapIsPro = iap.firstOrNull(),
                iapBindSupported = bind.firstOrNull(),
                cerCheck = cerCheck,
            )
        }
    }

    private fun configuredUserManager(scope: HookScope): Class<*>? =
        scope.string(InShot.KEY_USER_MANAGER_CLASS)
            ?.takeIf { it.isNotBlank() }
            ?.let { name ->
                scope.classOrNull(name)
                    ?.also { scope.log.i("UserManager 取自设置项：$name") }
                    ?: run { scope.log.w("设置项里的 $name 不存在"); null }
            }
}
