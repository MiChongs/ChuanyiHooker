package com.chuanyi.hillspatch;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A loopback HTTP endpoint standing in for the purchase-verification call.
 *
 * Cutting the request off instead is not an option. A request that throws reads
 * to the app as "could not verify", and that revokes exactly like a negative
 * verdict does — so the replacement has to be something that *answers*, which
 * is why the patcher rewrites the URL to point here rather than at a dead port
 * or a blocked host. (Blocking the host is doubly wrong: the same host serves
 * the update check, and the app will not start without it.)
 *
 * Two ways out of {@link #serve}:
 *
 * <ul>
 *   <li>the request carries a bearer token this understands — answer with a
 *       freshly signed grant, no network involved;</li>
 *   <li>anything else — replay it against the real endpoint and hand back
 *       whatever comes, rather than inventing something the app cannot parse.</li>
 * </ul>
 *
 * Plain HTTP is deliberate: the app talks through rhttp/reqwest, which brings
 * its own socket stack and neither consults Android's cleartext policy nor would
 * accept a self-signed certificate here.
 */
final class VerifyEndpoint {

    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int UPSTREAM_TIMEOUT_MS = 20_000;
    private static final int MAX_HEAD = 64 * 1024;

    /** Hop-by-hop, or recomputed by the connection; never forwarded. */
    private static final List<String> SKIPPED = Arrays.asList(
            "host", "content-length", "connection", "accept-encoding",
            "transfer-encoding", "keep-alive", "upgrade", "proxy-connection");

    private final int port;
    private final String upstream;
    private final Jwt jwt;

    private ServerSocket socket;

    VerifyEndpoint(int port, String upstream, Jwt jwt) {
        this.port = port;
        this.upstream = upstream;
        this.jwt = jwt;
    }

    /**
     * Binds and starts accepting. Binding happens on the caller's thread on
     * purpose — the app must never reach a rewritten URL before there is
     * something listening behind it.
     */
    boolean start() {
        if (socket != null && !socket.isClosed()) return true;
        try {
            socket = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        } catch (Throwable error) {
            Log.e("cannot bind 127.0.0.1:" + port, error);
            return false;
        }

        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        }, "hillspatch-verify");
        acceptor.setDaemon(true);
        acceptor.start();

        Log.i("verification endpoint listening on 127.0.0.1:" + port);
        return true;
    }

    private void accept() {
        while (socket != null && !socket.isClosed()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (Throwable error) {
                if (socket != null && !socket.isClosed()) Log.e("accept failed", error);
                return;
            }
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serve(client);
                    } catch (Throwable error) {
                        Log.e("verification request failed", error);
                    }
                    try {
                        client.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, "hillspatch-verify-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket client) throws Exception {
        client.setSoTimeout(READ_TIMEOUT_MS);
        Request request = read(client.getInputStream());
        if (request == null) return;

        String bearer = request.bearer();
        JSONObject claims = Jwt.claims(bearer);
        String granted = (claims != null && jwt != null) ? jwt.grant(claims) : null;

        if (granted != null) {
            Log.i("verify -> granted (req_jti=" + claims.optString("jti", "") + ")");
            write(client.getOutputStream(), 200, "application/json; charset=utf-8",
                    granted.getBytes("UTF-8"));
            return;
        }

        // Either there was no token to answer, or no key to answer it with.
        // Forwarding is the honest fallback: it is where the request was going
        // before the URL was rewritten.
        Log.w(claims == null ? "no bearer token, forwarding upstream"
                : "no signing key, forwarding upstream");
        Response response = forward(request);
        write(client.getOutputStream(), response.status, response.contentType, response.body);
    }

    // -----------------------------------------------------------------------

    private static final class Request {
        String method = "POST";
        String path = "/";
        final List<String[]> headers = new ArrayList<String[]>();
        byte[] body = new byte[0];

        String bearer() {
            for (String[] header : headers) {
                if (!header[0].equalsIgnoreCase("Authorization")) continue;
                String value = header[1].trim();
                if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    return value.substring(7).trim();
                }
            }
            return null;
        }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static Request read(InputStream input) throws Exception {
        String head = readHead(input);
        if (head == null) return null;

        Request request = new Request();
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return null;

        String[] start = lines[0].split(" ");
        if (start.length < 2) return null;
        request.method = start[0];
        request.path = start[1];

        int length = 0;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            request.headers.add(new String[]{name, value});
            if (name.equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            } else if (name.equalsIgnoreCase("Transfer-Encoding")
                    && value.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }

        if (chunked) {
            request.body = readChunked(input);
        } else if (length > 0) {
            request.body = readExactly(input, length);
        }
        return request;
    }

    /** Everything up to the blank line, without consuming a byte of the body. */
    private static String readHead(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = input.read();
            if (b < 0) return buffer.size() == 0 ? null : new String(buffer.toByteArray(), "ISO-8859-1");
            buffer.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
            if (buffer.size() > MAX_HEAD) return null;
        }
        return new String(buffer.toByteArray(), "ISO-8859-1");
    }

    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = input.read(out, read, length - read);
            if (n < 0) return Arrays.copyOf(out, read);
            read += n;
        }
        return out;
    }

    private static byte[] readChunked(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = input.read();
                if (b < 0) return out.toByteArray();
                if (b == '\n') break;
                if (b != '\r') line.append((char) b);
            }
            int size;
            try {
                size = Integer.parseInt(line.toString().split(";")[0].trim(), 16);
            } catch (NumberFormatException error) {
                return out.toByteArray();
            }
            if (size == 0) return out.toByteArray();
            out.write(readExactly(input, size));
            input.read();
            input.read();
        }
    }

    private Response forward(Request request) {
        if (upstream == null) {
            return new Response(502, "application/json", "{\"error\":\"no upstream\"}".getBytes());
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(upstream).openConnection();
            connection.setRequestMethod(request.method);
            connection.setConnectTimeout(UPSTREAM_TIMEOUT_MS);
            connection.setReadTimeout(UPSTREAM_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            for (String[] header : request.headers) {
                if (SKIPPED.contains(header[0].toLowerCase())) continue;
                connection.setRequestProperty(header[0], header[1]);
            }
            if (request.body.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.body.length);
                OutputStream out = connection.getOutputStream();
                out.write(request.body);
                out.close();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] body = stream == null ? new byte[0] : drain(stream);
            String contentType = connection.getContentType();
            Log.i("verify -> upstream " + status + " " + body.length + "B");
            return new Response(status, contentType == null ? "application/json" : contentType, body);
        } catch (Throwable error) {
            Log.e("upstream call failed", error);
            return new Response(502, "application/json", "{\"error\":\"upstream unreachable\"}".getBytes());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] drain(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        input.close();
        return out.toByteArray();
    }

    private static void write(OutputStream output, int status, String contentType, byte[] body)
            throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(status >= 200 && status < 300 ? " OK" : " Error")
                .append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes("ISO-8859-1"));
        output.write(body);
        output.flush();
    }
}
