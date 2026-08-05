package com.chuanyi.hooker.hookers.airmusic

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Java 层落点的定位。
 *
 * 这个包的混淆是半边的：`app.airmusic.*` 下的类名全是明文（`AudioProxy`、
 * `CommonUtils`、`MainActivity`…），而 Google LVL 那一套被 R8 搬进了默认包并重命名成
 * `r01` / `ec0` / `fc0` / `dc0` / `y6` 这种两三个字符的名字 —— 那批名字每次构建都会变，
 * 写死等于绑死在这一版上。
 *
 * 所以两种取法各管一段：
 *
 * * 明文类（[AirMusic] 里那几个常量）直接按名字取，方法名虽然也被缩成了单字母，
 *   但**形状**在类内唯一，按形状挑即可，零开销；
 * * 授权裁决那个方法只能扫 —— 它的类名、方法名全变了，唯一不变的是「它必须调用
 *   `AudioProxy.h` 来验签」。这是协议性质的依赖，改了功能就不成立，比名字稳得多。
 */
internal object AirMusicDex {

    /**
     * 授权裁决：`Policy.allowAccess()`，3.2.2 里是 `r01.a()`。
     *
     * ## 为什么是这一个方法
     *
     * 整个 LVL 流程最后都收敛到它，而且是**唯一**的收敛点：
     *
     * ```text
     * y6.onReceive()      → if (policy.allowAccess()) { 已授权 }   ← 连 Play 都不绑
     *                       else bindService(ILicensingService)
     * fc0.b(data,code,sig)→ if (policy.allowAccess()) { 已授权 }   ← 有 Play 应答时
     * ec0.b(fc0)          → if (policy.allowAccess()) { 已授权 }   ← 绑不上服务时
     * ```
     *
     * 三条路（正常应答 / 绑不上 / 压根没绑）全过它，所以钉住它一处，
     * **有没有 Google 服务都一样成立** —— 这正是「冻结 Play 也要能用」要的性质。
     *
     * ## 特征选择
     *
     * 方法体末尾是 `return AudioProxy.h(cachedData, cachedSignature)`：把缓存下来的
     * signedData 和签名交给原生层验签。`AudioProxy` 是明文类名，`h` 是它的 JNI 方法
     * （so 里的导出符号就叫 `Java_app_airmusic_proxy_AudioProxy_h`，改不动）。
     *
     * 「无参 + 返回 boolean + 调用 `AudioProxy.h`」在整个 dex 里只有这一个命中：
     * 另一处调 `h` 的是 `dc0` 里那个 `Runnable.run()`，返回 void，形状对不上。
     */
    fun allowAccess(scope: HookScope): Method? = scan(scope, "授权裁决") { dex ->
        dex.findMethod {
            matcher {
                returnType = "boolean"
                paramCount = 0
                addInvoke {
                    name = AirMusic.NATIVE_VERIFY
                    declaredClass(AirMusic.AUDIO_PROXY)
                }
            }
        }
    }

    /**
     * 试用版判定：`CommonUtils` 上那个 `return getResources().getBoolean(R.bool.is_trial)`。
     *
     * 类名是明文的，方法名被缩成了 `e`。类里九个方法只有它「无参返回 boolean」，
     * 按形状挑就够，扫 dex 是取不到时的备选。
     */
    fun isTrialCheck(scope: HookScope): Method? {
        val byShape = scope.classOrNull(AirMusic.COMMON_UTILS)
            ?.declaredMethods
            ?.filter {
                it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    Modifier.isStatic(it.modifiers)
            }
            ?.singleOrNull()
        if (byShape != null) {
            scope.log.d("按形状命中 ${AirMusic.COMMON_UTILS}.${byShape.name}()")
            return byShape
        }

        scope.log.d("${AirMusic.COMMON_UTILS} 上没有形状唯一的试用判定，改扫 dex")
        return scan(scope, "试用版判定") { dex ->
            dex.findMethod {
                matcher {
                    declaredClass(AirMusic.COMMON_UTILS)
                    returnType = "boolean"
                    paramCount = 0
                }
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * 扫一次 dex。
     *
     * 打不开就算了，调用方拿到 null 自己决定要不要降级 —— 本机 ABI 没打进
     * `libdexkit.so` 时会抛 `UnsatisfiedLinkError`，那不该让整个 hooker 崩掉。
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
