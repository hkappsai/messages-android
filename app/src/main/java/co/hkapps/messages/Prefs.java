package co.hkapps.messages;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User settings, pinned conversations and blocked numbers. */
public final class Prefs {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;
    public static final int MODE_BLACK = 3;

    private static final String KEY_MODE = "theme_mode";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_BUBBLE = "bubble";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_BLOCKED = "blocked";

    private Prefs() {}

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static int themeMode(Context c) { return sp(c).getInt(KEY_MODE, MODE_SYSTEM); }
    public static void setThemeMode(Context c, int mode) { sp(c).edit().putInt(KEY_MODE, mode).apply(); }

    public static int accent(Context c) { return sp(c).getInt(KEY_ACCENT, Look.COLORS[0]); }
    public static void setAccent(Context c, int color) { sp(c).edit().putInt(KEY_ACCENT, color).apply(); }

    /** Sent bubble color, or 0 to follow the accent color. */
    public static int bubble(Context c) { return sp(c).getInt(KEY_BUBBLE, 0); }
    public static void setBubble(Context c, int color) { sp(c).edit().putInt(KEY_BUBBLE, color).apply(); }

    // Pinned conversations

    public static boolean isPinned(Context c, long threadId) {
        return set(c, KEY_PINNED).contains(String.valueOf(threadId));
    }

    public static void setPinned(Context c, long threadId, boolean pinned) {
        Set<String> s = set(c, KEY_PINNED);
        if (pinned) s.add(String.valueOf(threadId)); else s.remove(String.valueOf(threadId));
        sp(c).edit().putStringSet(KEY_PINNED, s).apply();
    }

    // Blocked numbers

    public static List<String> blocked(Context c) {
        List<String> list = new ArrayList<>(set(c, KEY_BLOCKED));
        Collections.sort(list);
        return list;
    }

    public static boolean isBlocked(Context c, String address) {
        for (String b : set(c, KEY_BLOCKED)) {
            if (sameAddress(b, address)) return true;
        }
        return false;
    }

    public static void setBlocked(Context c, String address, boolean blocked) {
        Set<String> s = set(c, KEY_BLOCKED);
        if (blocked) {
            s.add(address);
        } else {
            // Remove every saved form of this number, like "+1 555 0100" and "5550100".
            for (String b : new ArrayList<>(s)) {
                if (sameAddress(b, address)) s.remove(b);
            }
        }
        sp(c).edit().putStringSet(KEY_BLOCKED, s).apply();
    }

    /** Matches phone numbers loosely (country code, spaces), and sender names exactly. */
    static boolean sameAddress(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        return looksLikeNumber(a) && looksLikeNumber(b) && PhoneNumberUtils.compare(a, b);
    }

    private static boolean looksLikeNumber(String s) {
        return s.matches("[+0-9 ()\\-.]{3,}");
    }

    private static Set<String> set(Context c, String key) {
        // getStringSet's result must not be changed in place, so copy it.
        return new HashSet<>(sp(c).getStringSet(key, Collections.<String>emptySet()));
    }
}
