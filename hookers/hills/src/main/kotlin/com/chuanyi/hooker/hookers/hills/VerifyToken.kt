package com.chuanyi.hooker.hookers.hills

import android.util.Base64
import com.chuanyi.hooker.core.HookScope
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

/**
 * The verification protocol, both directions.
 *
 * Captured off the device, the exchange is a pair of JWTs rather than a plain
 * JSON body. The app POSTs an empty body and puts everything in the bearer
 * token, signed HS256:
 *
 * ```
 * {"platform":"android","iat":…,"exp":…,"jti":"<uuid>",
 *  "data":{"purchases":[{"token":"…","productId":"…"}]}}
 * ```
 *
 * and the backend answers `{"token":"<RS256 JWT>"}` carrying:
 *
 * ```
 * {"platform":"android","iat":…,"exp":…,"req_jti":"<same uuid>",
 *  "data":{"success":true,"code":200,"message":"Success","isValid":false}}
 * ```
 *
 * `success` is about the call, `isValid` about the purchase — that single false
 * is the revocation. `success:true, isValid:false` is exactly what a token
 * Google never issued earns, and no amount of retrying changes it.
 *
 * The answer is RS256, so it cannot be forged against the server's key. It does
 * not have to be: the app validates against a public key it carries as plain PEM
 * text in the isolate snapshot, and that string can be swapped for one this
 * module holds the private half of — see [adopt].
 *
 * Nothing here is baked in. The app's key is found at runtime by [HillsHeap] and
 * the replacement pair is generated to match it, so a rotated key on the app's
 * side costs nothing: the next launch simply adopts the new one.
 */
internal object VerifyToken {

    /** The observed lifetime of a real answer. */
    private const val TTL_SECONDS = 300L

    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /**
     * A generated SPKI can come out a byte shorter than expected when the
     * modulus happens to have a leading zero. Cheaper to generate again than to
     * hand-pad the DER.
     */
    private const val KEYGEN_ATTEMPTS = 4

    /** The private half of whatever was swapped in, set by [adopt]. */
    @Volatile
    private var signingKey: KeyPair? = null

    val isReady: Boolean get() = signingKey != null

