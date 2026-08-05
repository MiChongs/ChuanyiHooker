package com.chuanyi.hooker.hookers.yamby

import com.chuanyi.hooker.core.HookScope
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Everything this hooker knows about Yamby's Play Billing usage.
 *
 * Yamby ships Play Billing's *legacy AIDL* path: `queryPurchasesAsync` ends up
 * in `IInAppBillingService.getPurchases`, whose reply Bundle carries three
 * parallel lists plus a response code. The library then zips them into
 * [PURCHASE] objects and hands the result to the app's own listener.
 *
 * ```
 * getPurchases() -> Bundle { RESPONSE_CODE, INAPP_PURCHASE_ITEM_LIST,
 *                            INAPP_PURCHASE_DATA_LIST, INAPP_DATA_SIGNATURE_LIST }
 *   -> new Purchase(dataList[i], signatureList[i])
 *     -> listener.onQueryPurchasesResponse(billingResult, purchases)
 *       -> app flips to Pro and persists the entitlement
 * ```
 *
 * The app verifies nothing: no `SHA1withRSA` over the payload, no licence
 * server, no receipt round-trip. Whatever appears in that Bundle *is* the
 * entitlement — which is why [KEY_DATA_LIST] is the whole unlock, and why none
 * of the app's own nmmp-nativised classes have to be touched.
 */
internal object Billing {

    /** Kept by the billing library's consumer rules; not renamed by R8. */
    const val PURCHASE = "com.android.billingclient.api.Purchase"

    /**
     * The one-time product behind "Lifetime Pro", as Play reports it for
     * 2.0.5.5. Overridable through the `product_id` setting because a store
     * listing can be renamed without the APK changing.
     */
    const val PRODUCT_LIFETIME = "pro_lifetime_discount"

    // Bundle keys of the Play Billing AIDL reply. These are protocol, not app
    // code, so they survive every obfuscation pass.
    const val KEY_RESPONSE_CODE = "RESPONSE_CODE"
    const val KEY_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST"
    const val KEY_DATA_LIST = "INAPP_PURCHASE_DATA_LIST"
    const val KEY_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST"

    /** The three lists the library reads in lockstep — one entry each, always. */
    val PURCHASE_LISTS = setOf(KEY_ITEM_LIST, KEY_DATA_LIST, KEY_SIGNATURE_LIST)

    /**
     * The app's `PurchasesResponseListener`, as named in 2.0.5.5.
     *
     * R8 renamed it into Arabic presentation forms; written as escapes so the
     * source stays ASCII and cannot be mangled by an editor. Only [Announce]
     * needs it — the main unlock is name-free — and it is verified by shape and
     * overridable through the `listener_class` setting.
     */
    const val LISTENER = "sc.\u06E5\u06D6\u06EC\u06E5\u06DB\u06E6"

    /** MMKV is the app's whole preference layer; not renamed by R8. */
    const val MMKV = "com.tencent.mmkv.MMKV"

    /**
     * Where the app caches "the entitlement is good", in its `emby_setting`
     * store.
     *
     * Recovered from the Kotlin property metadata R8 leaves behind for
     * delegated properties: `getValidBefore()Z`, a `Boolean` defaulting to
     * false, sitting next to `lastTime` / `failedTime` — the usual
     * last-checked / last-failed pair of a cached licence verdict.
     *
     * It is written the moment Play reports the product as owned, and read on
     * every launch, which makes it the earliest point where Pro can be true.
     */
    const val ENTITLEMENT_KEY = "validBefore"

    /**
     * Play's own payload, field for field.
     *
     * `purchaseState` 0 is what Play writes for a completed purchase (the
     * library maps it to `PURCHASED`); `acknowledged` true keeps the app from
     * trying to acknowledge a token Play has never heard of.
     */
    fun purchaseJson(packageName: String, product: String): String = buildString {
        append('{')
        append("\"orderId\":\"GPA.0000-0000-0000-00000\",")
        append("\"packageName\":\"").append(packageName).append("\",")
        append("\"productId\":\"").append(product).append("\",")
        append("\"purchaseTime\":").append(PURCHASE_TIME_MS).append(',')
        append("\"purchaseState\":0,")
        append("\"purchaseToken\":\"chuanyi.").append(product).append("\",")
        append("\"quantity\":1,")
        append("\"acknowledged\":true,")
        append("\"autoRenewing\":false")
        append('}')
    }

    /** True when [dataList] already contains a purchase of [product]. */
    fun ownsProduct(dataList: Collection<*>?, product: String): Boolean =
        dataList?.any { it is String && it.contains("\"productId\":\"$product\"") } == true

    fun newPurchase(scope: HookScope, json: String): Any? = runCatching {
        val clazz = scope.classOrNull(PURCHASE) ?: return null
        clazz.getConstructor(String::class.java, String::class.java)
            .newInstance(json, "")
    }.getOrNull()

    // -----------------------------------------------------------------------
    // Shape-resolved plumbing for [Announce].

    /**
     * The listener's single delivery method: two parameters, the second a
     * [List]. It is `native` because nmmp compiled the body away — irrelevant
     * for calling it, which is all this hooker does.
     */
    fun deliveryMethod(listener: Class<*>): Method? =
        listener.declaredMethods.singleOrNull { m ->
            m.parameterCount == 2 && m.parameterTypes[1] == List::class.java
        }

    /**
     * Builds an OK `BillingResult`.
     *
     * R8 elided the class's own constructor — the builder `new`s it and calls
     * `Object.<init>` directly — so it cannot be instantiated reflectively.
     * The builder is reachable by shape instead: `BillingResult` exposes one
     * static no-arg factory, and the object it returns has one no-arg method
     * that hands a `BillingResult` back, plus the int/String pair to fill in.
     */
    fun okResult(resultClass: Class<*>): Any? = runCatching {
        val factory = resultClass.declaredMethods.singleOrNull { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterCount == 0 &&
                m.returnType != resultClass &&
                !m.returnType.isPrimitive &&
                m.returnType != String::class.java
        } ?: return null
        factory.isAccessible = true
        val builder = factory.invoke(null) ?: return null

        val builderClass = builder.javaClass
        builderClass.declaredFields.forEach { field ->
            field.isAccessible = true
            when (field.type) {
                Int::class.javaPrimitiveType -> field.setInt(builder, 0)
                String::class.java -> field.set(builder, "")
            }
        }

        val build = builderClass.declaredMethods.singleOrNull { m ->
            m.parameterCount == 0 && m.returnType == resultClass
        } ?: return null
        build.isAccessible = true
        build.invoke(builder)
    }.getOrNull()

    /** Fixed so a cold start does not look like a purchase made seconds ago. */
    private const val PURCHASE_TIME_MS = 1700000000000L
}
