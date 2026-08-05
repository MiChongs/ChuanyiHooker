package com.chuanyi.hooker.hookers.hills

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.chuanyi.hooker.core.HookScope
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Locates the Java classes this hooker hooks, by what they *do* rather than by
 * what they are called.
 *
 * On the build this was written against every one of them still has its real
 * name — the Flutter plugins ship their own keep rules and the app does not
 * obfuscate its models. That is luck, not a guarantee, and it is the kind of
 * luck that runs out in a point release: R8 renames a class, `Class.forName`
 * returns null, and the feature reports "not found" with nothing to go on.
 *
 * What cannot be renamed is a pigeon channel name. Dart and Java both hold the
 * same literal — `dev.flutter.pigeon.<plugin>.<Api>.<method>` — and the two
 * sides only talk if the strings match exactly, so R8 has to leave them alone.
 * Everything below hangs off one of those:
 *
 * ```
 *   "…in_app_purchase_android.InAppPurchaseApi.queryPurchasesAsync"
 *        -> Messages$InAppPurchaseApi        (the class holding the string)
 *        -> Messages                          (its enclosing name)
 *        -> MethodCallHandlerImpl             (the only implementor)
 *        -> Translator                        (same package, static List->List)
 *
 *   "…shared_preferences_android.SharedPreferencesAsyncApi.setBool"
 *        -> SharedPreferencesAsyncApi -> SharedPreferencesBackend
 * ```
 *
 * The app's own `PlayerConfig` has no channel to hang off, but it is a Moshi
 * `@JsonClass`, and a generated adapter has to spell out every JSON key as a
 * string constant — `isPro` among them. Those are as unrenameable as a channel
 * name for the same reason: they are what the wire format *is*.
 *
 * Four levels, first hit wins:
 *
 *  1. a `dex.<key>` setting — fixes a wrong answer without a rebuild
 *  2. this process's answer, from the cache or an earlier scan
 *  3. the name as of the build this was written against, **if it still resolves**
 *  4. one DexKit pass over the APK
 *
 * The order of the last two is deliberate and is the opposite of what
 * [AstraFlowDex][com.chuanyi.hooker.hookers.astraflow] needs. There the target's
 * class is renamed on every build, so scanning is the normal path. Here nothing
 * is renamed yet, and a pass costs a few hundred milliseconds on the app's
 * startup path — spending that to confirm an answer a single `Class.forName`
 * already gave is pure loss. Trying the known name first means the scan is
 * dormant insurance: it runs only once a name has actually stopped resolving,
 * which is exactly when it is worth what it costs.
 *
 * A stale name cannot win by accident either. Level 3 is not "the name is
 * plausible", it is "a class by that name exists in this process", and after a
 * rename nothing answers to the old one.
 */
internal object HillsDex {

    // --- keys, also the suffix of the `dex.<key>` setting and the cache entry --
    const val HANDLER = "handler"
    const val TRANSLATOR = "translator"
    const val MESSAGES = "messages"
    const val PREFS_BACKEND = "prefs"
    const val PLAYER_CONFIG = "player_config"

    /**
     * The names as of Hills 1.7.2 (versionCode 4110), where nothing was renamed.
     *
     * Only reached when the scan finds nothing, which on a build that *has* been
     * renamed means these will not resolve either — that is the intended
     * outcome, since a wrong class is worse than an absent one.
     */
    private val PINNED = mapOf(
        HANDLER to "io.flutter.plugins.inapppurchase.MethodCallHandlerImpl",
        TRANSLATOR to "io.flutter.plugins.inapppurchase.Translator",
        MESSAGES to "io.flutter.plugins.inapppurchase.Messages",
        PREFS_BACKEND to "io.flutter.plugins.sharedpreferences.SharedPreferencesBackend",
        PLAYER_CONFIG to "com.mountains.player.models.PlayerConfig",
    )

    /**
     * Anchors. Each is a full pigeon channel name, which is a wire-protocol
     * constant rather than a symbol — Dart compares it byte for byte.
     */
    private const val IAP_ANCHOR =
        "dev.flutter.pigeon.in_app_purchase_android.InAppPurchaseApi.queryPurchasesAsync"
    private const val PREFS_ANCHOR =
        "dev.flutter.pigeon.shared_preferences_android.SharedPreferencesAsyncApi.setBool"

