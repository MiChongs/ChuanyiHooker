package com.chuanyi.hooker.hookers.secretshoot

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodDataList
import java.lang.reflect.Method

/**
 * 定位那些被 R8 重命名的落点。
 *
 * 目标里能按名字锚的只有 `com.weixikeji.secretshoot.bean.*` 和
 * `com.weixikeji.secretshoot.preferences` 两个包 —— 剩下的（权益中枢在 4.3.8 里叫
 * `ij.f`）名字是 R8 生成的，每次构建都可能变。写死等于每次应用更新都要重开一次 jadx。
 *
 * 改按**特征串**找。挑的都是「改了功能就不成立」的串，不是随手拿的字面量：
 *
 * - 权益中枢：`month` / `quarter` / `year` / `permanent` —— 商品号里必须出现的约定，
 *   客户端拿它把 productId 翻成会员名称，Play 后台的商品配置得跟它对上
 * - 离线宽限：`fetch_user_info_failed_timestamp` / `fetch_user_info_failed_count` ——
 *   配置键，改了名旧版本的本地数据就读不出来
 *
 * 每个落点四条路依次试，前一条不成才走下一条：
 *
 * 1. 设置项覆盖 —— 应急用，不重新出包就能纠正一次误判
 * 2. 上次扫描的结果，按 versionCode 缓存在**目标自己**的数据目录里
 * 3. DexKit 扫 dex
 * 4. 写死的候选名 —— DexKit 装不起来（本机 ABI 没有 `libdexkit.so`）时还能工作
 *
 * 第 4 条只有权益中枢有：另外两处的判据本身就是「函数体里用了哪个串」，没有等价的
 * 名字可退，扫不到就是这一项不可用，调用方记一行日志跳过。
 */
internal object SecretShootDex {

    /** 4.3.8 里权益中枢的类名，作为最后的退路。 */
    private val PINNED_VAULT = arrayOf("ij.f")

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "secretshoot.version"
    private const val KEY_VAULT = "secretshoot.vault"

    /** 覆盖权益中枢的类名，不重新出包就能纠正。 */
    const val KEY_VAULT_CLASS = "vault_class"

    // -----------------------------------------------------------------------

    /**
     * 权益中枢的类。
     *
     * 锚点是 `String j(Context)` —— 把商品号翻成「月度/季度/年度/永久会员」的那个方法，
     * 函数体里四个周期片段全都在。全包里只有它同时用到这四个串。
     *
     * 拿到类之后**一定要再验形状**（有没有那对 `GooglePayBean` 存取方法），
     * 缓存和设置项这两条路尤其需要 —— 它们给的是上一次的答案，应用可能已经变了。
     */
    fun vault(scope: HookScope, bean: Class<*>): Class<*>? {
        configuredVault(scope, bean)?.let {
            scope.log.i("权益中枢来自设置项：${it.name}")
            return it
        }
        cachedVault(scope, bean)?.let {
            scope.log.d("权益中枢命中缓存：${it.name}")
            return it
        }
        scanVault(scope, bean)?.let {
            rememberVault(scope, it)
            scope.log.i("权益中枢扫描到：${it.name}")
            return it
        }
        return pinnedVault(scope, bean)?.also {
            scope.log.w("扫描没结果，退回写死的类名：${it.name}")
        }
    }

    /**
     * 离线宽限窗口的判定方法（4.3.8 里是 `preferences.c.B0()`）。
     *
     * 原样是「距上次成功同步不到 5 天 **或** 还剩启动次数」。它是缓存凭据的总开关：
     * 返回 false 时上层直接拿一个空凭据，等于会员立刻失效。
     *
     * 判据：无参、返回 boolean、函数体里同时用到那两个配置键 —— 同一个类里另外两个
     * 用到这两个键的方法（清零 / 置位）都是 void，签名就分开了。
     */
    fun graceGate(scope: HookScope): Method? = scan(scope, "离线宽限判定") { dex ->
        dex.findMethod {
            matcher {
                returnType = "boolean"
                paramCount = 0
                usingStrings(SecretShoot.GRACE_TIMESTAMP_KEY, SecretShoot.GRACE_COUNT_KEY)
            }
        }
    }

