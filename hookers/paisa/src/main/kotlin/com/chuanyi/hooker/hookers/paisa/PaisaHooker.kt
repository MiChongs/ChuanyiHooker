package com.chuanyi.hooker.hookers.paisa

import com.chuanyi.hooker.core.AppHooker
import com.chuanyi.hooker.core.HookFeature
import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.core.getFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createReplaceHook

/**
 * Paisa (`dev.hemanths.paisa`) — a Flutter expense tracker.
 *
 * Nothing about the premium gate lives in Dart-visible Java: the app is Flutter,
 * and every decision (`SubscriptionStatusCubit`, `isPro`, `isLifetimePro`) is
 * made in `libapp.so` from the customer-info `Map` that the RevenueCat Android
 * SDK sends over the method channel. That map has exactly one producer, so the
 * whole thing collapses to a handful of Java hooks — no Dart AOT patching.
 *
 * The build is also wrapped in Google PairIP (`com.pairip.licensecheck`, the
 * license-check flavour, no code virtualisation), which is irrelevant to a live
 * Xposed module but included because the same module is useful on a repacked
 * copy of the APK.
 */
class PaisaHooker : AppHooker {

    override val id = "paisa"
    override val displayName = "Paisa"
    override val description = "记账应用，解锁终身高级版"
    override val targetPackages = setOf("dev.hemanths.paisa")
    override val stage = AppHooker.Stage.PACKAGE_READY

    override val features: List<HookFeature> = listOf(
        HookFeature(
            id = "lifetime",
            title = "解锁终身高级版",
            summary = "让应用始终认为终身版已购买，全部高级功能直接可用",
            install = { installLifetime() },
        ),
        HookFeature(
            id = "offline_entitlement",
            title = "离线也保持解锁",
            summary = "没有网络、或首次启动还没同步过账户时，解锁状态依然成立",
            install = { installOfflineFallback() },
        ),
        HookFeature(
            id = "pairip",
            title = "跳过安装来源校验",
            summary = "只有在用重新签名的安装包时才需要打开；从应用商店正常安装的无需理会",
            defaultEnabled = false,
            install = { installPairipBypass() },
        ),
        HookFeature(
            id = "log_customer_info",
            title = "记录会员状态",
            summary = "排查用：把应用读到的会员信息写进日志，用来确认解锁是否生效",
            defaultEnabled = false,
            install = { installCustomerInfoLog() },
        ),
        HookFeature(
            id = "native_file_log",
            title = "记录文件读取",
            summary = "排查用：记录应用读取了哪些本地数据文件",
            defaultEnabled = false,
            requiresRestart = false,
            install = { installNativeFileLog() },
        ),
    )

    override fun isCompatible(scope: HookScope): Boolean {
        val present = scope.classOrNull(CUSTOMER_INFO_MAPPER) != null
        if (!present) {
            scope.log.w("RevenueCat hybrid-common mapper not found — build too old or renamed")
        }
        return present
    }

    override fun onHook(scope: HookScope) {
        scope.log.i("Paisa ${scope.versionCode} in ${scope.processName}")
    }

    // -----------------------------------------------------------------------

    /**
     * `CustomerInfoMapperKt.map(CustomerInfo)` is the single funnel through
     * which the SDK hands Dart a customer-info map: `getCustomerInfo`,
     * `restorePurchases`, `logIn`, purchase results and the
     * `Purchases-CustomerInfoUpdated` listener all go through it. Patching the
     * return value covers every path at once.
     */
    private fun HookScope.installLifetime() {
        val mapper = classOrNull(CUSTOMER_INFO_MAPPER)
            ?: error("$CUSTOMER_INFO_MAPPER not found")

        val map = mapper.findMethod {
            name("map")
            paramCount(1)
            isStatic()
            returnTypeExtendsFrom(Map::class.java)
        }

        map.createAfterHook("paisa.lifetime.mapper") { param ->
            val original = param.result as? Map<*, *>
            param.result = CustomerInfoPatch.patch(original)
        }
        log.i("patched ${mapper.simpleName}.map")
    }

