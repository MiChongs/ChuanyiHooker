package com.chuanyi.hooker.hookers.osmin

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 按特征串在 dex 里找授权链路上的两个静态方法。
 *
 * 目标的业务代码整个被 R8 重命名进默认包，还合并成了几个上万行的巨型类
 * （v0.3.1 里这两个方法是 `zq0.i2` 和 `zq0.S2`）。类名和方法名每次构建都会变，
 * 写死等于每次更新都要重开一次 jadx，所以改按**方法体里的字符串**找。
 *
 * 挑的两组锚点都是「改了就会坏掉」的东西，比任何名字都稳：
 *
 * * [tierGate] —— 三个等级名。它们是服务端下发的 `tier` 字段的取值，
 *   客户端改一个字就再也认不出自己的授权状态。
 * * [entitlementWriter] —— 配置文件名 `module_settings`。被注入的进程按这个名字
 *   去要远端副本，两端必须一致。
 *
 * 命中之后还要复核。[tierGate] 能做**行为复核** —— 直接拿三个等级名调一遍看
 * 返回值对不对，这比任何形状判据都硬：它证明的不是「长得像」，而是「就是它」。
 *
 * 扫一遍在这个 3.3 MB 的单 dex 上是几十毫秒，但它落在应用启动路径上，
 * 所以结果按 versionCode 缓存在**目标自己**的数据目录 —— 模块的远端配置从被 hook
 * 的进程只能读，写不进去。
 */
internal object OsMinDex {

    private const val CACHE_FILE = "chuanyi_hooker_osmin"
    private const val KEY_VERSION = "version"