    /**
     * 登录态判定（4.3.8 里是权益中枢的 `q()`）。
     *
     * 中枢上「无参返回 boolean」的方法有十个，光看签名分不出来。这一个的特征是
     * 它要判 token 空不空，于是函数体里有一次 `TextUtils.isEmpty` 调用 ——
     * 另外九个（会员有效 / 试用中 / 激励时长内 / …）没有一个碰 `TextUtils`。
     */
    fun loginState(scope: HookScope, vault: Class<*>): Method? =
        scan(scope, "登录态判定") { dex ->
            dex.findMethod {
                matcher {
                    declaredClass(vault.name)
                    returnType = "boolean"
                    paramCount = 0
                    addInvoke {
                        name = "isEmpty"
                        declaredClass("android.text.TextUtils")
                    }
                }
            }
        }

    // -----------------------------------------------------------------------

    private fun configuredVault(scope: HookScope, bean: Class<*>): Class<*>? =
        scope.string(KEY_VAULT_CLASS)
            ?.takeIf { it.isNotBlank() }
            ?.let { scope.classOrNull(it) }
            ?.takeIf { verify(it, bean) }

    private fun pinnedVault(scope: HookScope, bean: Class<*>): Class<*>? =
        PINNED_VAULT.firstNotNullOfOrNull { scope.classOrNull(it) }?.takeIf { verify(it, bean) }

    /**
     * 扫一次要几百毫秒，而它发生在应用启动路径上 —— 结果按 versionCode 缓存。
     *
     * 缓存写在**目标自己**的数据目录：模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cachedVault(scope: HookScope, bean: Class<*>): Class<*>? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        return prefs.getString(KEY_VAULT, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { scope.classOrNull(it) }
            ?.takeIf { verify(it, bean) }
    }

    private fun rememberVault(scope: HookScope, vault: Class<*>) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(KEY_VAULT, vault.name)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    private fun scanVault(scope: HookScope, bean: Class<*>): Class<*>? =
        scan(scope, "权益中枢") { dex ->
            dex.findMethod {
                matcher {
                    returnType = "java.lang.String"
                    paramTypes("android.content.Context")
                    usingStrings(*SecretShoot.PRODUCT_KINDS)
                }
            }
        }?.declaringClass?.takeIf {
            verify(it, bean).also { ok ->
                if (!ok) scope.log.w("扫到的类 ${it.name} 形状不对，弃用")
            }
        }

    /** 形状复核：这个类身上必须有那对 `GooglePayBean` 的读 / 写方法。 */
    private fun verify(candidate: Class<*>, bean: Class<*>): Boolean =
        SecretShoot.credentialGetter(candidate, bean) != null &&
            SecretShoot.credentialWriter(candidate, bean) != null

    // -----------------------------------------------------------------------

    /**
     * 扫一次 dex。
     *
     * DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而在 LSPosed
     * 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，等到
     * 第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块
     * 自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载一次，
     * DexKit 自己那次随后就成了空操作。
     *
     * 全程失败都返回 null —— 调用方要么还有别的路可走，要么把这一项跳过。
     */
    private fun scan(
        scope: HookScope,
        label: String,
        query: (DexKitBridge) -> MethodDataList,
    ): Method? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("$label：拿不到 APK 路径，跳过 dex 扫描")
            return null
        }
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("$label：libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("$label：DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val hits = runCatching { query(dex) }
                .onFailure { scope.log.w("$label：dex 扫描失败：${it.message}") }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (hits.isNullOrEmpty()) {
                scope.log.w("$label：dex 里没找到（${elapsed}ms）")
                return@use null
            }
            if (hits.size > 1) {
                scope.log.w("$label：命中 ${hits.size} 个，取第一个：${hits.joinToString { it.descriptor }}")
            }
            runCatching { hits.first().getMethodInstance(scope.classLoader) }
                .onFailure { scope.log.w("$label：命中的方法反射不出来：${it.message}") }
                .getOrNull()
                ?.also { scope.log.i("$label：命中 ${it.declaringClass.name}.${it.name}（${elapsed}ms）") }
        }
    }
}
