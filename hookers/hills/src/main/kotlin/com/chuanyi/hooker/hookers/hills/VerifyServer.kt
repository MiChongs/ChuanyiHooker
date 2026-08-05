package com.chuanyi.hooker.hookers.hills

import com.chuanyi.hooker.core.HookScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * A loopback HTTP endpoint that stands in for the purchase-verification call.
 *
 * Cutting the request off is not enough — a thrown request reads to the app as
 * "could not verify", which revokes the unlock just as a negative verdict does.
 * The only answer that keeps Pro is a *successful* one, so this serves the
 * response instead of preventing it.
 *
 * The reply is chosen in this order:
 *
 *   1. `files/hills_verify.json` in the target's data dir, served verbatim —
 *      an escape hatch for trying a different body without rebuilding.
 *   2. a freshly signed grant from [VerifyToken], which is the normal path and
 *      needs no network at all.
 *   3. the real server's answer, forwarded unchanged, when the request is not
 *      the exchange we know how to answer.
 *
 * Everything crossing this point is logged, so one run shows what the app sends
 * and what it was told.
 *
 * The URL handed to [VerifyBreaker] replaces the app's own in place, so it has
 * to be exactly as long — and how long that is is only known once the endpoint
 * has been found in memory. [buildUrl] pads the path to whatever the caller
 * asks for; the handler ignores the path, so the padding costs nothing.
 *
 * Plain HTTP is deliberate: the app talks through rhttp/reqwest, which uses its
 * own socket stack and neither consults Android's cleartext policy nor would
 * accept a self-signed certificate here.
 */
internal object VerifyServer {

    /** Free port, high enough for an unprivileged bind, fixed so the URL is stable. */
    private const val PORT = 45871

    /** Dropped into the target's `files/` to pin the response. */
    const val OVERRIDE_FILE = "hills_verify.json"

    private const val LOG_BODY_LIMIT = 8192
    private const val READ_TIMEOUT_MS = 15_000
    private const val UPSTREAM_TIMEOUT_MS = 20_000

    /** Never forwarded upstream: hop-by-hop, or recomputed by the connection. */
    private val SKIPPED_HEADERS = setOf(
        "host", "content-length", "connection", "accept-encoding",
        "transfer-encoding", "keep-alive", "upgrade", "proxy-connection",
    )

    private val socket = AtomicReference<ServerSocket?>(null)