    /**
     * 「这个等级算不算已授权」。
     *
     * 应用进程里所有付费判定的唯一入口：界面拿它决定付费项是可点还是显示
     * 「需授权」，同步流程拿它的结果去写 [OsMin.KEY_AUTH_ACTIVE]。
     * 让它恒为 true，这两件事就一起解决了。
     */
    fun tierGate(scope: HookScope): Method? = locate(
        scope = scope,
        key = "tier_gate",
        what = "授权等级判定",
        verify = { verifyTierGate(it) },
    ) { dex ->
        dex.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramTypes("java.lang.String")
                usingStrings(OsMin.TIERS_AUTHORIZED)
            }
        }.mapNotNull { runCatching { it.getMethodInstance(scope.classLoader) }.getOrNull() }
    }

    /**
     * 「把某个开关写进两份配置」。
     *
     * 本地那份和框架托管的远端那份一起写 —— 后者正是被注入的进程读的东西。
     * 目标用它写各种开关，[OsMin.KEY_AUTH_ACTIVE] 只是其中一个 key，
     * 所以 hook 它的时候必须按 key 过滤，不能一律改。
     *
     * 这个没法行为复核（调一次就落盘了），靠签名兜底：
     * 三个参数的静态 void，且方法体里出现 `module_settings` —— 全包唯一。
     */
    fun entitlementWriter(scope: HookScope): Method? = locate(
        scope = scope,
        key = "entitlement_writer",
        what = "权益写入",
        verify = { verifyWriter(it) },
    ) { dex ->
        dex.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "void"
                paramTypes("android.content.Context", "java.lang.String", "boolean")
                usingStrings(listOf(OsMin.PREFS_SETTINGS))
            }
        }.mapNotNull { runCatching { it.getMethodInstance(scope.classLoader) }.getOrNull() }
    }

    // -----------------------------------------------------------------------

    /**
     * 三个等级名逐个调进去看结果对不对，再补一个反例。
     *
     * `usingStrings` 是**包含**匹配，理论上别的方法也能引用同一批串；而这一步
     * 让「像」变成「是」—— 判定函数的语义就写在返回值里，冒名顶替的过不了。
     */
    private fun verifyTierGate(method: Method): Boolean = runCatching {
        method.isAccessible = true
        OsMin.TIERS_AUTHORIZED.all { method.invoke(null, it) == true } &&
            method.invoke(null, OsMin.TIER_NONE) == false
    }.getOrDefault(false)

    private fun verifyWriter(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 3 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == String::class.java &&
            method.parameterTypes[2] == Boolean::class.javaPrimitiveType

    /**
     * 缓存 → 扫描 两条路。
     *
     * 缓存里存的是「类名#方法名」，回来时按名字反射再走一遍 [verify] ——
     * 名字对不上（原地覆盖安装但 versionCode 没变）就当没缓存，重新扫。
     */
    private fun locate(
        scope: HookScope,
        key: String,
        what: String,
        verify: (Method) -> Boolean,
        scan: (DexKitBridge) -> List<Method>,
    ): Method? {
        cached(scope, key)?.takeIf { runCatching { verify(it) }.getOrDefault(false) }?.let {
            scope.log.d("$what 命中缓存：${it.declaringClass.name}.${it.name}")
            return it
        }

        val hit = withDexKit(scope, what) { dex ->
            val hits = scan(dex)
            if (hits.size > 1) {
                scope.log.d("$what 特征串命中 ${hits.size} 个，逐个复核")
            }
            hits.firstOrNull { runCatching { verify(it) }.getOrDefault(false) }
        } ?: return null

        hit.isAccessible = true
        remember(scope, key, hit)
        scope.log.i("$what 已定位：${hit.declaringClass.name}.${hit.name}")
        return hit
    }

    /**
     * DexKit 的原生库。
     *
     * 2.x 起 `DexKitBridge` **不再自己 `loadLibrary`**，得使用方显式加载。漏掉这一步
     * 的表现不是「找不到 so」，而是调用时报 `No implementation found for …
     * nativeInitDexKit`，看着像版本不匹配。
     *
     * 第二层麻烦在被注入的进程里：`System.loadLibrary` 按**调用类的 classloader**
     * 找库，而这里的调用类由框架的模块 classloader 加载，它未必带着模块 APK 的原生库
     * 路径。所以直接加载失败时再问一次 classloader 要那个库的绝对路径
     * （`findLibrary` 是 protected，只能反射）。
     */
    private val nativeReady: Boolean by lazy {
        runCatching {
            System.loadLibrary("dexkit")
            return@lazy true
        }
        runCatching {
            val loader = OsMinDex::class.java.classLoader ?: return@runCatching false
            val find = ClassLoader::class.java
                .getDeclaredMethod("findLibrary", String::class.java)
                .apply { isAccessible = true }
            val path = find.invoke(loader, "dexkit") as? String ?: return@runCatching false
            System.load(path)
            true
        }.getOrDefault(false)
    }

    private fun <T> withDexKit(scope: HookScope, what: String, block: (DexKitBridge) -> T?): T? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过扫描 $what")
            return null
        }
        if (!nativeReady) {
            scope.log.w("libdexkit.so 装不上，跳过扫描 $what")
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val result = runCatching { block(dex) }
                .onFailure { scope.log.w("扫描 $what 失败：${it.message}") }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (result == null) {
                scope.log.w("dex 里找不到 $what（${elapsed}ms）")
            } else {
                scope.log.d("扫描 $what 用时 ${elapsed}ms")
            }
            result
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let {
            runCatching { it.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    private fun cached(scope: HookScope, key: String): Method? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val (owner, name) = prefs.getString(key, null)
            ?.split('#')
            ?.takeIf { it.size == 2 }
            ?: return null
        return scope.classOrNull(owner)
            ?.declaredMethods
            ?.firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
    }

    /**
     * 版本变了先清空再写。旧版本扫出来的名字留在那儿虽然会因版本对不上而不被采用，
     * 但一旦某次写入把版本号抬上来，那些陈旧条目就会突然「生效」。
     */
    private fun remember(scope: HookScope, key: String, method: Method) {
        val prefs = cache(scope) ?: return
        runCatching {
            val editor = prefs.edit()
            if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) {
                editor.clear().putLong(KEY_VERSION, scope.versionCode)
            }
            editor.putString(key, "${method.declaringClass.name}#${method.name}").apply()
        }
    }
}