    /**
     * JSON keys of `PlayerConfig`, as its generated Moshi adapter spells them.
     *
     * Three rather than one: `isPro` alone appears in plenty of classes, and the
     * combination only appears in the adapter for this exact model.
     */
    private val PLAYER_CONFIG_ANCHOR = arrayOf("isPro", "danmuConfig", "subtitleStyle")

    private const val CACHE_FILE = "chuanyi_hooker_hills"
    private const val CACHE_VERSION = "dex.version"

    private val found = ConcurrentHashMap<String, String>()
    private val scanned = AtomicBoolean(false)

    /** `MethodCallHandlerImpl` — every pigeon billing call lands here. */
    fun handler(scope: HookScope): Class<*>? = resolve(scope, HANDLER)

    /** `Translator` — converts Play's objects into pigeon payloads. */
    fun translator(scope: HookScope): Class<*>? = resolve(scope, TRANSLATOR)

    /** `SharedPreferencesBackend` — the app's own settings, seen from Java. */
    fun prefsBackend(scope: HookScope): Class<*>? = resolve(scope, PREFS_BACKEND)

    /** The app's `PlayerConfig`, which carries `isPro` across the boundary. */
    fun playerConfig(scope: HookScope): Class<*>? = resolve(scope, PLAYER_CONFIG)

    /**
     * A nested pigeon type, e.g. `pigeon(scope, "PlatformBillingResult")`.
     *
     * The outer name is the discovered one, so a package move is handled; the
     * suffix is not, and does not need to be. R8 renames a whole class or none
     * of it — a build where `Messages$Result` became `a$b` is one where
     * `MethodCallHandlerImpl` is gone too, and nothing here would have resolved.
     */
    fun pigeon(scope: HookScope, simpleName: String): Class<*>? {
        val outer = name(scope, MESSAGES) ?: return null
        return scope.classOrNull("$outer\$$simpleName")
    }

    /** Same, for a type nested two deep (`PlatformBillingResult$Builder`). */
    fun pigeonBuilder(scope: HookScope, simpleName: String): Class<*>? =
        pigeon(scope, "$simpleName\$Builder")

    // -----------------------------------------------------------------------

    private fun resolve(scope: HookScope, key: String): Class<*>? =
        name(scope, key)?.let { scope.classOrNull(it) }

    /**
     * The four levels. Every one of them is confirmed by actually loading the
     * class, so a name that no longer resolves falls through instead of being
     * handed back.
     */
    private fun name(scope: HookScope, key: String): String? {
        scope.string("dex.$key")
            ?.takeIf { it.isNotBlank() && scope.classOrNull(it) != null }
            ?.let { return it }

        found[key]?.takeIf { scope.classOrNull(it) != null }?.let { return it }

        // Before paying for a scan, check whether there is anything to look for.
        PINNED[key]?.takeIf { scope.classOrNull(it) != null }?.let { return it }

        ensureScanned(scope)
        return found[key]?.takeIf { scope.classOrNull(it) != null }
    }

