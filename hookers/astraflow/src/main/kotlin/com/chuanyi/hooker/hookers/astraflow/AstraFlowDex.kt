package com.chuanyi.hooker.hookers.astraflow

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 定位镜像签名函数。
 *
 * 这个函数是整条伪造链的支点：镜像 HMAC 得用**目标自己的**实现算，密钥和消息布局
 * 才会跟着版本走。但它所在的类被 R8 扔进了默认包，名字（v1.31 里是 `Xq`）每次构建
 * 都可能变，写死等于每次更新都要重开一次 jadx。
 *
 * 改按**特征串**找：HMAC 消息里有一段固定前缀
 *
 * ```
 * "v2astraflow-device-pass" + passState + …
 * ```
 *
 * 这段串服务端和客户端必须算出同一个值，所以它**不能改** —— 比任何类名都稳。再配上
 * 那个九参数签名（全包唯一），命中就是唯一的。
 *
 * 四条路依次试，前一条不成才走下一条：
 *
 * 1. `signer_class` 设置项 —— 应急用，不重新出包就能纠正
 * 2. 上次扫描的结果，按 versionCode 缓存在目标自己的数据目录里
 * 3. DexKit 扫 dex
 * 4. 写死的候选名 —— DexKit 加载不起来（.so 缺本机 ABI）时还能工作
 */
internal object AstraFlowDex {

    /** HMAC 消息里的固定串。改了它服务端就验不过自己签的凭证，所以它不会变。 */
    const val ANCHOR = "astraflow-device-pass"

    /**
     * 最后的退路：v1.31 里签名函数所在的类名。
     *
     * 它在**默认包**（无包名）。jadx 显示成 `defpackage.Xq` —— 那是 jadx 给无包名类
     * 合成的前缀，运行时不存在；apktool 又因为 Windows 大小写不敏感把 `Xq` 和 `XQ`
     * 的文件名撞在一起。两个工具的显示都不是真名，以 smali 里的 `.class … LXq;` 为准。
     */
    private val PINNED = arrayOf("Xq", "defpackage.Xq")

    private const val CACHE_FILE = "chuanyi_hooker_dex"
    private const val KEY_VERSION = "signer.version"
    private const val KEY_CLASS = "signer.class"
    private const val KEY_METHOD = "signer.method"

    fun signer(scope: HookScope): Method? {
        configured(scope)?.let {
            scope.log.i("签名函数来自设置项：${it.declaringClass.name}.${it.name}")
            return it
        }
        cached(scope)?.let {
            scope.log.d("签名函数命中缓存：${it.declaringClass.name}.${it.name}")
            return it
        }
        scan(scope)?.let {
            remember(scope, it)
            scope.log.i("签名函数扫描到：${it.declaringClass.name}.${it.name}")
            return it
        }
        return pinned(scope)?.also {
            scope.log.w("扫描没结果，退回写死的类名：${it.declaringClass.name}.${it.name}")
        }
    }

    // -----------------------------------------------------------------------

    private fun configured(scope: HookScope): Method? =
        scope.string(AstraFlow.KEY_SIGNER_CLASS)
            ?.takeIf { it.isNotBlank() }
            ?.let { scope.classOrNull(it) }
            ?.let(AstraFlow::signerIn)

    private fun pinned(scope: HookScope): Method? =
        scope.classOrNull(*PINNED)?.let(AstraFlow::signerIn)

    /**
     * 扫一次要几百毫秒，而它发生在应用启动路径上 —— 所以结果按 versionCode 缓存。
     * 缓存写在**目标自己**的数据目录：模块的远端配置是只读的，被 hook 的进程写不了。
     */
    private fun cached(scope: HookScope): Method? {
        val prefs = cache(scope) ?: return null
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return null
        val className = prefs.getString(KEY_CLASS, null)?.takeIf { it.isNotBlank() } ?: return null
        val methodName = prefs.getString(KEY_METHOD, null)?.takeIf { it.isNotBlank() } ?: return null
        // 名字对不上（应用原地覆盖安装但 versionCode 没变）就当没缓存，重新扫。
        return scope.classOrNull(className)
            ?.let(AstraFlow::signerIn)
            ?.takeIf { it.name == methodName }
    }

    private fun remember(scope: HookScope, signer: Method) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putString(KEY_CLASS, signer.declaringClass.name)
                .putString(KEY_METHOD, signer.name)
                .apply()
        }
    }

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /**
     * 特征串 + 完整签名一起给 DexKit，两个条件叠起来在全包里唯一。
     *
     * 只用 `usingStrings` 不够稳：那是**包含**匹配，理论上别的方法也能引用同一段串；
     * 只用签名也不够，九个参数的静态方法不保证只有一个。
     */
    private fun scan(scope: HookScope): Method? {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("拿不到 APK 路径，跳过 dex 扫描")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        // 打不开就算了（本机 ABI 没有 libdexkit.so 时会抛 UnsatisfiedLinkError）——
        // 调用方还有写死的候选名可退。
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit 打不开 $apkPath：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val hits = runCatching {
                dex.findMethod {
                    matcher {
                        modifiers = Modifier.STATIC
                        returnType = "java.lang.String"
                        paramTypes(
                            "java.lang.String", "java.lang.String", "java.lang.String",
                            "int", "long", "int", "int", "java.lang.String", "long",
                        )
                        usingStrings(ANCHOR)
                    }
                }
            }.onFailure { scope.log.w("dex 扫描失败：${it.message}") }.getOrNull()

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when {
                hits == null -> null
                hits.isEmpty() -> {
                    scope.log.w("dex 里找不到带 '$ANCHOR' 的签名函数（${elapsed}ms）")
                    null
                }

                else -> {
                    if (hits.size > 1) {
                        scope.log.w("特征串命中 ${hits.size} 个方法，取第一个：${hits.joinToString { it.descriptor }}")
                    }
                    scope.log.d("dex 扫描用时 ${elapsed}ms")
                    runCatching { hits.first().getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.w("命中的方法反射不出来：${it.message}") }
                        .getOrNull()
                }
            }
        }
    }
}
