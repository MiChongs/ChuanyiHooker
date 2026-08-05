package com.chuanyi.hooker.hookers.hills

import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Redirects the purchase-verification call and takes over the key its answer is
 * validated with, both inside the live Dart heap.
 *
 * The observed failure mode is that the unlock takes, then reverts about a
 * second later: the grant comes from the local purchase list, the revocation
 * from the server's verdict on a token Google never issued. Measured on device,
 * the request goes out six milliseconds after the purchase is announced.
 *
 * The rewrite is inherently a race: the app connects its billing client, queries
 * its purchases and posts the first verification some thirty milliseconds later,
 * while a full scan of the process's mappings takes a few hundred. It only ever
 * decides once, so losing that race means losing outright. [awaitReady] is how
 * the billing hooks hold the purchase list back until this has landed.
 *
 * Pointing the URL at a dead port does not help — a request that throws reads to
 * the app as "could not verify", which revokes exactly like a negative verdict
 * does. The replacement therefore has to be something that answers; that is
 * [VerifyServer]. Its answer is only believed if it carries the right RS256
 * signature, hence the second swap.
 *
 * Blocking the host instead is not an option either — the same host serves
 * `check-update`, and the app will not start without it. Only a URL swap has the
 * granularity to cut this single path.
 *
 * Both strings live in `_kDartIsolateSnapshotData`, a serialized cluster stream
 * (payloads carry a `(len<<1)|0x80` length marker, not an object header) that is
 * deserialized into the heap at isolate start. Patching the mapped `.so` at
 * runtime is therefore useless; patching the heap copy works, and because each
 * replacement is the same length as what it replaces it never touches the length
 * marker — no object-pool walk, no code offset, unaffected by `--obfuscate`.
 *
 * Neither value is known ahead of time. [HillsHeap] finds them by shape on the
 * same pass, which is also why discovery lives inside the retry ladder rather
 * than before it: until the isolate has deserialized, there is nothing to find.
 */
internal object VerifyBreaker {

    /**
     * The isolate has to deserialize before the strings exist in the heap. The
     * first attempts are close together because the app queries and verifies its
     * purchases within tens of milliseconds of the billing client connecting,
     * and [awaitReady] holds that answer back until this is done.
     */
    private val RETRY_DELAYS_MS = longArrayOf(150, 250, 400, 1_000, 2_000, 5_000, 10_000, 20_000, 40_000)

    private val started = AtomicBoolean(false)

    /** Opened once both swaps land, or once the retries are exhausted. */
    private val ready = CountDownLatch(1)

    /**
     * The endpoint as the app originally had it, once discovered.
     *
     * [VerifyServer] forwards to it for requests it cannot answer itself, which
     * is the only remaining reference to the real backend anywhere in the module.
     */
    @Volatile
    var upstream: String? = null
        private set

    /** True between [start] and the swaps landing — the window [awaitReady] covers. */
    val isPending: Boolean get() = started.get() && ready.count > 0L

