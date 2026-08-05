package com.chuanyi.hooker.hookers.hills

import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.core.findConstructor
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Everything needed to make `in_app_purchase_android` report an owned lifetime
 * product, built out of the target's own classes so the pigeon payload is
 * field-for-field what Dart expects.
 */
internal object Billing {

    /**
     * Stable so Dart-side de-duplication by token behaves.
     *
     * Which product the token is claimed for is not decided here — see [Skus],
     * which learns it from the app instead of naming it.
     */
    private const val TOKEN = "chuanyi-hooker-lifetime-000000000000000000"

    /**
     * `com.android.billingclient.api.Purchase`.
     *
     * The one name still written out: Play Billing ships consumer keep rules
     * that pin its public API, and there is no channel string or JSON key to
     * anchor a search on. Everything else goes through [HillsDex].
     */
    const val PURCHASE = "com.android.billingclient.api.Purchase"

    /** `MethodCallHandlerImpl` keeps the pigeon callback interface here. */
    const val CALLBACK_FIELD = "callbackApi"

    /**
     * Play Billing v5+ purchase JSON.
     *
     * `Translator.fromPurchase` reads `productIds` first and falls back to
     * `productId`, and maps any `purchaseState` other than 4 to PURCHASED.
     * `acknowledged` is true so Dart never tries to acknowledge a token that
     * does not exist, and `autoRenewing` is false because a lifetime unlock is a
     * one-time product rather than a subscription.
     */
    private fun purchaseJson(packageName: String, sku: String): String = buildString {
        append("{")
        append("\"orderId\":\"GPA.0000-0000-0000-00000\",")
        append("\"packageName\":\"").append(packageName).append("\",")
        append("\"productId\":\"").append(sku).append("\",")
        append("\"productIds\":[\"").append(sku).append("\"],")
        append("\"purchaseTime\":1700000000000,")
        append("\"purchaseState\":0,")
        append("\"purchaseToken\":\"").append(TOKEN).append("\",")
        append("\"quantity\":1,")
        append("\"acknowledged\":true,")
        append("\"autoRenewing\":false,")
        append("\"developerPayload\":\"\"")
        append("}")
    }

    /** `new com.android.billingclient.api.Purchase(originalJson, signature)`. */
    fun fakePurchase(scope: HookScope, sku: String): Any? = runCatching {
        val clazz = scope.classOrNull(PURCHASE) ?: return null
        clazz.findConstructor { params(String::class.java, String::class.java) }
            .apply { isAccessible = true }
            .newInstance(purchaseJson(scope.packageName, sku), "")
    }.onFailure { scope.log.e("cannot build a synthetic Purchase", it) }.getOrNull()

    /** One `Messages$PlatformPurchase`, converted by the app's own Translator. */
    fun platformPurchase(scope: HookScope, sku: String): Any? = runCatching {
        val translator = HillsDex.translator(scope) ?: return null
        val purchase = fakePurchase(scope, sku) ?: return null
        val fromPurchase = translator.findMethod {
            name("fromPurchase")
            paramCount(1)
            isStatic()
        }
        fromPurchase.isAccessible = true
        fromPurchase.invoke(null, purchase)
    }.onFailure { scope.log.e("cannot convert the synthetic Purchase", it) }.getOrNull()

    /** `Messages$PlatformBillingResult` with responseCode = OK. */
    fun okBillingResult(scope: HookScope): Any? = runCatching {
        val responseEnum = HillsDex.pigeon(scope, "PlatformBillingResponse") ?: return null
        val ok = responseEnum.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "OK" }
            ?: return null

        val builderClass = HillsDex.pigeonBuilder(scope, "PlatformBillingResult") ?: return null
        val builder = builderClass.findConstructor { noParams() }
            .apply { isAccessible = true }
            .newInstance()

