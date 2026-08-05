package com.chuanyi.hillspatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the entry that makes {@code in_app_purchase_android} report an owned
 * product, out of the app's own classes so the pigeon payload is field-for-field
 * what Dart expects.
 *
 * Nothing is constructed by hand. {@code Translator.fromPurchase} is the app's
 * own converter, so whatever fields that version of the plugin fills in get
 * filled in the same way here — a synthetic {@code PlatformPurchase} assembled
 * field by field would drift the first time the plugin adds one.
 *
 * The two class names come from the patcher rather than being written here: it
 * is the side that resolved them while patching, and passing them along keeps
 * the runtime working on a build where they moved.
 */
final class Purchases {

    /** Stable so Dart-side de-duplication by token behaves. */
    private static final String TOKEN = "chuanyi-hillspatch-0000000000000000000000";

    private final Method fromPurchase;
    private final Constructor<?> purchase;
    private final String packageName;

    private Purchases(Method fromPurchase, Constructor<?> purchase, String packageName) {
        this.fromPurchase = fromPurchase;
        this.purchase = purchase;
        this.packageName = packageName;
    }

    /** null when either class is absent, which disables injection rather than crashing. */
    static Purchases of(String translatorName, String purchaseName, String packageName) {
        if (translatorName == null || purchaseName == null) return null;
        try {
            Class<?> purchaseClass = Class.forName(purchaseName);
            Constructor<?> constructor =
                    purchaseClass.getDeclaredConstructor(String.class, String.class);
            constructor.setAccessible(true);

            Method converter = null;
            for (Method candidate : Class.forName(translatorName).getDeclaredMethods()) {
                if (candidate.getName().equals("fromPurchase")
                        && candidate.getParameterTypes().length == 1) {
                    converter = candidate;
                    break;
                }
            }
            if (converter == null) {
                Log.w("Translator has no fromPurchase, cannot inject");
                return null;
            }
            converter.setAccessible(true);
            return new Purchases(converter, constructor, packageName);
        } catch (Throwable error) {
            Log.e("cannot prepare the synthetic purchase", error);
            return null;
        }
    }

    /**
     * {@code converted} with one entry for {@code sku} appended.
     *
     * Appending rather than replacing leaves the app's own conversion in charge
     * of every genuine purchase, and the guard stops a real entitlement from
     * being duplicated.
     */
    List<Object> inject(List<Object> converted, String sku) {
        if (sku == null) return converted;

        List<Object> out = new ArrayList<Object>();
        if (converted != null) {
            for (Object entry : converted) {
                // A real purchase of the same product means there is nothing to
                // add; its own token is the one that should be verified.
                if (entry != null && String.valueOf(entry).contains(sku)) return converted;
                out.add(entry);
            }
        }

        Object injected = platformPurchase(sku);
        if (injected == null) return converted;
        out.add(injected);
        Log.i("injected " + sku + " into the purchase list");
        return out;
    }

    private Object platformPurchase(String sku) {
        try {
            Object real = purchase.newInstance(json(packageName, sku), "");
            return fromPurchase.invoke(null, real);
        } catch (Throwable error) {
            Log.e("cannot build the synthetic purchase", error);
            return null;
        }
    }

    /**
     * Play Billing v5+ purchase JSON.
     *
     * {@code Translator.fromPurchase} reads {@code productIds} first and falls
     * back to {@code productId}, and maps any {@code purchaseState} other than 4
     * to PURCHASED. {@code acknowledged} is true so Dart never tries to
     * acknowledge a token that does not exist, and {@code autoRenewing} is false
     * because a lifetime unlock is a one-time product, not a subscription.
     */
    private static String json(String packageName, String sku) {
        return "{"
                + "\"orderId\":\"GPA.0000-0000-0000-00000\","
                + "\"packageName\":\"" + packageName + "\","
                + "\"productId\":\"" + sku + "\","
                + "\"productIds\":[\"" + sku + "\"],"
                + "\"purchaseTime\":1700000000000,"
                + "\"purchaseState\":0,"
                + "\"purchaseToken\":\"" + TOKEN + "\","
                + "\"quantity\":1,"
                + "\"acknowledged\":true,"
                + "\"autoRenewing\":false,"
                + "\"developerPayload\":\"\""
                + "}";
    }
}
