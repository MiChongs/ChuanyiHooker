package com.chuanyi.hooker.hookers.hills

import android.content.Context
import android.content.SharedPreferences
import com.chuanyi.hooker.core.HookScope
import io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Which product id stands for the lifetime unlock, learned from the app rather
 * than written down.
 *
 * The id is a Dart-side constant: it is not in the dex at all (`hills.pro` gets
 * zero hits there), it is not derivable from the package name, and on an
 * `--obfuscate`d build there is no symbol pointing at it. Hard-coding one means
 * the injection silently stops working the day the app renames its catalogue —
 * the synthetic purchase is still built, still handed to Dart, and simply does
 * not match anything.
 *
 * What the app cannot hide is which products it asks Play about. Every path goes
 * through pigeon:
 *
 * ```
 * queryProductDetailsAsync(List<PlatformQueryProduct>, Result<…>)
 * launchBillingFlow(PlatformBillingFlowParams, VoidResult)
 * ```
 *
 * and both carry the id in plain Java. Reading them there needs no class name of
 * the app's own and no offset.
 *
 * Timing is the one catch: an app usually queries its *purchases* at startup and
 * its *products* only when a store screen opens, so on a very first run the id
 * can arrive after the purchase list has already gone past. Two things cover
 * that — the value is cached per version in the target's own data dir, so it is
 * there from the start on every later launch, and [onLearned] lets the announce
 * path push the purchase the moment the id turns up.
 */
internal object Skus {

    /** Words that mark an id as a promo variant of the real product. */
    private val PROMO_MARKERS = listOf("discount", "promo", "sale", "trial", "off", "intro")

    /** Words that mark an id as the perpetual unlock rather than a lesser tier. */
    private val PERPETUAL_MARKERS = listOf("lifetime", "forever", "permanent", "onetime", "life_time")

    private const val CACHE_FILE = "chuanyi_hooker_hills"
    private const val KEY_VERSION = "sku.version"
    private const val KEY_PRODUCTS = "sku.inapp"

    /** Optional setting: pin the product id instead of learning it. */
    const val KEY_LIFETIME_SKU = "lifetime_sku"

    /** Every one-time product id the app has asked Play about, this run or before. */
    private val known = CopyOnWriteArrayList<String>()

    /** The id the app last opened the Play purchase sheet for, if it ever did. */
    private val purchased = AtomicReference<String?>(null)

    /** Set once the last run's catalogue has been read back, or proven absent. */
    private val restored = AtomicBoolean(false)

    /** Set once anything has been learned; only gates [onLearned], not the value. */
    private val announced = AtomicReference<String?>(null)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Starts recording the ids the app queries, and seeds the set from the last
     * run so a warm start knows the answer immediately.
     *
     * Safe to call whether or not any feature that consumes the id is enabled;
     * the hooks only read.
     */
    fun watch(scope: HookScope) {
        // Usually a no-op this early — see [restore].
        restore(scope)

        val handler = HillsDex.handler(scope) ?: run {
            scope.log.w("no pigeon billing handler, product ids cannot be learned")
            return
        }

        // The catalogue query: one PlatformQueryProduct per id, each carrying
        // INAPP or SUBS. A lifetime unlock is always INAPP.
        handler.findMethodOrNull { name("queryProductDetailsAsync"); paramCount(2) }
            ?.createAfterHook("hills.sku.query") { param ->
                val products = param.arg(0) as? List<*> ?: return@createAfterHook
                products.forEach { product -> record(scope, product) }
            }

        // The buy button. Rarer, but it names exactly the product the user is
        // being sold, which is the strongest signal there is.
        handler.findMethodOrNull { name("launchBillingFlow"); paramCount(2) }
            ?.createAfterHook("hills.sku.flow") { param ->
                val params = param.arg(0) ?: return@createAfterHook
                val id = runCatching {
                    params.javaClass.findMethodOrNull { name("getProduct"); noParams() }
                        ?.invoke(params) as? String
                }.getOrNull()
                if (!id.isNullOrBlank()) {
                    purchased.set(id)
                    scope.log.i("the app is selling $id, taking it over the queried ids")
                    remember(scope, id)
                }
            }

    }

    /**
     * The id to claim ownership of, or null while nothing is known yet.
     *
     * A stored `lifetime_sku` wins outright — it is the escape hatch for a
     * catalogue this cannot read correctly.
     */
    fun lifetime(scope: HookScope): String? {
        scope.string(KEY_LIFETIME_SKU)?.takeIf { it.isNotBlank() }?.let { return it }
        restore(scope)
        return best()
    }