        builderClass.findMethod { name("setResponseCode"); paramCount(1) }
            .invoke(builder, ok)
        builderClass.findMethod { name("setDebugMessage"); paramCount(1) }
            .invoke(builder, "")
        builderClass.findMethod { name("build"); noParams() }.invoke(builder)
    }.onFailure { scope.log.e("cannot build an OK BillingResult", it) }.getOrNull()

    /**
     * A whole `Messages$PlatformPurchasesResponse` holding just the synthetic
     * purchase. Built from `fromPurchase` rather than `fromPurchasesList` so it
     * does not re-enter the hook that injects into that list.
     */
    fun purchasesResponse(scope: HookScope, sku: String): Any? = runCatching {
        val builderClass = HillsDex.pigeonBuilder(scope, "PlatformPurchasesResponse") ?: return null
        val billingResult = okBillingResult(scope) ?: return null
        val purchase = platformPurchase(scope, sku) ?: return null

        val builder = builderClass.findConstructor { noParams() }
            .apply { isAccessible = true }
            .newInstance()

        builderClass.findMethod { name("setBillingResult"); paramCount(1) }
            .invoke(builder, billingResult)
        builderClass.findMethod { name("setPurchases"); paramCount(1) }
            .invoke(builder, listOf(purchase))
        builderClass.findMethod { name("build"); noParams() }.invoke(builder)
    }.onFailure { scope.log.e("cannot build a PurchasesResponse", it) }.getOrNull()

    /**
     * A `Messages$PlatformBillingConfigResponse` that says the lookup worked.
     *
     * Only ever used to replace an error. The country is the device's own —
     * nothing downstream of this hooker reads it, but a plausible value is
     * cheaper than a wrong one if some build starts to.
     */
    fun billingConfig(scope: HookScope): Any? = runCatching {
        val builderClass = HillsDex.pigeonBuilder(scope, "PlatformBillingConfigResponse")
            ?: return null
        val billingResult = okBillingResult(scope) ?: return null

        val builder = builderClass.findConstructor { noParams() }
            .apply { isAccessible = true }
            .newInstance()

        builderClass.findMethod { name("setBillingResult"); paramCount(1) }
            .invoke(builder, billingResult)
        builderClass.findMethod { name("setCountryCode"); paramCount(1) }
            .invoke(builder, Locale.getDefault().country.ifEmpty { "US" })
        builderClass.findMethod { name("build"); noParams() }.invoke(builder)
    }.onFailure { scope.log.e("cannot build a BillingConfigResponse", it) }.getOrNull()

    /** Forces `billingResult` on an already-built response to OK. */
    fun forceOk(scope: HookScope, response: Any?): Any? {
        if (response == null) return null
        val billingResult = HillsDex.pigeon(scope, "PlatformBillingResult") ?: return response
        runCatching {
            response.javaClass
                .findMethodOrNull { name("setBillingResult"); params(billingResult) }
                ?.invoke(response, okBillingResult(scope))
        }
        return response
    }

    /** A `Messages$VoidResult` that swallows both outcomes. */
    fun voidResult(scope: HookScope): Any? {
        val clazz = HillsDex.pigeon(scope, "VoidResult") ?: return null
        return Proxy.newProxyInstance(scope.classLoader, arrayOf(clazz)) { _, method, _ ->
            if (method.declaringClass == Any::class.java) null else null
        }
    }

    /**
     * Wraps a pigeon `Messages$Result`.
     *
     * The billing plugin reports both synchronous problems (`billingClient` is
     * null) and asynchronous ones (Play answered SERVICE_UNAVAILABLE) through the
     * same callback, so intercepting the callback covers both without having to
     * patch each call site.
     *
     * @param onSuccess receives the original value, returns what to forward
     * @param onError   returns a replacement to forward through `success`, or
     *                  null to let the error through untouched
     * @param dispatch  runs the forwarding; the default runs it inline. Used to
     *                  hold an answer back until something else is in place —
     *                  pigeon does not care when the reply comes, only that it
     *                  comes once.
     */
    fun wrapResult(
        scope: HookScope,
        real: Any,
        onSuccess: (Any?) -> Any?,
        onError: (Any?) -> Any?,
        dispatch: (() -> Unit) -> Unit = { it() },
    ): Any {
        val resultClass = HillsDex.pigeon(scope, "Result") ?: return real
        val successMethod = resultClass.methods
            .firstOrNull { it.name == "success" && it.parameterCount == 1 }
            ?: return real

        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            when {
                method.name == "success" && args?.size == 1 -> {
                    // Never let our own bookkeeping break the app's billing flow.
                    val value = runCatching { onSuccess(args[0]) }
                        .onFailure { scope.log.e("success() rewrite failed, forwarding original", it) }
                        .getOrDefault(args[0])
                    dispatch { method.invoke(real, value) }
                    null
                }

                method.name == "error" && args?.size == 1 -> {
                    val replacement = runCatching { onError(args[0]) }
                        .onFailure { scope.log.e("error() rewrite failed, forwarding original", it) }
                        .getOrNull()
                    dispatch {
                        if (replacement == null) method.invoke(real, *args)
                        else successMethod.invoke(real, replacement)
                    }
                    null
                }

                args == null -> method.invoke(real)
                else -> method.invoke(real, *args)
            }
        }
        return Proxy.newProxyInstance(scope.classLoader, arrayOf(resultClass), handler)
    }

    /**
     * True when this list of `Purchase` already carries [sku].
     *
     * Reading `toString()` rather than the JSON: a real Purchase prints its
     * original JSON, and the check only has to be good enough to avoid
     * duplicating a genuine entitlement.
     */
    fun containsSku(purchases: Any?, sku: String): Boolean {
        val list = purchases as? List<*> ?: return false
        return list.any { it != null && it.toString().contains(sku) }
    }
}