    /**
     * Blocks until the rewrite has landed, or [timeoutMs] elapses.
     *
     * Returns false on timeout, in which case the caller should carry on rather
     * than stall the app: a late answer is better than none.
     */
    fun awaitReady(timeoutMs: Long): Boolean =
        !started.get() || ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun start(scope: HookScope) {
        if (!NativeHook.isAvailable) {
            error("native layer unavailable: ${NativeHook.lastError}")
        }
        if (!started.compareAndSet(false, true)) return

        // Off the main thread: each pass walks every anonymous mapping, which is
        // hundreds of MB in a Flutter process.
        Thread({ run(scope) }, "hills-verify-breaker").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun run(scope: HookScope) {
        var keySwapped = false
        var urlSwapped = false
        // Set once minting a stand-in has been shown to be impossible for this
        // key — a non-RSA algorithm, or a PEM whose length cannot be matched.
        // Retrying would burn a fresh RSA keygen per round to reach the same
        // answer, and the URL half still has work to do.
        var keyHopeless = false

        for (delay in RETRY_DELAYS_MS) {
            runCatching { Thread.sleep(delay) }.onFailure { return }

            if (!keySwapped && !keyHopeless) {
                when (swapKey(scope, delay)) {
                    KeySwap.DONE -> keySwapped = true
                    KeySwap.IMPOSSIBLE -> keyHopeless = true
                    KeySwap.RETRY -> Unit
                }
            }

            // Strictly after the key, and not merely "first in the loop body":
            // the two strings appear in the heap at different times, so on the
            // pass where the URL is already there and the PEM is not, redirecting
            // would hand the app a request nothing can answer. Measured on
            // device, that is not hypothetical — the app posts its first check
            // within milliseconds of the swap, the local server has to forward
            // it upstream, and the real backend's "isValid: false" is precisely
            // the verdict this exists to avoid. Leaving the URL alone for one
            // more round costs nothing: the app was going to reach that same
            // backend anyway.
            if (!urlSwapped && (keySwapped || keyHopeless)) urlSwapped = swapUrl(scope, delay)

            if ((keySwapped || keyHopeless) && urlSwapped) {
                ready.countDown()
                if (keyHopeless) {
                    scope.log.w("redirect is in place but responses cannot be signed for")
                }
                return
            }
        }

        // Not fatal on its own: whichever half landed still does its job, and a
        // build that dropped one of the strings simply keeps its own behaviour
        // for that half.
        if (!keySwapped) scope.log.w("response signing key never showed up in memory")
        if (!urlSwapped) scope.log.w("verification endpoint never showed up in memory")
        // Release anyone waiting either way — stalling the app is worse.
        ready.countDown()
        started.set(false)
    }

    private enum class KeySwap {
        /** Written over; nothing more to do. */
        DONE,

        /** Not there yet, or not writable yet. Worth another round. */
        RETRY,

        /** The key cannot be stood in for at all. Stop asking. */
        IMPOSSIBLE,
    }

    /** Finds the app's public key, mints a same-length stand-in, writes it over. */
    private fun swapKey(scope: HookScope, delay: Long): KeySwap {
        val appPem = HillsHeap.publicKeyPem(scope) ?: run {
            scope.log.d("verification key not in memory yet (${delay}ms)")
            return KeySwap.RETRY
        }
        // adopt() reports its own reason. It depends only on the PEM, which does
        // not change once found, so a failure here is final.
        val ours = VerifyToken.adopt(scope, appPem) ?: return KeySwap.IMPOSSIBLE

        val written = NativeHook.replaceAscii(appPem, ours)
        if (written <= 0) {
            scope.log.d("public key found but not writable yet (${delay}ms)")
            return KeySwap.RETRY
        }
        scope.log.i("response signing key replaced in $written place(s)")
        return KeySwap.DONE
    }

    /**
     * Finds the endpoint, brings up something that answers on the same number of
     * bytes, and writes it over.
     *
     * The local server binds before the swap, never after: a URL pointing at a
     * closed port reads to the app as a failed check, which revokes.
     */
    private fun swapUrl(scope: HookScope, delay: Long): Boolean {
        val original = HillsHeap.verifyUrl(scope) ?: run {
            scope.log.d("verification endpoint not in memory yet (${delay}ms)")
            return false
        }
        upstream = original

        val replacement = replacementFor(scope, original) ?: return false
        val written = NativeHook.replaceAscii(original, replacement)
        if (written <= 0) {
            scope.log.d("endpoint found but not writable yet (${delay}ms)")
            return false
        }
        scope.log.i("verification endpoint rewritten in $written place(s) -> $replacement")
        return true
    }

    /**
     * What to write over the endpoint: an explicit `verify_url` when one is
     * stored and it fits, otherwise the in-process server.
     *
     * The setting is for pointing the check at something else entirely. It has
     * to be exactly as long as what it replaces, and there is nothing sensible
     * to do about one that is not — padding someone's URL would change where it
     * points.
     */
    private fun replacementFor(scope: HookScope, original: String): String? {
        val configured = scope.string(KEY_VERIFY_URL)?.takeIf { it.isNotBlank() }
        if (configured != null) {
            if (configured.length == original.length) return configured
            scope.log.w(
                "'$KEY_VERIFY_URL' is ${configured.length}B but the endpoint is " +
                    "${original.length}B; using the local server instead",
            )
        }
        return VerifyServer.start(scope, original.length)
            ?: run {
                scope.log.e("cannot bind a local endpoint ${original.length}B long")
                null
            }
    }

    /** Optional setting: an endpoint to redirect to instead of the local server. */
    const val KEY_VERIFY_URL = "verify_url"
}
