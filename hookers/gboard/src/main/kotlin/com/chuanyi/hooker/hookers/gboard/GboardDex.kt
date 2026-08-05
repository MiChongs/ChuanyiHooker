package com.chuanyi.hooker.hookers.gboard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * 按特征串在 dex 里找类和方法。
 *
 * Gboard 的剪贴板整包都被 R8 重命名过，但**每个类的 Flogger TAG 里留着原始全限定
 * 名**（`wbu.i("com/google/.../ClipboardAdapter")`），日志方法调用里还留着原始方法
 * 名（`"deleteExpiredItemsInternal"`）。这些串是 Google 内部日志系统的定位信息，
 * 跨版本不会变 —— 比任何混淆后的名字都稳。
 *
 * ## 为什么必须缓存
 *
 * Gboard 的 base.apk 有 88 MB、四个 dex，扫一遍要几百毫秒到数秒。而这段代码跑在
 * **输入法的启动路径**上 —— 用户点开输入框到键盘出现之间。所以扫描结果按
 * versionCode 缓存在目标自己的数据目录（模块的远端配置从被 hook 的进程只能读），
 * 之后每次启动只是一次 SharedPreferences 读取。
 *
 * 缓存里存的是类名与方法描述符，回来时再按名字反射 —— 名字对不上（原地覆盖安装但
 * versionCode 没变）就当没缓存，重新扫。
 */
internal object GboardDex {

    private const val CACHE_FILE = "chuanyi_hooker_gboard"
    private const val KEY_VERSION = "version"

    /**
     * 加载剪贴板列表的那个 `Callable`。
     *
     * 锚点是它拼 SQL 用的格式串：`item_type` 的两个位（固定 / 最近使用）加时间下限，
     * 这是查询语义本身，改了它剪贴板就查错了 —— 比类名稳得多。
     */
    const val ANCHOR_LOADER = "(%s & %d) = 0 AND (%s & %d) = 0 AND %s >= ?"

    /** 清理过期项的那个 `Callable`。锚点是它出错时打的原始方法名。 */
    const val ANCHOR_CLEANER = "deleteExpiredItemsInternal"

    /** `InputBundleManager.loadActiveInputBundleId()`：挑当前该用哪套键盘。 */
    const val ANCHOR_ACTIVE_BUNDLE = "loadActiveInputBundleId"

    /**
     * 按类里出现过的字符串找类。
     *
     * `usingStrings` 是**包含**匹配，而 R8 会把一堆 lambda 与工具方法合并进共享类，
     * 那些类里也带着原主人的日志 TAG —— 光凭串会命中好几个，顺序还不确定。所以
     * 调用方要给一个 [verify]：命中的类身上得真有它要的那个成员，才算找对。
     */
    fun classByString(
        scope: HookScope,
        anchor: String,
        verify: (Class<*>) -> Boolean = { true },
    ): Class<*>? {
        cachedName(scope, "class:$anchor")
            ?.let { scope.classOrNull(it) }
            ?.takeIf { runCatching { verify(it) }.getOrDefault(false) }
            ?.let { return it }

        return scan(scope, "扫类 $anchor") { dex ->
            dex.findClass { matcher { usingStrings(anchor) } }
                .asSequence()
                .mapNotNull { runCatching { it.getInstance(scope.classLoader) }.getOrNull() }
                .firstOrNull { runCatching { verify(it) }.getOrDefault(false) }
        }?.also { remember(scope, "class:$anchor", it.name) }
    }