    /**
     * Builds a replacement for [appPem] whose bytes this module can sign for.
     *
     * The swap is an in-place overwrite of a live Dart string, so the result has
     * to be exactly as long as the original — which is why the key is generated
     * rather than carried: a fixed PEM only fits an app whose key happens to be
     * the same size and formatted the same way.
     *
     * Length equality is not assumed, it is checked. Both keys being the same
     * modulus size makes their base64 bodies the same length, and reproducing
     * the original's line width, line ending and trailing newline makes the rest
     * line up; if any of that fails to hold, this returns null and the caller
     * leaves memory alone rather than corrupting a neighbouring object.
     *
     * @return the PEM to write over [appPem], or null if it cannot be matched
     */
    fun adopt(scope: HookScope, appPem: String): String? {
        val layout = Layout.of(appPem) ?: run {
            scope.log.w("public key PEM has no recognisable layout, not replacing it")
            return null
        }

        val theirDer = runCatching { Base64.decode(layout.body, Base64.DEFAULT) }.getOrNull()
        if (theirDer == null || theirDer.isEmpty()) {
            scope.log.w("public key PEM body is not base64, not replacing it")
            return null
        }

        val bits = runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(theirDer))
            (key as RSAPublicKey).modulus.bitLength()
        }.getOrElse {
            // An EC or Ed25519 key cannot be answered for; the URL redirect on
            // its own would then produce a reply the app rejects.
            scope.log.w("the app's key is not RSA (${it.message}), cannot sign for it")
            return null
        }

        repeat(KEYGEN_ATTEMPTS) { attempt ->
            val pair = runCatching {
                KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
            }.getOrElse {
                scope.log.e("RSA-$bits key generation failed", it)
                return null
            }

            val ours = layout.render(Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            if (ours.length == appPem.length) {
                signingKey = pair
                scope.log.i("minted an RSA-$bits stand-in key (${ours.length}B, attempt ${attempt + 1})")
                return ours
            }
            scope.log.d("generated PEM is ${ours.length}B against ${appPem.length}B, retrying")
        }

        scope.log.w("could not produce a same-length RSA-$bits PEM, leaving the app's key alone")
        return null
    }

    /** Payload of a JWT, without checking its signature — we only need the claims. */
    fun claims(jwt: String?): JSONObject? = runCatching {
        val parts = jwt?.split('.') ?: return null
        if (parts.size < 2) return null
        JSONObject(String(Base64.decode(parts[1], B64), Charsets.UTF_8))
    }.getOrNull()

    /** The bearer token out of a request header list, or null. */
    fun bearer(headers: List<Pair<String, String>>): String? = headers
        .firstOrNull { it.first.equals("Authorization", ignoreCase = true) }
        ?.second
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()

    /**
     * The response body for [request], granting the purchase, or null when no
     * key has been adopted — in which case the caller should forward upstream
     * rather than send something the app will reject.
     *
     * `req_jti` is echoed back because the real backend does; a client that
     * pairs answers to requests would otherwise drop this one.
     */
    fun mint(request: JSONObject?): String? {
        val pair = signingKey ?: return null
        val now = System.currentTimeMillis() / 1000
        val data = JSONObject()
            .put("success", true)
            .put("code", 200)
            .put("message", "Success")
            .put("isValid", true)

        val payload = JSONObject()
            .put("platform", request?.optString("platform")?.takeIf { it.isNotEmpty() } ?: "android")
            .put("data", data)
            .put("iat", now)
            .put("exp", now + TTL_SECONDS)
        request?.optString("jti")?.takeIf { it.isNotEmpty() }?.let { payload.put("req_jti", it) }

        return runCatching { JSONObject().put("token", sign(pair, payload)).toString() }.getOrNull()
    }

    private fun sign(pair: KeyPair, payload: JSONObject): String {
        val header = JSONObject().put("alg", "RS256").put("typ", "JWT")
        val signingInput = encode(header.toString()) + "." + encode(payload.toString())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + Base64.encodeToString(signature, B64)
    }

    private fun encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), B64)

    /**
     * How one particular PEM is written out.
     *
     * PEM has no single canonical form — line width, `\n` against `\r\n` and a
     * trailing newline all vary, and each of them changes the total length.
     * Rebuilding the replacement in the *observed* shape rather than a canonical
     * one is what makes the byte counts agree.
     */
    private class Layout(
        val begin: String,
        val end: String,
        val eol: String,
        val lineWidth: Int,
        val trailingEol: Boolean,
        /** The original base64 body, concatenated. */
        val body: String,
    ) {
        fun render(base64: String): String = buildString {
            append(begin).append(eol)
            var index = 0
            while (index < base64.length) {
                append(base64, index, minOf(index + lineWidth, base64.length)).append(eol)
                index += lineWidth
            }
            append(end)
            if (trailingEol) append(eol)
        }

        companion object {
            fun of(pem: String): Layout? {
                val eol = if (pem.contains("\r\n")) "\r\n" else "\n"
                val trailingEol = pem.endsWith(eol)
                val lines = pem.removeSuffix(eol).split(eol)
                if (lines.size < 3) return null
                val begin = lines.first()
                val end = lines.last()
                if (!begin.startsWith("-----BEGIN") || !end.startsWith("-----END")) return null
                val bodyLines = lines.subList(1, lines.size - 1)
                if (bodyLines.isEmpty()) return null
                // The last body line is a remainder; the width is set by a full one.
                val width = bodyLines.first().length
                if (width == 0) return null
                return Layout(begin, end, eol, width, trailingEol, bodyLines.joinToString(""))
            }
        }
    }
}
