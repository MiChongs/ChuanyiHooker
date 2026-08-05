package com.chuanyi.hooker.hookers.hills

import com.chuanyi.hooker.core.HookScope
import com.chuanyi.hooker.nativehook.NativeHook

/**
 * Finds the two Dart-side constants this hooker has to rewrite, by scanning the
 * live heap for their *shape* rather than for a value baked in at build time.
 *
 * Neither of them is reachable any other way. Both live in
 * `_kDartIsolateSnapshotData`, get deserialized into the heap at isolate start,
 * and belong to an `--obfuscate`d build where no name survives. Writing the
 * literals into the module works exactly until the app ships a new endpoint or
 * rotates its key — and the failure is silent, because a needle that matches
 * nothing is indistinguishable from a scan that ran too early.
 *
 * What is searched for instead cannot change without changing what the strings
 * *are*: a URL still starts with `https://`, a PEM still starts with
 * `-----BEGIN PUBLIC KEY-----`. [NativeHook.findAscii] returns the whole run
 * from there to the first non-text byte, which on a Dart target is the exact end
 * of the string — the following cluster's length marker is `(len<<1)|0x80`, so
 * its top bit is always set.
 *
 * Both results are memoised: the scan walks every anonymous mapping in the
 * process, and these values do not change once the isolate is up.
 */
internal object HillsHeap {

    /** Nothing sane is longer than this, and it caps the per-hit read. */
    private const val MAX_URL = 512
    private const val MAX_PEM = 4096

    private const val PEM_BEGIN = "-----BEGIN PUBLIC KEY-----"

    /**
     * Words that mark a URL as the purchase-verification endpoint, and what each
     * is worth.
     *
     * Scoring rather than matching: the app talks to three endpoints under the
     * same host — `check-update`, `google-get-discount` and the verification one
     * — so "belongs to the backend" does not narrow it down. Only the last is
     * about a purchase *and* about verifying it, and a rename inside that theme
     * (`verify-purchase-v2`, `validate-purchase`) still scores highest.
     */
    private val URL_SIGNALS = listOf(
        "verify" to 3,
        "validate" to 3,
        "purchase" to 3,
        "receipt" to 2,
        "subscription" to 1,
        "google" to 1,
        "billing" to 1,
        "iap" to 1,
    )

    /** Anything scoring below this is not worth redirecting. */
    private const val MIN_SCORE = 4

    @Volatile
    private var url: String? = null

    @Volatile
    private var pem: String? = null

    /**
     * The purchase-verification endpoint, or null while the isolate has not
     * deserialized it yet.
     *
     * `verify_url_match` pins the choice to a URL containing that substring, for
     * the case where a future build makes the scoring pick the wrong one; it
     * still comes out of the heap, so the length is right by construction.
     */
    fun verifyUrl(scope: HookScope): String? {
        url?.let { return it }

        val pinned = scope.string(KEY_URL_MATCH)?.takeIf { it.isNotBlank() }
        val candidates = NativeHook.findAscii("https://", minLength = 12, maxLength = MAX_URL)
            .mapNotNull(::sanitiseUrl)
            .distinct()
        if (candidates.isEmpty()) return null

        val chosen = if (pinned != null) {
            candidates.firstOrNull { it.contains(pinned, ignoreCase = true) }
                ?: run {
                    scope.log.w("no URL in memory contains '$pinned', falling back to scoring")
                    bestByScore(scope, candidates)
                }
        } else {
            bestByScore(scope, candidates)
        }

        if (chosen == null) {
            // Worth seeing in full: it is the difference between "scanned too
            // early" and "the endpoint no longer looks like a purchase check".
            scope.log.d("no verification endpoint among ${candidates.size} URL(s): $candidates")
            return null
        }
        url = chosen
        scope.log.i("verification endpoint found in memory: $chosen (${chosen.length}B)")
        return chosen
    }

    /** The public key the app validates responses against, PEM text as stored. */
    fun publicKeyPem(scope: HookScope): String? {
        pem?.let { return it }

        val found = NativeHook.findAscii(PEM_BEGIN, minLength = 100, maxLength = MAX_PEM)
            .mapNotNull(::sanitisePem)
        if (found.isEmpty()) return null

        if (found.size > 1) {
            // More than one key means signing against the wrong half is possible,
            // so say which was taken instead of silently picking.
            scope.log.w("${found.size} public keys in memory, taking the first (${found.map { it.length }}B)")
        }
        val chosen = found.first()
        pem = chosen
        scope.log.i("response signing key found in memory (${chosen.length}B)")
        return chosen
    }

