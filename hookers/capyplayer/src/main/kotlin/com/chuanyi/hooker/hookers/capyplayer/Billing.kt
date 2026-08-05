package com.chuanyi.hooker.hookers.capyplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * `in_app_purchase_android` 的 pigeon 宿主实现，按明文串定位。
 *
 * ## 要它干什么
 *
 * 没装 Google Play 的设备上，`BillingClient` 建不起来，于是
 *
 * ```java
 * public boolean isFeatureSupported(PlatformBillingClientFeature f) {
 *     BillingClient c = this.billingClient;
 *     if (c == null) throw new FlutterError("UNAVAILABLE",
 *                          "BillingClient is unset. Try reconnecting.", null);
 *     …
 * }
 * ```
 *
 * 注意它**抛异常**而不是返回 false —— 所以 after hook 永远看不到这个返回值，必须
 * 整个替换掉。Dart 侧收到的是一个 `PlatformException`，订阅相关的界面会据此走进
 * 「本设备不支持」的分支。
 *
 * 钉成 `true` 之后探测直接通过，无 Play 的机器上界面和流程跟有 Play 时一致。
 *
 * ## 这条和解锁是分开的
 *
 * 真正的解锁在 [Entitlement]，全在 Dart 层，**不经过 Play、不经过这个类**。这里
 * 修的只是「无 Play 时的探测噪音」，坏了也只是订阅页某些控件变灰，功能照常。所以
 * 两者互不依赖，任一失败另一个照常工作。
 *
 * ## 为什么必须用 DexKit
 *
 * 这个类被 R8 重命名了（1.1.3 里是 `v7.h`），而且**接口方法名也没留下** ——
 * pigeon 生成的 `InAppPurchaseApi` 接口被优化成了一个空接口，14 个 API 里除了
 * `isFeatureSupported` 和 `launchBillingFlow`，其余全被内联进 setUp 方法那个五千行
 * 的 lambda 里。名字一个都靠不住，只有它自带的英文错误串是跨版本稳定的 ——
 * 那是 Flutter 官方插件的源码常量，不随宿主应用的构建变化。
 */
internal object Billing {

    /**
     * 定位锚点。来自 `in_app_purchase_android` 插件自身的源码，宿主应用重新构建
     * 不会动它。
     */
    private const val ANCHOR = "BillingClient is unset. Try reconnecting."

    /** 1.1.3 的实际类名，DexKit 用不了时的退路。 */
    private val PINNED = arrayOf("v7.h", "p188v7.C1649h")

    private const val CACHE_FILE = "chuanyi_hooker_capyplayer"
    private const val KEY_VERSION = "billing.version"
    private const val KEY_CLASS = "billing.class"

    /** 终身版的商品号，`_handlePurchase` 里和 `capyplayer.pro.year` 并列写死的那个。 */
    const val LIFETIME_PRODUCT = "capyplayer.pro.lifetime"

    /**
     * 目标持有的 `BillingClient`，从 pigeon 宿主类的字段形状认出来。
     *
     * 宿主类的字段就那么几个：`Context`、`Activity`、callback API、一个 `HashMap`，
     * 外加**唯一一个非 final、类型既不是接口也不是 JDK/Android 类**的字段 —— 那就是
     * `BillingClient`。它非 final 是因为连接断开时会被置回 null（`isFeatureSupported`
     * 里 `if (client == null) throw` 判的就是它）。
     *
     * 按形状取而不是按类型名：`p080j4.C1012c` 这种名字每次构建都会变。
     */
    fun billingClientField(scope: HookScope): java.lang.reflect.Field? {
        val host = resolveClass(scope) ?: return null
        val hits = host.declaredFields.filter { f ->
            !java.lang.reflect.Modifier.isFinal(f.modifiers) &&
                !java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                !f.type.isPrimitive && !f.type.isInterface && !f.type.isArray &&
                !f.type.name.startsWith("android.") &&
                !f.type.name.startsWith("java.") &&
                !f.type.name.startsWith("kotlin.")
        }
        return when (hits.size) {
            1 -> hits[0].apply { isAccessible = true }
            0 -> {
                scope.log.w("${host.name} 上找不到 BillingClient 字段，形状变了")
                null
            }
            else -> {
                scope.log.w("${host.name} 上有 ${hits.size} 个候选字段：${hits.joinToString { "${it.name}:${it.type.simpleName}" }}")
                hits[0].apply { isAccessible = true }
            }
        }
    }