    /**
     * Runs [action] once, as soon as any id is known — immediately if one
     * already is.
     *
     * Exists because the id can arrive minutes after the purchase list did, and
     * at that point re-announcing is the only way to still get the unlock in
     * without waiting for a restart.
     *
     * Deliberately passes nothing: ids arrive one per callback and the first is
     * not the best one — on device that fired with `hills.pro` a moment before
     * `hills.pro.lifetime` turned up. The callback re-reads [lifetime] instead,
     * so it always acts on the whole catalogue rather than on whichever entry
     * happened to unblock it.
     */
    fun onLearned(action: () -> Unit) {
        if (announced.get() != null) {
            action()
            return
        }
        listeners.add(action)
    }

    // -----------------------------------------------------------------------

    /**
     * Picks the product to claim out of everything the app queries.
     *
     * The catalogue this was validated against is
     * `hills.pro`, `hills.pro.lifetime`, `hills.pro.lifetime.discount3`, and it
     * is what ruled out the obvious rule. "The id the others are built on" picks
     * `hills.pro`, which is a *different, lesser* product — a shared prefix
     * marks a family, not the thing being sold. What is actually being sold is
     * the most specific entry that is not a promotion, so:
     *
     *  * a promo variant is pushed down;
     *  * an id that names a perpetual unlock is pulled up;
     *  * ties go to the longer id, because a catalogue narrows as it gets
     *    specific and the short one is the family root.
     *
     * The word lists are hints, not identity — [KEY_LIFETIME_SKU] is there for
     * the catalogue that defeats them, and [purchased] outranks all of this.
     */
    private fun best(): String? {
        // Whatever the user was actually taken to the Play sheet for. Nothing
        // inferred can beat the app naming the product itself.
        purchased.get()?.let { return it }

        return known.distinct().minWithOrNull(
            compareByDescending<String> { rank(it) }.thenByDescending { it.length },
        )
    }

    private fun rank(candidate: String): Int {
        var score = 0
        if (PROMO_MARKERS.any { candidate.contains(it, ignoreCase = true) }) score -= 3
        if (PERPETUAL_MARKERS.any { candidate.contains(it, ignoreCase = true) }) score += 2
        return score
    }

    private fun record(scope: HookScope, product: Any?) {
        if (product == null) return
        val type = runCatching {
            product.javaClass.findMethodOrNull { name("getProductType"); noParams() }
                ?.invoke(product)
        }.getOrNull()
        // A subscription is not what the lifetime unlock is; injecting one would
        // make the app expect a renewal it will never see.
        if ((type as? Enum<*>)?.name == "SUBS") return

        val id = runCatching {
            product.javaClass.findMethodOrNull { name("getProductId"); noParams() }
                ?.invoke(product) as? String
        }.getOrNull() ?: return
        remember(scope, id)
    }

    private fun remember(scope: HookScope, id: String) {
        if (id.isBlank() || id in known) return
        known.add(id)
        scope.log.i("learned one-time product id: $id")
        persist(scope)

        val chosen = best() ?: return
        // Fire once. A later, better id does not re-fire: the purchase has
        // already been announced and announcing a second is more likely to
        // confuse Dart than to help. Listeners re-read [lifetime] themselves,
        // so a better id arriving in the same burst is still picked up.
        if (announced.compareAndSet(null, chosen)) {
            listeners.forEach { listener -> runCatching { listener() } }
            listeners.clear()
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Cached in the *target's* data dir, not the module's: the module's remote
     * preferences are read-only from inside a hooked process.
     */
    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /**
     * Loads the last run's catalogue, retrying until a Context exists.
     *
     * At [PACKAGE_READY][com.chuanyi.hooker.core.AppHooker.Stage.PACKAGE_READY]
     * there is no Application yet, so the very first attempt — from [watch] —
     * always comes back empty. Left at that, the cache would never load at all
     * and every launch would relearn the catalogue from scratch, which is
     * exactly the window in which the purchase list goes past with only the
     * first id known. Retrying from [lifetime] closes it: by the time anything
     * asks, the app is running and its preferences are reachable.
     */
    private fun restore(scope: HookScope) {
        if (restored.get()) return
        val prefs = cache(scope) ?: return
        if (!restored.compareAndSet(false, true)) return

        // Keyed by version: a catalogue change ships with an app update, and a
        // stale id is worse than none because it looks like it worked.
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE) != scope.versionCode) return
        val cached = prefs.getStringSet(KEY_PRODUCTS, null)
            ?.filter { it.isNotBlank() }
            ?.filterNot { it in known }
            .orEmpty()
        if (cached.isEmpty()) return
        known.addAll(cached)
        scope.log.i("product ids from the last run: $cached")
    }

    private fun persist(scope: HookScope) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit()
                .putLong(KEY_VERSION, scope.versionCode)
                .putStringSet(KEY_PRODUCTS, known.toSet())
                .apply()
        }
    }
}