    /**
     * 按字符串找一个无参、返回 `Object` 的方法 —— 也就是某个 `Callable.call()`。
     *
     * 这两个 Callable 都是匿名内部类，没有 TAG 可用，只能从它们方法体里的常量入手。
     */
    fun callableByString(scope: HookScope, anchor: String): Method? {
        cachedName(scope, "callable:$anchor")?.let { className ->
            scope.classOrNull(className)
                ?.declaredMethods
                ?.firstOrNull { it.parameterCount == 0 && it.returnType == Any::class.java }
                ?.let { return it.apply { isAccessible = true } }
        }
        return scan(scope, "扫 Callable $anchor") { dex ->
            dex.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "java.lang.Object"
                    usingStrings(anchor)
                }
            }.firstOrNull()?.getMethodInstance(scope.classLoader)
        }?.apply {
            isAccessible = true
            remember(scope, "callable:$anchor", declaringClass.name)
        }
    }

    /**
     * 按方法体里出现过的字符串找方法。
     *
     * Flogger 的调用点把**原始方法名**当参数传了进去 ——
     * `.j("com/…/InputBundleManager", "loadActiveInputBundleId", 552, "InputBundleManager.java")`
     * 混淆之后这一行原样还在。所以类的 TAG 加上方法名这两个串合起来，
     * 就是「某个类的某个方法」的稳定坐标，比方法名本身、比它在类里的位置都稳。
     *
     * [anchors] 全部命中才算（`usingStrings` 之间是与关系），[params] 再卡一道参数个数。
     */
    fun methodByStrings(scope: HookScope, params: Int, vararg anchors: String): Method? {
        val key = "method:${anchors.joinToString("|")}"
        cachedName(scope, key)?.split('#')?.takeIf { it.size == 2 }?.let { (owner, name) ->
            scope.classOrNull(owner)
                ?.declaredMethods
                ?.firstOrNull { it.name == name && it.parameterCount == params }
                ?.let { return it.apply { isAccessible = true } }
        }
        return scan(scope, "扫方法 ${anchors.last()}") { dex ->
            dex.findMethod {
                matcher {
                    paramCount = params
                    usingStrings(anchors.toList())
                }
            }.firstOrNull()?.getMethodInstance(scope.classLoader)
        }?.apply {
            isAccessible = true
            remember(scope, key, "${declaringClass.name}#$name")
        }
    }

    // -----------------------------------------------------------------------

    /**
     * DexKit 的原生库。
     *
     * 2.x 起 `DexKitBridge` **不再自己 `loadLibrary`** —— 官方要求使用方显式加载，
     * 好让模块能自定义加载路径。漏掉这一步的表现不是「找不到 so」，而是调用时报
     * `No implementation found for … nativeInitDexKit`，看起来像版本不匹配。
     *
     * 在被注入的进程里还有第二层麻烦：`System.loadLibrary` 按**调用类的
     * classloader** 找库，而这里的调用类由框架的模块 classloader 加载，它未必带着
     * 模块 APK 的原生库路径。所以直接加载失败时，再问一次 classloader 那个库的绝对
     * 路径（`findLibrary` 是 protected，只能反射），拿到就按路径装。
     */
    private val nativeReady: Boolean by lazy {
        runCatching {
            System.loadLibrary("dexkit")
            return@lazy true
        }
        runCatching {
            val loader = GboardDex::class.java.classLoader ?: return@runCatching false
            val find = ClassLoader::class.java
                .getDeclaredMethod("findLibrary", String::class.java)
                .apply { isAccessible = true }
            val path = find.invoke(loader, "dexkit") as? String ?: return@runCatching false
            System.load(path)
            true
        }.getOrDefault(false)
    }

    /**
     * 开一次 DexKit 做一件事。
     *
     * 打不开就返回 null（本机 ABI 没有 `libdexkit.so` 时会抛 `UnsatisfiedLinkError`）
     * —— 调用方各自决定这一项功能是跳过还是报错，不影响别的功能。
     */
    private fun <T> scan(scope: HookScope, what: String, block: (DexKitBridge) -> T?): T? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 $what")
            return null
        }
        if (!nativeReady) {
            scope.log.w("libdexkit.so 装不上，跳过 $what")
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val result = runCatching { block(dex) }
                .onFailure { scope.log.w("$what 失败：${it.message}") }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (result == null) {
                scope.log.w("$what 没有结果（${elapsed}ms）")
            } else {
                scope.log.d("$what 用时 ${elapsed}ms")
            }
            result
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let {
            runCatching { it.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    private fun cachedName(scope: HookScope, key: String): String? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 版本变了就先清空再写：旧版本扫出来的类名放在那儿，
     * 下一次 [cachedName] 虽然会因为版本对不上而不用它，但它会一直占着位置，
     * 而且一旦某次写入把版本号抬上来，那些陈旧的条目就会突然"生效"。
     */
    private fun remember(scope: HookScope, key: String, name: String) {
        val prefs = cache(scope) ?: return
        runCatching {
            val editor = prefs.edit()
            if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) {
                editor.clear().putLong(KEY_VERSION, scope.versionCode)
            }
            editor.putString(key, name).apply()
        }
    }
}
