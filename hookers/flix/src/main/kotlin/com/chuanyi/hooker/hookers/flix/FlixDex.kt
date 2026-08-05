package com.chuanyi.hooker.hookers.flix

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * Java 层落点的定位。
 *
 * ## 为什么这一版用不上 DexKit，却还是留着它
 *
 * Flix 2.2.1 的 dex **没开混淆**：`defpackage` 下只有 6 个类，`com.jarvan.fluwx.*`、
 * `com.tencent.mm.opensdk.*`、`io.flutter.plugins.*` 全是明文。按名字取类当场就能拿到，
 * 扫 dex 纯属浪费那几百毫秒。
 *
 * 但「这一版没混淆」不等于「下一版也不会」。所以两条路都留着，顺序是**先明文、后扫描**：
 *
 * 1. 按写死的类名直接取 —— 当前版本必中，零开销
 * 2. 取不到（目标开了 R8，或者换了实现）→ DexKit 按**行为特征**扫
 *
 * 行为特征指的是那些「改了功能就不成立」的东西：`isWXAppInstalled` 要判微信装没装，就
 * 绕不开 `"com.tencent.mm"` 这个包名字符串；`url_launcher` 要报「打不开」，就绕不开它
 * 自己那几个错误码。这些比类名稳。
 *
 * ## 扫不到不算失败
 *
 * 这两处都只服务于**兼容性**（没装微信/支付宝时让支付入口仍然可选），解锁本身一个都不
 * 依赖。所以全部返回可空，调用方拿到 null 就跳过并记一行日志，不影响主功能。
 */
internal object FlixDex {

    /** 微信 openSDK 的 API 实现类。2.2.1 里是明文。 */
    private val WECHAT_IMPL = arrayOf(
        "com.tencent.mm.opensdk.openapi.WXApiImplV10",
        "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10",
    )

    /** url_launcher 插件的实现类。2.2.1 里是明文。 */
    private val URL_LAUNCHER = arrayOf(
        "io.flutter.plugins.urllauncher.UrlLauncher",
    )

    /** 微信客户端的包名。判「装没装」绕不开它，是这条链上最稳的特征。 */
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /** Dart 侧问「能不能开」和「去开」用的两个方法，都要挡。 */
    private val LAUNCH_METHODS = listOf("canLaunchUrl", "launchUrl")

    /**
     * `IWXAPI.isWXAppInstalled()` 的实现。
     *
     * 钉成 true 之后，没装微信的设备上微信支付入口照样可选 —— 配合
     * [Entitlement.PAY_BYPASS]，整条下单→查单的流程能走完，不需要真的装微信。
     */
    fun wechatInstalledCheck(scope: HookScope): Method? =
        byName(scope, WECHAT_IMPL) { cls ->
            cls.declaredMethods.firstOrNull {
                it.name == "isWXAppInstalled" &&
                    it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
        } ?: scan(scope, "微信安装检测") { dex ->
            dex.findMethod {
                matcher {
                    name = "isWXAppInstalled"
                    returnType = "boolean"
                    paramCount = 0
                }
            }.ifEmpty {
                // 名字也被改了：退到「返回 boolean、无参、函数体里提到微信包名」。
                dex.findMethod {
                    matcher {
                        returnType = "boolean"
                        paramCount = 0
                        usingStrings(WECHAT_PACKAGE)
                    }
                }
            }
        }

    /**
     * url_launcher 的 `canLaunchUrl` / `launchUrl`。
     *
     * 支付宝走的不是 SDK 而是 scheme 跳转（`alipays://`），没装支付宝时抛
     * `ActivityNotFoundException`，界面上就是那句「无法拉起支付宝，请稍后重试」。两个方法
     * 都要：Dart 侧一般先问 `canLaunchUrl` 再 `launchUrl`，只挡后一个，前一个就先把入口
     * 判死了。
     *
     * **返回的是装箱 `java.lang.Boolean`，不是 `boolean`** —— pigeon 生成的接口一律用装箱
     * 类型。按基本类型匹配会一个都找不到（这一版就踩过）。
     *
     * 签名在插件各版本间变过（早期 `launch(String, ...)`，pigeon 化之后是
     * `launchUrl(String, Map, Boolean)`），所以只认名字和返回类型，不写死参数表。
     */
    fun urlLaunchers(scope: HookScope): List<Method> {
        val byName = scope.classOrNull(*URL_LAUNCHER)
            ?.declaredMethods
            ?.filter { it.name in LAUNCH_METHODS && it.returnType.isBooleanLike() }
            .orEmpty()
        if (byName.isNotEmpty()) {
            scope.log.d("按类名命中 ${byName.joinToString { "${it.declaringClass.simpleName}.${it.name}" }}")
            return byName
        }

        return LAUNCH_METHODS.mapNotNull { name ->
            scan(scope, "URL 拉起($name)") { dex ->
                dex.findMethod {
                    matcher {
                        this.name = name
                        returnType = "java.lang.Boolean"
                    }
                }
            }
        }
    }

    /** pigeon 用装箱类型，老版本用基本类型 —— 两个都收。 */
    private fun Class<*>.isBooleanLike(): Boolean =
        this == Boolean::class.javaObjectType || this == Boolean::class.javaPrimitiveType

    // -----------------------------------------------------------------------

    /** 按写死的类名取，第一个能解析且能挑出方法的就用。 */
    private fun byName(
        scope: HookScope,
        names: Array<String>,
        pick: (Class<*>) -> Method?,
    ): Method? = names.firstNotNullOfOrNull { name ->
        scope.classOrNull(name)?.let(pick)
    }?.also { scope.log.d("按类名命中 ${it.declaringClass.name}.${it.name}") }

    /**
     * 扫一次 dex。
     *
     * 打不开就算了（本机 ABI 没有 `libdexkit.so` 时会抛 `UnsatisfiedLinkError`）——
     * 这两处都是可选功能，调用方拿 null 直接跳过。
     */
    private fun scan(
        scope: HookScope,
        label: String,
        query: (DexKitBridge) -> org.luckypray.dexkit.result.MethodDataList,
    ): Method? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("$label：拿不到 APK 路径，跳过 dex 扫描")
            return null
        }

        // DexKit 在自己的 static 初始化里调 `System.loadLibrary("dexkit")`，而在 LSPosed
        // 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，等到
        // 第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。先用模块
        // 自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载一次，DexKit
        // 自己那次随后就成了空操作。
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

            when {
                hits.isNullOrEmpty() -> {
                    scope.log.w("$label：dex 里没找到（${elapsed}ms）")
                    null
                }

                else -> {
                    if (hits.size > 1) {
                        scope.log.w("$label：命中 ${hits.size} 个，取第一个：${hits.joinToString { it.descriptor }}")
                    }
                    runCatching { hits.first().getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.w("$label：命中的方法反射不出来：${it.message}") }
                        .getOrNull()
                        ?.also { scope.log.i("$label：dex 扫描命中 ${it.declaringClass.name}.${it.name}（${elapsed}ms）") }
                }
            }
        }
    }
}
