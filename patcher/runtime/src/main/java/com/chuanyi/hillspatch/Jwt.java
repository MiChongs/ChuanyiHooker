package com.chuanyi.hillspatch;

import android.util.Base64;

import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * The verification exchange, both directions.
 *
 * The app POSTs an empty body and puts everything in a bearer token signed
 * HS256:
 *
 * <pre>
 * {"platform":"android","iat":…,"exp":…,"jti":"&lt;uuid&gt;",
 *  "data":{"purchases":[{"token":"…","productId":"…"}]}}
 * </pre>
 *
 * and the backend answers {@code {"token":"<RS256 JWT>"}} carrying
 *
 * <pre>
 * {"platform":"android","iat":…,"exp":…,"req_jti":"&lt;same uuid&gt;",
 *  "data":{"success":true,"code":200,"message":"Success","isValid":false}}
 * </pre>
 *
 * where {@code success} is about the call and {@code isValid} about the purchase
 * — that single false is the revocation, and it is what a token Google never
 * issued always earns.
 *
 * RS256 cannot be forged against the real key, and does not have to be: the key
 * the app validates against is a plain PEM string inside the isolate snapshot,
 * and the patcher overwrote it with the public half of the key loaded here.
 */
final class Jwt {

    /** The observed lifetime of a real answer. */
    private static final long TTL_SECONDS = 300L;

    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    private final PrivateKey key;

    private Jwt(PrivateKey key) {
        this.key = key;
    }

    /** null when the key cannot be loaded, in which case nothing may be signed. */
    static Jwt of(byte[] pkcs8) {
        if (pkcs8 == null) return null;
        try {
            return new Jwt(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
        } catch (Throwable error) {
            Log.e("cannot load the signing key", error);
            return null;
        }
    }

    /** Claims of a JWT without checking its signature — only the values matter. */
    static JSONObject claims(String jwt) {
        if (jwt == null) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            return new JSONObject(new String(Base64.decode(parts[1], FLAGS), "UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The response body granting the purchase described by {@code request}.
     *
     * {@code req_jti} is echoed because the real backend does; a client that
     * pairs answers to requests would otherwise drop this one.
     */
    String grant(JSONObject request) {
        try {
            long now = System.currentTimeMillis() / 1000L;

            JSONObject data = new JSONObject()
                    .put("success", true)
                    .put("code", 200)
                    .put("message", "Success")
                    .put("isValid", true);

            JSONObject payload = new JSONObject()
                    .put("platform", platformOf(request))
                    .put("data", data)
                    .put("iat", now)
                    .put("exp", now + TTL_SECONDS);

            if (request != null) {
                String jti = request.optString("jti", "");
                if (jti.length() > 0) payload.put("req_jti", jti);
            }

            return new JSONObject().put("token", sign(payload)).toString();
        } catch (Throwable error) {
            Log.e("cannot mint a grant", error);
            return null;
        }
    }

    private static String platformOf(JSONObject request) {
        if (request == null) return "android";
        String platform = request.optString("platform", "");
        return platform.length() > 0 ? platform : "android";
    }

    private String sign(JSONObject payload) throws Exception {
        JSONObject header = new JSONObject().put("alg", "RS256").put("typ", "JWT");
        String input = encode(header.toString()) + "." + encode(payload.toString());

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(input.getBytes("US-ASCII"));
        return input + "." + Base64.encodeToString(signature.sign(), FLAGS);
    }

    private static String encode(String text) throws Exception {
        return Base64.encodeToString(text.getBytes("UTF-8"), FLAGS);
    }
}