    /**
     * 一条合成的终身买断记录，用 Play 自己的 JSON 格式。
     *
     * `com.android.billingclient.api.Purchase` 是这条链上**唯一没被混淆**的类 ——
     * 它的构造器是 `(originalJson, signature)`，构造时只做一次 `new JSONObject(json)`，
     * 不校验任何东西。而它的 getter 被 R8 内联光了，消费方一律直接读那个 JSONObject，
     * 所以只要 JSON 的字段名对，这个对象和 Play 发下来的没有区别。
     *
     * 往下游走，计费库自己会把它翻成 pigeon 的 `PlatformPurchase`、编码、投给 Dart ——
     * 一个 pigeon 类都不用碰。
     *
     * `productIds` 和 `productId` 都写：Play Billing 5 起用数组，旧字段留着兼容。
     * `purchaseState: 0` 是 JSON 里的「已购买」（注意它和 API 常量 `PURCHASED = 1`
     * 不是一个编码，写 1 反而会被当成 canceled）。
     */
    fun syntheticPurchaseJson(packageName: String, product: String = LIFETIME_PRODUCT): String {
        val token = "chuanyi-" + java.util.UUID.randomUUID().toString().replace("-", "")
        return org.json.JSONObject()
            .put("orderId", "GPA.0000-0000-0000-00000")
            .put("packageName", packageName)
            .put("productId", product)
            .put("productIds", org.json.JSONArray().put(product))
            .put("purchaseTime", 1_600_000_000_000L)
            .put("purchaseState", 0)
            .put("purchaseToken", token)
            .put("quantity", 1)
            .put("acknowledged", true)
            .put("autoRenewing", false)
            .toString()
    }

    /**
     * `isFeatureSupported`，按形状而非名字取。
     *
     * 在这个类里它是唯一「一个枚举入参、返回 boolean」的实例方法 —— 另外两个实质
     * 方法分别是 `static FlutterError` 工厂和返回自定义类型的 `launchBillingFlow`。
     * 合成方法一律排除：Kotlin 给被 lambda 捕获的私有成员生成的桥接方法可能撞上
     * 同样的签名。
     */
    fun featureSupported(scope: HookScope): Method? {
        val clazz = resolveClass(scope) ?: return null
        val hits = clazz.declaredMethods.filter {
            !it.isSynthetic && !it.isBridge &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isEnum
        }
        return when (hits.size) {
            1 -> hits[0]
            0 -> {
                scope.log.w("${clazz.name} 上没有「枚举入参、返回 boolean」的方法，形状变了")
                null
            }
            else -> {
                scope.log.w("${clazz.name} 上有 ${hits.size} 个同形方法，不猜：${hits.joinToString { it.name }}")
                null
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun resolveClass(scope: HookScope): Class<*>? {
        cached(scope)?.let {
            scope.log.d("Billing 宿主类命中缓存：${it.name}")
            return it
        }
        scan(scope)?.let {
            remember(scope, it)
            scope.log.i("Billing 宿主类扫描到：${it.name}")
            return it
        }
        return scope.classOrNull(*PINNED)?.also {
            scope.log.w("扫描没结果，退回写死的类名：${it.name}")
        }
    }

    /**
     * 扫一次几百毫秒，且发生在启动路径上，所以按 versionCode 缓存。
     *
     * 缓存写目标自己的数据目录 —— 模块的远端配置对被 hook 的进程是只读的。
     */
    private fun cached(scope: HookScope): Class<*>? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val name = prefs.getString(KEY_CLASS, null)?.takeIf { it.isNotBlank() } ?: return null
        return scope.classOrNull(name)
    }

    private fun remember(scope: HookScope, clazz: Class<*>) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(KEY_CLASS, clazz.name)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /**
     * 只用 `usingStrings` 就够：这段错误文案在整个包里只有 pigeon 宿主实现会引用，
     * 而它同时是 `isFeatureSupported` 和其它几个方法的 `BillingClient == null` 出口，
     * 所以「引用了它的类」必然就是要找的那个。
     */
    private fun scan(scope: HookScope): Class<*>? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return null
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而在
        // LSPosed 给模块构造的 classloader 下那条路不一定通 —— 实测就是不通，症状是
        // 后面第一次调原生方法时报 "No implementation found for nativeInitDexKit"，
        // 而不是加载时就失败，很有迷惑性。先用模块自己那套带绝对路径兜底的加载器把
        // 它装进来；soname 一个进程只会加载一次，DexKit 自己那次随后就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val hits = runCatching {
                dex.findClass { matcher { usingStrings(ANCHOR) } }
            }.onFailure { scope.log.w("dex 扫描失败：${it.message}") }.getOrNull()

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when {
                hits.isNullOrEmpty() -> {
                    scope.log.w("dex 里找不到引用 '$ANCHOR' 的类（${elapsed}ms）")
                    null
                }

                else -> {
                    if (hits.size > 1) {
                        scope.log.w("锚点命中 ${hits.size} 个类，取第一个：${hits.joinToString { it.name }}")
                    }
                    scope.log.d("dex 扫描用时 ${elapsed}ms")
                    scope.classOrNull(hits.first().name)
                }
            }
        }
    }
}