    // -----------------------------------------------------------------------

    /**
     * Highest-scoring candidate, and of the equally-good ones the shortest.
     *
     * The shortest rule is not a tiebreak, it is a correctness requirement, and
     * it has to be applied *after* scoring rather than to the raw candidate
     * list. On device the same endpoint came back three times — 56, 57 and 64
     * bytes, the longer two carrying a neighbour's first characters. A text run
     * ends at the first non-text byte, and while a snapshot always puts a
     * `(len<<1)|0x80` marker there, a *heap* neighbour is an object header whose
     * low bytes are sometimes printable. So a run can overshoot, never
     * undershoot, and the chosen string is fed straight back into an
     * equal-length overwrite: too long and the write runs past the end of the
     * string into whatever object follows.
     *
     * Collapsing prefixes across the *whole* list instead is what the first
     * attempt did, and it found nothing at all: the app also holds its bare API
     * origin, every endpoint under it starts with that, and so every endpoint
     * got thrown away. Restricting the rule to candidates that already look like
     * a verification endpoint keeps unrelated URLs from suppressing each other —
     * an origin scores zero and is never in the pool to begin with.
     */
    private fun bestByScore(scope: HookScope, candidates: List<String>): String? {
        val scored = candidates
            .map { it to score(it) }
            .filter { it.second >= MIN_SCORE }
        if (scored.isEmpty()) return null

        val top = scored.maxOf { it.second }
        val best = scored.filter { it.second == top }.map { it.first }
        if (best.size > 1) {
            scope.log.d("${best.size} equal candidates, taking the shortest: $best")
        }
        return best.minBy { it.length }
    }

    private fun score(candidate: String): Int {
        val lower = candidate.lowercase()
        // Only the path decides. A host called `verify.example.com` serving
        // everything would otherwise make every endpoint look like the one.
        val path = lower.substringAfter("://").substringAfter('/', "")
        if (path.isEmpty()) return 0
        return URL_SIGNALS.sumOf { (word, weight) -> if (path.contains(word)) weight else 0 }
    }

    /**
     * Trims a raw run down to the URL itself, or drops it.
     *
     * A text run is bounded by the *next object*, not by the end of the URL, so
     * on the rare occasion the neighbour starts with printable bytes the run
     * carries a tail. Cutting at the first character that cannot appear in a URL
     * removes it — and a run with no such character is already exact.
     */
    private fun sanitiseUrl(run: String): String? {
        val end = run.indexOfFirst { it !in URL_CHARS }
        val text = if (end < 0) run else run.substring(0, end)
        if (text.length < 12) return null
        // A bare origin is never the endpoint, and format strings ("https://%s")
        // are template text rather than a live URL.
        if (!text.substringAfter("://").contains('/')) return null
        if (text.contains('%') && text.contains("%s")) return null
        return text
    }

    /** Cuts the run at the end marker; anything after it belongs to a neighbour. */
    private fun sanitisePem(run: String): String? {
        val marker = run.indexOf(PEM_END)
        if (marker < 0) return null
        var end = marker + PEM_END.length
        // Keep a trailing newline if the app stores one: the replacement has to
        // reproduce the layout byte for byte, and that includes this.
        if (end < run.length && run[end] == '\r') end++
        if (end < run.length && run[end] == '\n') end++
        return run.substring(0, end)
    }

    private const val PEM_END = "-----END PUBLIC KEY-----"

    /** RFC 3986 unreserved + reserved, which is every byte a URL may contain. */
    private val URL_CHARS: Set<Char> =
        buildSet {
            ('a'..'z').forEach(::add)
            ('A'..'Z').forEach(::add)
            ('0'..'9').forEach(::add)
            "-._~:/?#[]@!$&'()*+,;=%".forEach(::add)
        }

    /** Optional setting: substring the verification endpoint must contain. */
    const val KEY_URL_MATCH = "verify_url_match"
}
