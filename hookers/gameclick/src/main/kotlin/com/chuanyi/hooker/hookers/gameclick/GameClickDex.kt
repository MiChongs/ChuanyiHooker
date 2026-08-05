package com.chuanyi.hooker.hookers.gameclick

import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodDataList
import java.lang.reflect.Method

/**
 * Java 层落点的定位。
 *
 * ## 为什么这里的 DexKit 必须走「内存 dex」
 *
 * 目标是 **360 加固**包（`assets/libjiagu_a64.so` + `com.stub.StubApp`）。
 * 装在手机上的 `base.apk` 里 `classes.dex` 只有 5 个类 —— 壳自己的
 * `StubApp` / `DtcLoader` / `Configuration` 和两个工具类，应用一行真代码都没有。
 * 真正的 dex 由壳在 `attachBaseContext` 里解密后塞进内存。
 *
 * 所以其它 hooker 那套 `DexKitBridge.create(scope.apkPath)` 在这里**必然扫不到东西**，
 * 而且不会报错，只会安静地返回 0 条命中 —— 是最难查的那种失败。
 * 这里一律用 `DexKitBridge.create(classLoader, useMemoryDexFile = true)`：
 * DexKit 从 classloader 已加载的 dex 文件（含内存 dex）里取，正好覆盖脱壳后的形态。
 *
 * ## 顺序：先明文，后扫描
 *
 * 4.0.1 解密出来的 dex **主类名没混淆**（`com.pbb.gameclick.NativeUtil`、`DataConst`、
 * `FloatClickView` 全是明文，只有辅助包被压成了单字母）。按名字取当场就中，
 * 省掉扫一遍 2.7 MB dex 的几百毫秒。
 *
 * 扫描只在按名字取不到时才跑 —— 也就是应用哪天开了混淆，或者类被挪了位置。
 * 特征都选「改了功能就不成立」的东西：授权判定绕不开那一组 native 方法名，
 * 设备位校验绕不开「购买」「设备位」这两个要显示给用户看的词。
 */
internal object GameClickDex {

    // --- 类 -----------------------------------------------------------------

    /**
     * 授权判定类。
     *
     * 扫描特征：`IsVip()Z` 这个 native 方法。整个 dex 里独一份，
     * 而且只要还叫「会员」就改不掉判定本身的存在。
     */
    fun nativeUtil(scope: HookScope): Class<*>? =
        scope.classOrNull(GameClick.NATIVE_UTIL)
            ?: scanClass(scope, "授权判定类") { dex ->
                dex.findMethod {
                    matcher {
                        name = "IsVip"
                        returnType = "boolean"
                        paramCount = 0
                    }
                }
            }

    /**
     * 全局状态类。
     *
     * 扫描特征：`CheckDeviceNum()` 里那两个要弹给用户看的词。它们是**协议约定**
     * ——服务端把「设备位已满，请购买」这类文案塞进 `loginwxerror`，客户端靠
     * `contains` 判分支，两边必须对得上，所以改不动。
     */
    fun dataConst(scope: HookScope): Class<*>? =
        scope.classOrNull(GameClick.DATA_CONST)
            ?: scanClass(scope, "全局状态类") { dex ->
                dex.findMethod {
                    matcher {
                        returnType = "boolean"
                        paramCount = 0
                        usingStrings("购买", "设备位")
                    }
                }
            }

    /** 悬浮窗。区域数量上限的判定在它的 `AddClickRect()` 里。 */
    fun floatClickView(scope: HookScope): Class<*>? =
        scope.classOrNull(GameClick.FLOAT_CLICK_VIEW)
            ?: scanClass(scope, "悬浮窗类") { dex ->
                dex.findMethod {
                    matcher {
                        name = "AddClickRect"
                        returnType = "void"
                        paramCount = 0
                    }
                }
            }

    // --- 方法 ---------------------------------------------------------------

    /**
     * `NativeUtil.LoginResult(String)` —— 服务端登录返回的**唯一**落地口。
     *
     * 整条会员链路上游只有这一个入口：应用把 HTTP 响应体原样交给它，原生库解析
     * 完就成了内部状态，后面所有 `IsVip()` / `GetVipTime()` / `DeviceNum()` 都从
     * 那份状态读。改这里等于改源头。
     */
    fun loginResult(scope: HookScope): Method? =
        nativeUtil(scope)?.declaredMethods?.firstOrNull {
            it.name == "LoginResult" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }

    /** `NativeUtil` 上无参、返回 boolean 的判定方法。 */
    fun boolGate(scope: HookScope, name: String): Method? =
        nativeUtil(scope)?.declaredMethods?.firstOrNull {
            it.name == name &&
                it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        }

