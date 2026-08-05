package com.chuanyi.hillspatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which product id stands for the unlock, learned from the app.
 *
 * The id is a Dart-side constant — it is not in the dex at all, so patching
 * cannot read it out of the code the way it reads class names. What the app
 * cannot hide is which products it asks Play about, and that call crosses into
 * Java as {@code queryProductDetailsAsync(List<PlatformQueryProduct>, …)} with
 * the id in plain sight.
 *
 * The catalogue this was validated against is {@code hills.pro},
 * {@code hills.pro.lifetime}, {@code hills.pro.lifetime.discount3}, and it is
 * what ruled out the obvious rule: "the id the others are built on" picks
 * {@code hills.pro}, which is a different, lesser product. A shared prefix marks
 * a family, not the thing being sold. What is sold is the most specific entry
 * that is not a promotion.
 *
 * Timing is the catch — an app queries its purchases at startup and its
 * catalogue only when a store screen opens, so the id can arrive after the
 * purchase list has gone past. The answer is cached per app version, so only a
 * first run can be affected, and a pinned id in the patch config skips the
 * problem entirely.
 */
final class Skus {

    private static final String[] PROMO = {"discount", "promo", "sale", "trial", "off", "intro"};
    private static final String[] PERPETUAL = {"lifetime", "forever", "permanent", "onetime"};

    private static final String PREFS = "chuanyi_hillspatch";
    private static final String KEY_IDS = "sku.inapp";
    private static final String KEY_VERSION = "sku.version";

    private final Context context;
    private final String pinned;
    private final long version;
    private final List<String> known = Collections.synchronizedList(new ArrayList<String>());

    /** The id the app last opened the Play sheet for; beats anything inferred. */
    private volatile String selling;

    Skus(Context context, String pinned) {
        this.context = context;
        this.pinned = pinned;
        this.version = versionOf(context);
        restore();
    }

    /**
     * The app's versionCode, so a cached catalogue cannot outlive the build it
     * came from. A stale id is worse than none: it looks like it worked.
     */
    private static long versionOf(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }

    /** The id to claim, or null while nothing is known. */
    String lifetime() {
        if (pinned != null) return pinned;
        String sold = selling;
        if (sold != null) return sold;
        return best();
    }

    /** Records every one-time product in a {@code queryProductDetailsAsync} call. */
    void record(List<?> queryProducts) {
        if (queryProducts == null) return;
        for (Object product : queryProducts) {
            if (product == null) continue;
            try {
                Object type = invoke(product, "getProductType");
                // A subscription is not the perpetual unlock; claiming one would
                // make the app expect a renewal it will never see.
                if (type instanceof Enum && "SUBS".equals(((Enum<?>) type).name())) continue;

                Object id = invoke(product, "getProductId");
                if (id instanceof String) remember((String) id);
            } catch (Throwable ignored) {
                // One unreadable entry must not lose the rest of the catalogue.
            }
        }
    }

    /** Records the product the buy sheet was opened for. */
    void sold(String id) {
        if (id == null || id.length() == 0) return;
        selling = id;
        Log.i("the app is selling " + id + ", taking it over the queried ids");
        remember(id);
    }

    // -----------------------------------------------------------------------

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private void remember(String id) {
        synchronized (known) {
            if (known.contains(id)) return;
            known.add(id);
        }
        Log.i("learned one-time product id: " + id);
        persist();
    }

    /**
     * Highest rank wins; the longer id breaks a tie, because a catalogue narrows
     * as it gets specific and the short entry is the family root.
     */
    private String best() {
        String winner = null;
        int winnerRank = Integer.MIN_VALUE;
        synchronized (known) {
            for (String candidate : known) {
                int rank = rank(candidate);
                if (winner == null || rank > winnerRank
                        || (rank == winnerRank && candidate.length() > winner.length())) {
                    winner = candidate;
                    winnerRank = rank;
                }
            }
        }
        return winner;
    }

    private static int rank(String candidate) {
        String lower = candidate.toLowerCase();
        int score = 0;
        for (String word : PROMO) {
            if (lower.contains(word)) {
                score -= 3;
                break;
            }
        }
        for (String word : PERPETUAL) {
            if (lower.contains(word)) {
                score += 2;
                break;
            }
        }
        return score;
    }

    // -----------------------------------------------------------------------

    private SharedPreferences prefs() {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void restore() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        if (prefs.getLong(KEY_VERSION, Long.MIN_VALUE + 1) != version) return;
        Set<String> cached = prefs.getStringSet(KEY_IDS, null);
        if (cached == null || cached.isEmpty()) return;
        synchronized (known) {
            for (String id : cached) {
                if (id != null && id.length() > 0 && !known.contains(id)) known.add(id);
            }
        }
        Log.i("product ids from the last run: " + known);
    }

    private void persist() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        try {
            synchronized (known) {
                prefs.edit()
                        .putLong(KEY_VERSION, version)
                        .putStringSet(KEY_IDS, new HashSet<String>(known))
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }
}