    /**
     * Runs the scan at most once per process.
     *
     * A pass costs a few hundred milliseconds and lands on the app's startup
     * path, so the answer is written to the target's own data dir and reused
     * until its versionCode changes. The module's remote preferences are
     * read-only from inside a hooked process, which is why the cache lives on
     * the target's side.
     */
    private fun ensureScanned(scope: HookScope) {
        if (!scanned.compareAndSet(false, true)) return
        scope.log.i("a class name stopped resolving, scanning the dex for the new ones")

        if (restore(scope)) {
            scope.log.d("class map restored from cache: $found")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        found.putAll(scan(scope))
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        if (found.isEmpty()) {
            // Not fatal: the pinned names still get tried. Worth a warning
            // because it means every later lookup is running on the fallback.
            scope.log.w("dex scan found nothing in ${elapsed}ms, using the pinned names")
            return
        }
        scope.log.i("dex scan resolved ${found.size} class(es) in ${elapsed}ms")
        PINNED.keys.forEach { key ->
            val hit = found[key]
            when {
                hit == null -> scope.log.w("  $key -> not found, pinned ${PINNED[key]}")
                hit != PINNED[key] -> scope.log.i("  $key -> $hit (was ${PINNED[key]})")
                else -> scope.log.d("  $key -> $hit")
            }
        }
        persist(scope)
    }

    private fun scan(scope: HookScope): Map<String, String> {
        val apkPath = scope.apkPath
        if (apkPath.isNullOrEmpty()) {
            scope.log.w("no APK path, skipping the dex scan")
            return emptyMap()
        }

        // Fails with UnsatisfiedLinkError when libdexkit.so has no build for
        // this ABI; the pinned names cover that.
        val bridge = runCatching { DexKitBridge.create(apkPath) }
            .onFailure { scope.log.w("DexKit cannot open $apkPath: ${it.message}") }
            .getOrNull() ?: return emptyMap()

        return bridge.use { dex ->
            val out = mutableMapOf<String, String>()
            runCatching { scanBilling(dex, out) }
                .onFailure { scope.log.w("billing class scan failed: ${it.message}") }
            runCatching { scanPrefs(dex, out) }
                .onFailure { scope.log.w("preferences class scan failed: ${it.message}") }
            runCatching { scanPlayerConfig(dex, out) }
                .onFailure { scope.log.w("PlayerConfig scan failed: ${it.message}") }
            out
        }
    }

    /**
     * The pigeon interface holds the channel string in its `setUp`; its
     * enclosing class is `Messages` and its single implementor is the handler.
     */
    private fun scanBilling(dex: DexKitBridge, out: MutableMap<String, String>) {
        val api = dex.findClass { matcher { usingStrings(IAP_ANCHOR) } }
            .firstOrNull { it.name.contains('$') } ?: return

        out[MESSAGES] = api.name.substringBeforeLast('$')

        dex.findClass { matcher { addInterface(api.name) } }
            .firstOrNull { it.name != api.name }
            ?.let { out[HANDLER] = it.name }

        // Translator is the only class in the plugin's package with a static
        // List -> List conversion; that is `fromPurchasesList`, which is the
        // funnel every purchase path goes through.
        val pkg = api.name.substringBeforeLast('.')
        dex.findClass {
            matcher {
                className(pkg, StringMatchType.StartsWith, false)
                addMethod {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.List"
                    paramTypes("java.util.List")
                }
            }
        }.firstOrNull()?.let { out[TRANSLATOR] = it.name }
    }

    private fun scanPrefs(dex: DexKitBridge, out: MutableMap<String, String>) {
        val api = dex.findClass { matcher { usingStrings(PREFS_ANCHOR) } }
            .firstOrNull { it.name.contains('$') } ?: return
        dex.findClass { matcher { addInterface(api.name) } }
            .firstOrNull { it.name != api.name }
            ?.let { out[PREFS_BACKEND] = it.name }
    }

    /**
     * Moshi's generated adapter names every JSON key as a string constant, and
     * its `fromJson` returns the model — so the model is reachable without
     * knowing what either of them is called.
     */
    private fun scanPlayerConfig(dex: DexKitBridge, out: MutableMap<String, String>) {
        val adapter = dex.findClass { matcher { usingStrings(*PLAYER_CONFIG_ANCHOR) } }
            .firstOrNull() ?: return
        dex.findMethod {
            matcher {
                declaredClass(adapter.name, StringMatchType.Equals, false)
                name("fromJson")
            }
        }
            // The bridge method returns Object; the real one returns the model.
            .map { it.returnTypeName }
            .firstOrNull { it != "java.lang.Object" }
            ?.let { out[PLAYER_CONFIG] = it }
    }

    // -----------------------------------------------------------------------

    private fun cache(scope: HookScope): SharedPreferences? =
        scope.appContextOrNull()?.let { context ->
            runCatching { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }.getOrNull()
        }

    /** True when a usable map for this exact app version was restored. */
    private fun restore(scope: HookScope): Boolean {
        val prefs = cache(scope) ?: return false
        if (prefs.getLong(CACHE_VERSION, Long.MIN_VALUE) != scope.versionCode) return false
        PINNED.keys.forEach { key ->
            prefs.getString("dex.$key", null)?.takeIf { it.isNotBlank() }?.let { found[key] = it }
        }
        return found.isNotEmpty()
    }

    private fun persist(scope: HookScope) {
        val prefs = cache(scope) ?: return
        runCatching {
            prefs.edit().apply {
                putLong(CACHE_VERSION, scope.versionCode)
                found.forEach { (key, value) -> putString("dex.$key", value) }
            }.apply()
        }
    }
}
