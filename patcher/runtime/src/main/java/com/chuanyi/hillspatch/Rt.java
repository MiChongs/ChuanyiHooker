package com.chuanyi.hillspatch;

import android.content.Context;

import java.util.List;

/**
 * Everything the patched smali calls into.
 *
 * Kept to five static methods on purpose. Each patch site is a two-instruction
 * insert — {@code invoke-static} plus a {@code move-result} where there is a
 * value — so the register pressure at the call site never changes and no method
 * needs its {@code .locals} rewritten. All the actual work lives behind these
 * entry points in normal Java, which is what makes this maintainable at all:
 * hand-written smali for JWT signing or an HTTP server would be unreviewable.
 *
 * Every method here is failure-tolerant by construction. This code runs inside
 * somebody else's app with no supervision, and an exception escaping any of
 * these would surface as a crash in the app's own billing path — strictly worse
 * than the unlock not happening. Nothing here is allowed to throw.
 *
 * <pre>
 *   App.onCreate                        -> init(Context)
 *   Translator.fromPurchasesList        -> purchases(List)
 *   MethodCallHandlerImpl               -> supported()
 *     .isFeatureSupported / .isReady
 *   MethodCallHandlerImpl               -> products(List)
 *     .queryProductDetailsAsync
 *   Signature.toByteArray (at the       -> cert(byte[])
 *     signature channel's digest site)
 * </pre>
 */
public final class Rt {

    private static volatile Config config;
    private static volatile Purchases purchases;
    private static volatile Skus skus;
    private static volatile boolean started;

    private Rt() {
    }

    /**
     * Called first thing in the app's {@code Application.onCreate}.
     *
     * That is early enough by a wide margin — the Flutter engine, the billing
     * client and the first verification are all much later — and it is the only
     * point that is guaranteed to run exactly once per process.
     */
    public static synchronized void init(Context context) {
        if (started) return;
        started = true;
        try {
            Config loaded = Config.load(context);
            if (loaded == null) {
                Log.w("no patch config in the assets; leaving the app alone");
                return;
            }
            config = loaded;
            skus = new Skus(context, loaded.sku);
            purchases = Purchases.of(loaded.translator, loaded.purchaseClass, context.getPackageName());

            Jwt jwt = Jwt.of(loaded.privateKey);
            if (jwt == null) {
                // The endpoint still comes up: forwarding upstream is better
                // than a refused connection, which the app reads as a failure.
                Log.w("no usable signing key; the endpoint can only forward");
            }
            new VerifyEndpoint(loaded.port, loaded.upstream, jwt).start();
        } catch (Throwable error) {
            Log.e("init failed", error);
        }
    }

    /**
     * Wraps the return of {@code Translator.fromPurchasesList}.
     *
     * That method is the single funnel every purchase path goes through —
     * {@code queryPurchasesAsync} and the {@code onPurchasesUpdated} callback
     * alike — so one patch site covers both.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> purchases(List<Object> converted) {
        try {
            Purchases builder = purchases;
            Skus catalogue = skus;
            if (builder == null || catalogue == null) return converted;

            String sku = catalogue.lifetime();
            if (sku == null) {
                Log.w("no product id known yet, leaving the purchase list alone");
                return converted;
            }
            return builder.inject(converted, sku);
        } catch (Throwable error) {
            Log.e("purchase injection failed", error);
            return converted;
        }
    }

    /**
     * Replaces the billing capability probes.
     *
     * {@code isFeatureSupported} is what produces "设备不支持 Google Play 订阅",
     * and it says no in two different ways: false when Play reports the feature
     * missing, and a thrown {@code FlutterError("UNAVAILABLE")} when the billing
     * client does not exist yet. Replacing the method outright is the only thing
     * that covers both — and it is upstream of everything else here, because an
     * app that has decided the device cannot buy never gets as far as querying
     * purchases.
     */
    public static boolean supported() {
        return true;
    }

    /** Records the catalogue as the app asks Play about it. */
    public static void products(List<?> queryProducts) {
        try {
            Skus catalogue = skus;
            if (catalogue != null) catalogue.record(queryProducts);
        } catch (Throwable error) {
            Log.e("recording the catalogue failed", error);
        }
    }

    /** Records the product the buy sheet was opened for. */
    public static void sold(Object flowParams) {
        try {
            Skus catalogue = skus;
            if (catalogue == null || flowParams == null) return;
            Object id = flowParams.getClass().getMethod("getProduct").invoke(flowParams);
            if (id instanceof String) catalogue.sold((String) id);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Substitutes the certificate the app is about to digest.
     *
     * The app reports {@code SHA-256(apkContentsSigners[0].toByteArray())} to
     * its backend, and re-signing changes it — the one thing a repack cannot
     * hide by itself. Swapping the *input* rather than the resulting string
     * means the app's own digesting and formatting still run, so whatever
     * separator or case it uses stays correct without having to be reproduced
     * here.
     */
    public static byte[] cert(byte[] actual) {
        try {
            Config current = config;
            if (current == null || current.certificate == null) return actual;
            return current.certificate;
        } catch (Throwable error) {
            Log.e("certificate substitution failed", error);
            return actual;
        }
    }
}
