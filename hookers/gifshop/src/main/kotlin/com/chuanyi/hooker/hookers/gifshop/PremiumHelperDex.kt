package com.chuanyi.hooker.hookers.gifshop

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * 定位 PremiumHelper 的权益判定函数与写入函数。
 *
 * 这两个方法是 `com.zipoapps.premiumhelper.d`（`Preferences`）上的
 *
 * ```java
 * public final boolean z() { return sharedPreferences.getBoolean("has_active_purchase", false); }
 * public final void    W(boolean v) { …putBoolean("has_active_purchase", v).apply(); }
 * ```
 *
 * 类名的**包**保留了下来（R8 只重命名了类的短名），但方法名 `z` / `W` 是重命名结果，
 * 下一版就可能变成别的字母，写死等于每次更新都要重开一次 jadx。类里又有九个
 * 无参返回布尔的方法（`A` / `F` / `G` / `H` / `I` / `J` / `K` / `i` / `z`），
 * 光按形状分不出来。
 *
 * 能分出来的是**它引用的那个键名**。[PremiumHelper.KEY_ENTITLEMENT] 是用户已购状态的
 * 落盘键，改一次全体老用户的购买就丢了，所以它比任何类名都稳。全包引用这个串的只有
 * 上面两个方法，扫出来后按返回类型和参数一分，读写各归各位。
 *
 * 三条路依次试：
 *
 * 1. `verdict_class` / `verdict_method` 设置项 —— 应急用，不重新出包就能纠正
 * 2. 上次扫描的结果，按 versionCode 缓存在**目标自己**的数据目录里
 * 3. DexKit 扫描
 *
 * 没有第四条「写死名字」的退路，因为写死一个会漂的字母只会带来误伤。三条都不成时，
 * [GifShopHooker] 退到存储层拦截（`SharedPreferencesImpl.getBoolean`），
 * 那一层完全不需要目标的名字。
 */
internal object PremiumHelperDex {

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "gifshop.version"
    private const val KEY_GETTER_CLASS = "gifshop.verdict.class"
    private const val KEY_GETTER_METHOD = "gifshop.verdict.method"
    private const val KEY_SETTER_METHOD = "gifshop.writer.method"

    private val BOOLEAN = Boolean::class.javaPrimitiveType!!

    /** 判定函数（读）与写入函数（写）。任一为 null 表示没找着。 */
    class Refs(val verdict: Method?, val writer: Method?) {
        val isEmpty: Boolean get() = verdict == null && writer == null
    }

    fun resolve(scope: HookScope): Refs {
        configured(scope)?.let {
            scope.log.i("权益函数来自设置项：${describe(it)}")
            return it
        }
        cached(scope)?.let {
            scope.log.d("权益函数命中缓存：${describe(it)}")
            return it
        }
        val scanned = scan(scope)
        if (!scanned.isEmpty) {
            remember(scope, scanned)
            scope.log.i("权益函数扫描到：${describe(scanned)}")
        }
        return scanned
    }

    private fun describe(refs: Refs): String {
        val owner = (refs.verdict ?: refs.writer)?.declaringClass?.name ?: "?"
        return "$owner{读=${refs.verdict?.name ?: "-"}, 写=${refs.writer?.name ?: "-"}}"
    }

    // -----------------------------------------------------------------------

    private fun configured(scope: HookScope): Refs? {
        val className = scope.string(GifShopHooker.KEY_VERDICT_CLASS)?.takeIf { it.isNotBlank() } ?: return null
        val clazz = scope.classOrNull(className) ?: run {
            scope.log.w("设置项里的 $className 不存在")
            return null
        }
        val getter = scope.string(GifShopHooker.KEY_VERDICT_METHOD)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { clazz.getDeclaredMethod(it) }.getOrNull() }
            ?.takeIf { it.returnType == BOOLEAN }
        return if (getter == null) null else Refs(getter, null)
    }

    /**
     * 扫一次要几百毫秒，而它发生在应用启动路径上 —— 所以结果按 versionCode 缓存。
     * 缓存写在**目标自己**的数据目录：模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cached(scope: HookScope): Refs? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val className = prefs.getString(KEY_GETTER_CLASS, null)?.takeIf { it.isNotBlank() } ?: return null
        val clazz = scope.classOrNull(className) ?: return null

        val getter = prefs.getString(KEY_GETTER_METHOD, null)
            ?.let { runCatching { clazz.getDeclaredMethod(it) }.getOrNull() }
            ?.takeIf { it.returnType == BOOLEAN }
        val setter = prefs.getString(KEY_SETTER_METHOD, null)
            ?.let { runCatching { clazz.getDeclaredMethod(it, BOOLEAN) }.getOrNull() }
            ?.takeIf { it.returnType == Void.TYPE }

        // 覆盖安装但 versionCode 没变时名字可能已经对不上，当没缓存重扫。
        return if (getter == null) null else Refs(getter, setter)
    }

    private fun remember(scope: HookScope, refs: Refs) {
        val prefs = cache(scope) ?: return
        val owner = (refs.verdict ?: refs.writer)?.declaringClass?.name ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(KEY_GETTER_CLASS, owner)
                .putString(KEY_GETTER_METHOD, refs.verdict?.name)
                .putString(KEY_SETTER_METHOD, refs.writer?.name)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /**
     * 一次扫描同时拿到读和写。
     *
     * 只按特征串查，形状分类放在 Kotlin 里做 —— 比发两次带不同 matcher 的查询快，
     * 也省得为「无参」这种条件跟 DSL 较劲。搜索范围收在
     * [PremiumHelper.PACKAGE]：整包五个 dex 合计 38 MB，限定包之后只剩 SDK 那一小块。
     */
    private fun scan(scope: HookScope): Refs {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return Refs(null, null)
        }

        val startedAt = SystemClock.elapsedRealtime()
        // 打不开就算了（本机 ABI 没有 libdexkit.so 时会抛 UnsatisfiedLinkError）——
        // 调用方还有存储层那条不依赖名字的路可退。
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return Refs(null, null)

        return bridge.use { dex ->
            val hits = runCatching {
                dex.findMethod {
                    searchPackages(PremiumHelper.PACKAGE)
                    matcher { usingStrings(PremiumHelper.KEY_ENTITLEMENT) }
                }
            }.onFailure { scope.log.w("dex 扫描失败：${it.message}") }.getOrNull()

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (hits.isNullOrEmpty()) {
                scope.log.w("${PremiumHelper.PACKAGE} 里找不到引用 '${PremiumHelper.KEY_ENTITLEMENT}' 的方法（${elapsed}ms）")
                return@use Refs(null, null)
            }

            val methods = hits.mapNotNull { hit ->
                runCatching { hit.getMethodInstance(scope.classLoader) }
                    .onFailure { scope.log.d("${hit.descriptor} 反射不出来：${it.message}") }
                    .getOrNull()
            }
            scope.log.d("dex 扫描用时 ${elapsed}ms，命中 ${methods.size} 个方法")

            Refs(
                verdict = methods.firstOrNull { it.returnType == BOOLEAN && it.parameterCount == 0 },
                writer = methods.firstOrNull {
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(BOOLEAN))
                },
            )
        }
    }
}