    /**
     * Binds the endpoint and returns a [urlLength]-byte URL to rewrite the app's
     * copy with, or null when the port cannot be taken or the length cannot be
     * met.
     *
     * Binding happens on the caller's thread on purpose: the rewrite must not
     * land before there is something listening.
     */
    fun start(scope: HookScope, urlLength: Int): String? {
        val url = buildUrl(urlLength) ?: run {
            scope.log.e("$urlLength bytes is too short for a loopback URL (need $MIN_URL_LENGTH)")
            return null
        }
        socket.get()?.let { if (!it.isClosed) return url }

        val server = runCatching {
            ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            scope.log.e("cannot bind 127.0.0.1:$PORT", it)
            return null
        }
        if (!socket.compareAndSet(null, server)) {
            runCatching { server.close() }
            return url
        }

        val workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "hills-verify-worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        Thread({ accept(scope, server, workers) }, "hills-verify-server").apply {
            isDaemon = true
            start()
        }
        scope.log.i("verification endpoint listening on $url")
        return url
    }

    /** `http://127.0.0.1:<port>` — the shortest form the replacement can take. */
    private val ORIGIN = "http://127.0.0.1:$PORT"

    /** No endpoint can be redirected into fewer bytes than this. */
    private val MIN_URL_LENGTH = ORIGIN.length

    /**
     * `http://127.0.0.1:<port>/pppp…`, exactly [length] bytes.
     *
     * The replacement is written over the live Dart string in place, so it has
     * to be exactly as long as what it replaces — and that length comes from the
     * app, not from here. Padding goes in the path rather than a query string
     * because a path can absorb any surplus down to a single byte, while `?x`
     * needs two; [serve] ignores the path either way.
     */
    private fun buildUrl(length: Int): String? = when {
        length < MIN_URL_LENGTH -> null
        length == MIN_URL_LENGTH -> ORIGIN
        else -> ORIGIN + "/" + "p".repeat(length - MIN_URL_LENGTH - 1)
    }

    private fun accept(scope: HookScope, server: ServerSocket, workers: java.util.concurrent.ExecutorService) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrElse {
                if (!server.isClosed) scope.log.w("accept failed: ${it.message}")
                return
            }
            workers.execute {
                runCatching { serve(scope, client) }
                    .onFailure { scope.log.w("verification request failed: ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    private fun serve(scope: HookScope, client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream()
        val request = readRequest(input) ?: return

        scope.log.i("verify <- ${request.method} ${request.path}")
        request.headers.forEach { (name, value) -> scope.log.d("verify <-   $name: $value") }
        if (request.body.isNotEmpty()) {
            scope.log.i("verify <- body ${request.body.size}B: ${preview(request.body)}")
        }

        val override = overrideBody(scope)
        val claims = VerifyToken.claims(VerifyToken.bearer(request.headers))
        // Without an adopted key the answer would be signed by nobody the app
        // trusts, and an unverifiable reply revokes exactly like a refusal does.
        val minted = if (claims != null) VerifyToken.mint(claims) else null

        val response = when {
            override != null -> {
                scope.log.i("verify -> $OVERRIDE_FILE (${override.size}B)")
                Response(200, "application/json; charset=utf-8", override)
            }

            minted != null -> {
                scope.log.i("verify <- claims $claims")
                scope.log.i("verify -> granted (req_jti=${claims?.optString("jti")})")
                Response(200, "application/json; charset=utf-8", minted.toByteArray())
            }

            // Not the exchange we know how to answer: let the real backend
            // answer rather than inventing something the app cannot parse.
            else -> {
                if (claims == null) scope.log.w("verify <- no bearer token, forwarding upstream")
                else scope.log.w("verify <- no signing key adopted, forwarding upstream")
                forward(scope, request)
            }
        }

        write(client.getOutputStream(), response)
    }

    // -----------------------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    private class Response(val status: Int, val contentType: String, val body: ByteArray)

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val parts = lines[0].split(' ')
        if (parts.size < 2) return null

        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

        val length = headers.firstOrNull { it.first.equals("Content-Length", true) }
            ?.second?.toIntOrNull() ?: 0
        val chunked = headers.any {
            it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true)
        }

        val body = when {
            chunked -> readChunked(input)
            length > 0 -> readExactly(input, length)
            else -> ByteArray(0)
        }
        return Request(parts[0], parts[1], headers, body)
    }

    /** Everything up to the blank line, without consuming a byte of the body. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            buffer.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (buffer.size() > 64 * 1024) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) return out.copyOf(read)
            read += n
        }
        return out
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return out.toByteArray()
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            val size = line.toString().substringBefore(';').trim().toIntOrNull(16) ?: return out.toByteArray()
            if (size == 0) return out.toByteArray()
            out.write(readExactly(input, size))
            input.read(); input.read() // trailing CRLF
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Replays the request against the real backend and reports what came back.
     *
     * The upstream address is the endpoint [VerifyBreaker] found in the heap —
     * the only reference to the app's real backend left anywhere in the module,
     * and the reason a forwarded request still reaches the right place after the
     * app changes it.
     */
    private fun forward(scope: HookScope, request: Request): Response {
        val upstream = VerifyBreaker.upstream ?: run {
            scope.log.w("no upstream endpoint known, answering 502")
            return Response(502, "application/json", """{"error":"no upstream"}""".toByteArray())
        }
        val connection = runCatching {
            (URL(upstream).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = UPSTREAM_TIMEOUT_MS
                readTimeout = UPSTREAM_TIMEOUT_MS
                instanceFollowRedirects = true
                request.headers
                    .filterNot { it.first.lowercase() in SKIPPED_HEADERS }
                    .forEach { (name, value) -> setRequestProperty(name, value) }
                if (request.body.isNotEmpty()) {
                    doOutput = true
                    setFixedLengthStreamingMode(request.body.size)
                    outputStream.use { it.write(request.body) }
                }
            }
        }.getOrElse {
            scope.log.w("upstream call could not be started: ${it.message}")
            return Response(502, "application/json", """{"error":"upstream unreachable"}""".toByteArray())
        }

        val status = runCatching { connection.responseCode }.getOrElse {
            scope.log.w("upstream did not answer: ${it.message}")
            connection.disconnect()
            return Response(502, "application/json", """{"error":"upstream unreachable"}""".toByteArray())
        }
        val body = runCatching {
            (if (status in 200..399) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        val contentType = connection.contentType ?: "application/json"
        connection.disconnect()

        scope.log.i("verify -> upstream $status ${body.size}B: ${preview(body)}")
        return Response(status, contentType, body)
    }

    private fun overrideBody(scope: HookScope): ByteArray? {
        val dir = scope.appContextOrNull()?.filesDir ?: return null
        val file = File(dir, OVERRIDE_FILE)
        if (!file.isFile || !file.canRead()) return null
        return runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun write(output: OutputStream, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
                .append(if (response.status in 200..299) "OK" else "Error").append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private fun preview(body: ByteArray): String {
        val text = String(body, Charsets.UTF_8)
        return if (text.length <= LOG_BODY_LIMIT) text else text.take(LOG_BODY_LIMIT) + "…"
    }
}
