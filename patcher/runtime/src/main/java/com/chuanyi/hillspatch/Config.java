package com.chuanyi.hillspatch;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * What the patcher decided, read back at runtime.
 *
 * The alternative would be constants compiled into this dex, which would mean
 * rebuilding it for every target and every run. Three of these values cannot be
 * constants anyway:
 *
 * <ul>
 *   <li>{@code privateKey} — its public half is written over the app's own PEM
 *       inside {@code libapp.so}, and the pair is generated per patch. A fixed
 *       key shipped in a public patcher would let anyone else's build sign
 *       responses for this one.</li>
 *   <li>{@code certificate} — the original signer, which only exists in the
 *       untouched input APK.</li>
 *   <li>{@code upstream} — the endpoint as it was before being overwritten,
 *       needed to forward anything this cannot answer itself.</li>
 * </ul>
 *
 * Stored as JSON in the assets because {@code org.json} is part of the platform:
 * a parser of our own would be more code than everything else here.
 */
final class Config {

    /** Asset the patcher writes. Named so it is obvious in an unzip listing. */
    private static final String ASSET = "chuanyi_hillspatch.json";

    /** Loopback port the rewritten URL points at. */
    final int port;

    /** PKCS#8 private key whose public half now sits in the snapshot. */
    final byte[] privateKey;

    /** DER of the original signing certificate, or null to leave it alone. */
    final byte[] certificate;

    /** The endpoint as the app originally had it. */
    final String upstream;

    /** Product id to claim, or null to learn it from the app. */
    final String sku;

    /**
     * Names of the two app classes the injection reflects on.
     *
     * Passed in rather than written into this dex because the patcher is the
     * side that resolved them while patching the call sites — if a future build
     * moves them, the patcher follows and the runtime needs no change.
     */
    final String translator;
    final String purchaseClass;

    private Config(int port, byte[] privateKey, byte[] certificate, String upstream, String sku,
                   String translator, String purchaseClass) {
        this.port = port;
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.upstream = upstream;
        this.sku = sku;
        this.translator = translator;
        this.purchaseClass = purchaseClass;
    }

    /** Reads the asset, or null when it is missing or unreadable. */
    static Config load(Context context) {
        try {
            InputStream input = context.getAssets().open(ASSET);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            input.close();

            JSONObject json = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            return new Config(
                    json.getInt("port"),
                    decode(json.optString("privateKey", null)),
                    decode(json.optString("certificate", null)),
                    emptyToNull(json.optString("upstream", null)),
                    emptyToNull(json.optString("sku", null)),
                    emptyToNull(json.optString("translator", null)),
                    emptyToNull(json.optString("purchaseClass", null)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.length() == 0) return null;
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 ? null : value;
    }
}
