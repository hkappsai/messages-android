package co.hkapps.messages;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Settings for one chat. Each value falls back to the app-wide setting in {@link Prefs} until
 * the user changes it for this chat, like Textra's per-contact "Customize" screen.
 */
public final class ChatPrefs {

    /** Name the user gave a group chat. Has no app-wide counterpart. */
    static final String NAME = "name";

    private ChatPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("chats", Context.MODE_PRIVATE);
    }

    private static String k(long threadId, String key) {
        return threadId + "." + key;
    }

    /** True when this chat has its own value for the key. */
    static boolean has(Context c, long threadId, String key) {
        return threadId > 0 && sp(c).contains(k(threadId, key));
    }

    static boolean bool(Context c, long threadId, String key) {
        return has(c, threadId, key) ? sp(c).getBoolean(k(threadId, key), false) : Prefs.bool(c, key);
    }

    static int integer(Context c, long threadId, String key) {
        return has(c, threadId, key) ? sp(c).getInt(k(threadId, key), 0) : Prefs.integer(c, key);
    }

    static long longValue(Context c, long threadId, String key) {
        return has(c, threadId, key) ? sp(c).getLong(k(threadId, key), 0) : Prefs.longValue(c, key);
    }

    static String string(Context c, long threadId, String key) {
        if (has(c, threadId, key)) return sp(c).getString(k(threadId, key), "");
        Object fallback = Prefs.defaultOf(key);
        return fallback == null ? null : Prefs.string(c, key);
    }

    /** Sets this chat's value. Null goes back to the app-wide value. */
    static void put(Context c, long threadId, String key, Object value) {
        SharedPreferences.Editor e = sp(c).edit();
        Prefs.putValue(e, k(threadId, key), value);
        e.apply();
    }

    /** Forgets everything set for a chat, for example when it is deleted. */
    static void clear(Context c, long threadId) {
        SharedPreferences.Editor e = sp(c).edit();
        String prefix = threadId + ".";
        for (Map.Entry<String, ?> entry : sp(c).getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix)) e.remove(entry.getKey());
        }
        e.apply();
    }

    // ---------------------------------------------------------------- shortcuts

    static boolean muted(Context c, long threadId) {
        return !bool(c, threadId, Prefs.NOTIFY);
    }

    static String name(Context c, long threadId) {
        return has(c, threadId, NAME) ? sp(c).getString(k(threadId, NAME), null) : null;
    }

    /** Every key/value stored, for backups. */
    static Map<String, ?> all(Context c) {
        return sp(c).getAll();
    }

    static void restore(Context c, Map<String, Object> values) {
        SharedPreferences.Editor e = sp(c).edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) Prefs.putValue(e, entry.getKey(), entry.getValue());
        e.apply();
    }
}
