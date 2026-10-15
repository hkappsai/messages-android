package co.hkapps.messages;

import android.content.Context;

/** A short log of what the app did, kept only while "Debug log" is on in Settings. */
final class DebugLog {

    private DebugLog() {}

    static void log(Context context, String text) {
        if (!Prefs.bool(context, Prefs.DEBUG_LOG)) return;
        try {
            AppDb.get(context).log(text);
        } catch (RuntimeException ignored) {
            // Logging must never break messaging.
        }
    }
}