    /**
     * The success path only fires when the SDK actually produced a CustomerInfo.
     * Offline on a cold start there is no cache and no network, so the error
     * callback runs instead and the app falls back to free. Redirect that
     * callback to `onReceived` with a synthetic payload.
     */
    private fun HookScope.installOfflineFallback() {
        val errorCallback = classOrNull(GET_CUSTOMER_INFO_ERROR_CB)
            ?: error("$GET_CUSTOMER_INFO_ERROR_CB not found")
        val onResultInterface = classOrNull(ON_RESULT)
            ?: error("$ON_RESULT not found")

        val onReceived = onResultInterface.findMethod {
            name("onReceived")
            paramCount(1)
        }

        // invoke(PurchasesError)V, not the synthetic invoke(Object)Object bridge.
        val invokeMethod = errorCallback.findMethod {
            name("invoke")
            paramCount(1)
            voidReturnType()
        }

        invokeMethod.createReplaceHook("paisa.lifetime.offline") { param ->
            val onResult = param.thisObject.getFieldOrNull("\$onResult")
            if (onResult == null) {
                log.w("offline fallback: \$onResult missing, letting the error through")
                null
            } else {
                log.i("RevenueCat lookup failed, answering with a synthetic entitlement")
                onReceived.invoke(onResult, CustomerInfoPatch.synthetic())
                null
            }
        }
        log.i("patched getCustomerInfo error path")
    }

    /**
     * PairIP has two entry points and they do not share code: the wrapped
     * `Application.attachBaseContext` calls the static `checkLicense`, while
     * `LicenseContentProvider.onCreate` constructs a client and calls
     * `initializeLicenseCheck` directly. Both have to go.
     */
    private fun HookScope.installPairipBypass() {
        val client = classOrNull(PAIRIP_LICENSE_CLIENT)
        if (client == null) {
            log.i("no PairIP in this build, nothing to disable")
            return
        }

        var patched = 0
        client.findMethodOrNull {
            name("checkLicense")
            paramCount(1)
            isStatic()
        }?.let {
            it.createReplaceHook("paisa.pairip.check") { null }
            patched++
        }
        client.findMethodOrNull {
            name("initializeLicenseCheck")
            noParams()
        }?.let {
            it.createReplaceHook("paisa.pairip.init") { null }
            patched++
        }

        if (patched == 0) error("PairIP present but neither entry point matched")
        log.i("PairIP disabled ($patched entry point(s))")
    }

    private fun HookScope.installCustomerInfoLog() {
        val mapper = classOrNull(CUSTOMER_INFO_MAPPER) ?: return
        val map = mapper.findMethod {
            name("map")
            paramCount(1)
            isStatic()
            returnTypeExtendsFrom(Map::class.java)
        }
        // Lower priority than the patch hook, so "after" sees the final value.
        map.createAfterHook("paisa.log.customerinfo", priority = -100) { param ->
            log.i("customer info -> ${CustomerInfoPatch.describe(param.result as? Map<*, *>)}")
        }
    }

    private fun HookScope.installNativeFileLog() {
        if (!NativeHook.isAvailable) {
            error("native layer unavailable: ${NativeHook.lastError}")
        }
        NativeHook.setVerbose(true)
        NativeHook.setOpenatFilters("revenuecat", "shared_prefs", "paisa", "objectbox")
        if (!NativeHook.install("openat_logger")) {
            error("openat_logger did not install")
        }
        log.i("native file logger active")
    }

    private companion object {
        const val CUSTOMER_INFO_MAPPER =
            "com.revenuecat.purchases.hybridcommon.mappers.CustomerInfoMapperKt"
        const val GET_CUSTOMER_INFO_ERROR_CB =
            "com.revenuecat.purchases.hybridcommon.CommonKt\$getCustomerInfo\$1"
        const val ON_RESULT = "com.revenuecat.purchases.hybridcommon.OnResult"
        const val PAIRIP_LICENSE_CLIENT = "com.pairip.licensecheck.LicenseClient"
    }
}