    /** `NativeUtil.GetVipTime()`。返回 String，只能在 Java 侧换，见 [GameClick.VIP_TIME_FOREVER]。 */
    fun vipTime(scope: HookScope): Method? =
        nativeUtil(scope)?.declaredMethods?.firstOrNull {
            it.name == "GetVipTime" && it.parameterCount == 0 && it.returnType == String::class.java
        }

    /** `NativeUtil.DeviceNum()`。 */
    fun deviceNum(scope: HookScope): Method? =
        nativeUtil(scope)?.declaredMethods?.firstOrNull {
            it.name == "DeviceNum" &&
                it.parameterCount == 0 &&
                it.returnType == Int::class.javaPrimitiveType
        }

    /**
     * `DataConst.CheckDeviceNum()` —— 悬浮窗开不开的第二道门。
     *
     * 名字没混淆时直接取；否则按形状挑：静态、无参、返回 boolean、函数体里提到
     * 那两个文案词。
     */
    fun checkDeviceNum(scope: HookScope): Method? =
        dataConst(scope)?.declaredMethods?.firstOrNull {
            it.name == "CheckDeviceNum" &&
                it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: scanMethod(scope, "设备位校验") { dex ->
            dex.findMethod {
                matcher {
                    returnType = "boolean"
                    paramCount = 0
                    usingStrings("购买", "设备位")
                }
            }
        }

    /** `DataConst.CheckTime()` —— 每 24 小时联网复核一次会员。 */
    fun checkTime(scope: HookScope): Method? =
        dataConst(scope)?.declaredMethods?.firstOrNull {
            it.name == "CheckTime" && it.parameterCount == 0 && it.returnType == Void.TYPE
        }

    /** `FloatClickView.AddClickRect()` —— 加连点区域，区域数量上限在这里判。 */
    fun addClickRect(scope: HookScope): Method? =
        floatClickView(scope)?.declaredMethods?.firstOrNull {
            it.name == "AddClickRect" && it.parameterCount == 0 && it.returnType == Void.TYPE
        }

    // -----------------------------------------------------------------------

    private fun scanClass(
        scope: HookScope,
        label: String,
        query: (DexKitBridge) -> MethodDataList,
    ): Class<*>? = scanMethod(scope, label, query)?.declaringClass

    /**
     * 扫一次内存 dex。
     *
     * 打不开就算了 —— 调用方全部按可空处理，扫不到只是少一条兜底路径，
     * 当前版本按名字那条路本来就够。
     */
    private fun scanMethod(
        scope: HookScope,
        label: String,
        query: (DexKitBridge) -> MethodDataList,
    ): Method? {
        // DexKit 在自己的 static 初始化里调 System.loadLibrary("dexkit")，而在 LSPosed
        // 给模块构造的 classloader 下那条路不通 —— 症状很有迷惑性：加载当时不报错，
        // 等到第一次调原生方法才抛 "No implementation found for nativeInitDexKit"。
        // 先用模块自己那套带绝对路径兜底的加载器把它装进来；soname 一个进程只会加载
        // 一次，DexKit 自己那次随后就成了空操作。
        if (!NativeHook.loadModuleLibrary("dexkit")) {
            scope.log.w("$label：libdexkit.so 加载不起来（本机 ABI 可能没打进来），跳过 dex 扫描")
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        // 关键：走 classloader 而不是 apkPath。加固包的 apkPath 里只有壳。
        val bridge = runCatching { DexKitBridge.create(scope.classLoader, true) }
            .onFailure { scope.log.w("$label：DexKit 打不开内存 dex：${it.message}") }
            .getOrNull() ?: return null

        return bridge.use { dex ->
            val hits = runCatching { query(dex) }
                .onFailure { scope.log.w("$label：dex 扫描失败：${it.message}") }
                .getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            when {
                hits.isNullOrEmpty() -> {
                    scope.log.w("$label：内存 dex 里没找到（${elapsed}ms）")
                    null
                }

                else -> {
                    if (hits.size > 1) {
                        scope.log.w("$label：命中 ${hits.size} 个，取第一个：${hits.joinToString { it.descriptor }}")
                    }
                    runCatching { hits.first().getMethodInstance(scope.classLoader) }
                        .onFailure { scope.log.w("$label：命中的方法反射不出来：${it.message}") }
                        .getOrNull()
                        ?.also {
                            scope.log.i("$label：dex 扫描命中 ${it.declaringClass.name}.${it.name}（${elapsed}ms）")
                        }
                }
            }
        }
    }
}
