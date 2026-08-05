package com.chuanyi.hillspatch;

/**
 * One tag for everything this patch does.
 *
 * Deliberately not silent. A repacked app that misbehaves has no debugger and no
 * module UI attached to it, and the whole chain — endpoint bound, purchase
 * injected, response signed — is invisible from the outside; without a log the
 * only symptom available is "Pro did not unlock", which says nothing about
 * which of five steps failed.
 */
final class Log {

    static final String TAG = "ChuanyiHillsPatch";

    private Log() {
    }

    static void i(String message) {
        android.util.Log.i(TAG, message);
    }

    static void w(String message) {
        android.util.Log.w(TAG, message);
    }

    static void e(String message, Throwable error) {
        android.util.Log.e(TAG, message, error);
    }
}
