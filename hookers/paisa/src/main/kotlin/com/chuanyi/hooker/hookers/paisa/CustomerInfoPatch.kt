package com.chuanyi.hooker.hookers.paisa

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the customer-info payload the Flutter side expects.
 *
 * The RevenueCat Android SDK hands Dart a plain `Map` built by
 * `com.revenuecat.purchases.hybridcommon.mappers.*`. `purchases_flutter` parses
 * it with json_serializable, so a missing key throws and the whole CustomerInfo
 * fails to parse — worse than not patching at all. Every key and type below
 * mirrors the real mappers exactly:
 *
 *  * `CustomerInfoMapperKt.map(CustomerInfo)`
 *  * `EntitlementInfosMapperKt.map(EntitlementInfos)`
 *  * `EntitlementInfoMapperKt.map(EntitlementInfo)`
 *  * `TransactionMapperKt.map(Transaction)`
 *
 * Enum values are the Kotlin `.name()` spellings the SDK emits.
 *
 * Lifetime, in RevenueCat terms, is a non-subscription purchase: `expirationDate`
 * null, `willRenew` false, and an entry in `nonSubscriptionTransactions`.
 */
internal object CustomerInfoPatch {

    /** Entitlement id the app checks, plus aliases in case it looks for another. */
    private val ENTITLEMENT_IDS = listOf("paisa_pro", "premium", "pro")

    private const val PRODUCT_ID = "paisa_pro_lifetime"
    private const val TRANSACTION_ID = "paisa_pro_lifetime_tx"
    private const val USER_ID = "paisa_pro_lifetime"

    /** 2021-01-01T00:00:00Z — the purchase instant reported to the app. */
    private const val BOUGHT_MS = 1609459200000L

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun entitlement(id: String): Map<String, Any?> = linkedMapOf(
        "identifier" to id,
        "isActive" to true,
        "willRenew" to false,
        "periodType" to "NORMAL",
        "latestPurchaseDateMillis" to BOUGHT_MS,
        "latestPurchaseDate" to iso(BOUGHT_MS),
        "originalPurchaseDateMillis" to BOUGHT_MS,
        "originalPurchaseDate" to iso(BOUGHT_MS),
        // null expiration is what makes it lifetime rather than a subscription
        "expirationDateMillis" to null,
        "expirationDate" to null,
        "store" to "PLAY_STORE",
        "productIdentifier" to PRODUCT_ID,
        "productPlanIdentifier" to null,
        "isSandbox" to false,
        "unsubscribeDetectedAt" to null,
        "unsubscribeDetectedAtMillis" to null,
        "billingIssueDetectedAt" to null,
        "billingIssueDetectedAtMillis" to null,
        "ownershipType" to "PURCHASED",
        "verification" to "VERIFIED",
    )

    private fun transaction(): Map<String, Any?> = linkedMapOf(
        "transactionIdentifier" to TRANSACTION_ID,
        "revenueCatId" to TRANSACTION_ID,
        "productIdentifier" to PRODUCT_ID,
        "productId" to PRODUCT_ID,
        "purchaseDateMillis" to BOUGHT_MS,
        "purchaseDate" to iso(BOUGHT_MS),
    )

    @Suppress("UNCHECKED_CAST")
    private fun entitlements(original: Map<*, *>?): Map<String, Any?> {
        val all = LinkedHashMap<String, Any?>()
        val active = LinkedHashMap<String, Any?>()
        (original?.get("all") as? Map<String, Any?>)?.let(all::putAll)
        (original?.get("active") as? Map<String, Any?>)?.let(active::putAll)
        ENTITLEMENT_IDS.forEach { id ->
            val entry = entitlement(id)
            all[id] = entry
            active[id] = entry
        }
        return linkedMapOf(
            "all" to all,
            "active" to active,
            "verification" to "VERIFIED",
        )
    }

    /**
     * Takes whatever the real mapper produced and forces an active lifetime
     * entitlement into it. Pass null to synthesise a complete payload from
     * nothing — used when RevenueCat itself failed (offline, first launch, no
     * Play services).
     */
    @Suppress("UNCHECKED_CAST")
    fun patch(original: Map<*, *>?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        original?.forEach { (k, v) -> if (k is String) out[k] = v }

        out["entitlements"] = entitlements(out["entitlements"] as? Map<*, *>)

        val purchased = ArrayList<Any?>()
        (out["allPurchasedProductIdentifiers"] as? List<Any?>)?.let(purchased::addAll)
        if (PRODUCT_ID !in purchased) purchased += PRODUCT_ID
        out["allPurchasedProductIdentifiers"] = purchased

        val transactions = ArrayList<Any?>()
        (out["nonSubscriptionTransactions"] as? List<Any?>)?.let(transactions::addAll)
        transactions += transaction()
        out["nonSubscriptionTransactions"] = transactions

        val purchaseDates = LinkedHashMap<String, Any?>()
        (out["allPurchaseDates"] as? Map<String, Any?>)?.let(purchaseDates::putAll)
        purchaseDates[PRODUCT_ID] = iso(BOUGHT_MS)
        out["allPurchaseDates"] = purchaseDates

        val purchaseDatesMillis = LinkedHashMap<String, Any?>()
        (out["allPurchaseDatesMillis"] as? Map<String, Any?>)?.let(purchaseDatesMillis::putAll)
        purchaseDatesMillis[PRODUCT_ID] = BOUGHT_MS
        out["allPurchaseDatesMillis"] = purchaseDatesMillis

        // Only relevant when we are building the payload ourselves; putIfAbsent
        // keeps real values from the SDK untouched.
        val now = System.currentTimeMillis()
        out.putIfAbsent("activeSubscriptions", ArrayList<Any?>())
        out.putIfAbsent("latestExpirationDate", null)
        out.putIfAbsent("latestExpirationDateMillis", null)
        out.putIfAbsent("firstSeen", iso(BOUGHT_MS))
        out.putIfAbsent("firstSeenMillis", BOUGHT_MS)
        out.putIfAbsent("originalAppUserId", USER_ID)
        out.putIfAbsent("requestDate", iso(now))
        out.putIfAbsent("requestDateMillis", now)
        out.putIfAbsent("allExpirationDates", LinkedHashMap<String, Any?>())
        out.putIfAbsent("allExpirationDatesMillis", LinkedHashMap<String, Any?>())
        out.putIfAbsent("originalApplicationVersion", null)
        out.putIfAbsent("managementURL", null)
        out.putIfAbsent("originalPurchaseDate", iso(BOUGHT_MS))
        out.putIfAbsent("originalPurchaseDateMillis", BOUGHT_MS)
        out.putIfAbsent("subscriptionsByProductIdentifier", LinkedHashMap<String, Any?>())
        return out
    }

    /** Complete payload with no upstream data at all. */
    fun synthetic(): Map<String, Any?> = patch(null)

    /** Short description of what an entitlements map contains, for logging. */
    fun describe(map: Map<*, *>?): String {
        val entitlements = map?.get("entitlements") as? Map<*, *> ?: return "no entitlements"
        val active = (entitlements["active"] as? Map<*, *>)?.keys?.joinToString() ?: ""
        val all = (entitlements["all"] as? Map<*, *>)?.keys?.joinToString() ?: ""
        return "active=[$active] all=[$all]"
    }
}
