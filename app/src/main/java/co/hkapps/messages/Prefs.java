package co.hkapps.messages;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User settings, pinned conversations and blocked numbers.
 *
 * Settings that make sense per chat (sounds, sending, colors) can be overridden for one chat
 * through {@link ChatPrefs}; the values here are the defaults every chat falls back to.
 */
public final class Prefs {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;
    public static final int MODE_BLACK = 3;
    /** Light by day, dark between nightFrom and nightTo. */
    public static final int MODE_SCHEDULE = 4;

    // ---------------------------------------------------------------- keys

    // Look
    static final String THEME_MODE = "theme_mode";
    static final String ACCENT = "accent";
    static final String BUBBLE = "bubble";
    static final String BUBBLE_IN = "bubble_in";
    static final String BUBBLE_IN_TEXT = "bubble_in_text";
    static final String BUBBLE_OUT_TEXT = "bubble_out_text";
    static final String BUBBLE_SHAPE = "bubble_shape";
    static final String FONT = "font";
    static final String TEXT_SIZE = "text_size";
    static final String NIGHT_FROM = "night_from";
    static final String NIGHT_TO = "night_to";
    static final String AUTO_COLOR = "auto_color";
    static final String CHAT_COLOR = "chat_color";
    static final String ICON_COLOR = "icon_color";
    static final String SKIN_TONE = "skin_tone";

    // Notifications (all can be set per chat)
    static final String NOTIFY = "notify";
    static final String SOUND = "sound";
    static final String VIBRATE = "vibrate";
    static final String LED = "led";
    static final String REPEAT = "repeat";
    static final String SHOW_CONTACT = "show_contact";
    static final String SHOW_TEXT = "show_text";
    static final String WAKE = "wake";
    static final String HEADSUP = "headsup";
    static final String HEADSUP_LOCKED = "headsup_locked";
    static final String POPUP_SECS = "popup_secs";
    static final String POPUP_SECS_LOCKED = "popup_secs_locked";
    static final String TAP_OPENS = "tap_opens";
    static final String AUTO_CLOSE_QR = "auto_close_qr";
    static final String CLEAR_MARKS_READ = "clear_marks_read";
    static final String IN_CHAT_SOUND = "in_chat_sound";
    static final String IN_CHAT_VIBRATE = "in_chat_vibrate";
    static final String BUTTON_1 = "button_1";
    static final String BUTTON_2 = "button_2";
    static final String BUTTON_3 = "button_3";
    static final String UNKNOWN_SENDERS = "unknown_senders";
    static final String BADGE = "badge";
    static final String QUICK_COMPOSE = "quick_compose";
    static final String SENT_SOUND = "sent_sound";

    // Messaging
    static final String DELIVERY_SMS = "delivery_sms";
    static final String DELIVERY_MMS = "delivery_mms";
    static final String SEND_DELAY = "send_delay";
    static final String SIGNATURE = "signature";
    static final String SIM = "sim";
    static final String GROUP_MMS = "group_mms";
    static final String LONG_SMS_AS_MMS = "long_sms_as_mms";
    static final String SPLIT_SMS = "split_sms";
    static final String STRIP_ACCENTS = "strip_accents";
    static final String SEND_ENTER = "send_enter";
    static final String CLOSE_KEYBOARD = "close_keyboard";
    static final String VOICE_MEMO = "voice_memo";
    static final String SMS_COUNTER = "sms_counter";
    static final String MOBILE_ONLY = "mobile_only";
    static final String MY_NUMBER = "my_number";
    static final String SAVE_PICS = "save_pics";
    static final String SAVE_SPACE = "save_space";
    static final String MMS_LIMIT = "mms_limit";
    static final String VIDEO_WARNING = "video_warning";
    static final String LINK_PREVIEWS = "link_previews";
    static final String SMART_REPLY = "smart_reply";
    static final String AUTO_BLOCK_EMAIL = "auto_block_email";
    static final String KEEP_TEXT = "keep_text";
    static final String KEEP_MEDIA = "keep_media";
    static final String DEBUG_LOG = "debug_log";

    // Values
    static final int HEADSUP_ANDROID = 0;
    static final int HEADSUP_POPUP = 1;
    static final int HEADSUP_NONE = 2;
    static final int WAKE_NEVER = 0;
    static final int WAKE_ALWAYS = 1;
    static final int WAKE_UNLESS_QUIET = 2;
    static final int UNKNOWN_NORMAL = 0;
    static final int UNKNOWN_SILENT = 1;
    static final int UNKNOWN_NONE = 2;
    static final int ACCENTS_NEVER = 0;
    static final int ACCENTS_ALWAYS = 1;
    static final int ACCENTS_WHEN_CHEAPER = 2;
    static final int PREVIEWS_OFF = 0;
    static final int PREVIEWS_RECEIVED = 1;
    static final int PREVIEWS_SENT = 2;
    static final int PREVIEWS_ALL = 3;
    static final String TAP_APP = "app";
    static final String TAP_QUICK_REPLY = "qr";
    static final String TAP_QUICK_REPLY_NO_KEYBOARD = "qr_nokb";

    private static final String KEY_PINNED = "pinned";
    private static final String KEY_BLOCKED = "blocked";

    private Prefs() {}

    static SharedPreferences sp(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- generic

    /** Defaults for every key, so screens and readers agree on them. */
    static Object defaultOf(String key) {
        switch (key) {
            case THEME_MODE: return MODE_SYSTEM;
            case ACCENT: return Look.COLORS[0];
            case BUBBLE: case BUBBLE_IN: case BUBBLE_IN_TEXT: case BUBBLE_OUT_TEXT: return 0;
            case BUBBLE_SHAPE: return 0;
            case FONT: return 0;
            case TEXT_SIZE: return 1;
            case NIGHT_FROM: return 22 * 60;
            case NIGHT_TO: return 7 * 60;
            case AUTO_COLOR: return false;
            case CHAT_COLOR: return 0;
            case ICON_COLOR: return 0;
            case SKIN_TONE: return 0;

            case NOTIFY: return true;
            case SOUND: return "default";
            case VIBRATE: return "default";
            case LED: return 0xFFFFFFFF;
            case REPEAT: return 0;
            case SHOW_CONTACT: return true;
            case SHOW_TEXT: return true;
            case WAKE: return WAKE_NEVER;
            case HEADSUP: return HEADSUP_ANDROID;
            case HEADSUP_LOCKED: return HEADSUP_ANDROID;
            case POPUP_SECS: return 20;
            case POPUP_SECS_LOCKED: return 60;
            case TAP_OPENS: return TAP_APP;
            case AUTO_CLOSE_QR: return true;
            case CLEAR_MARKS_READ: return false;
            case IN_CHAT_SOUND: return false;
            case IN_CHAT_VIBRATE: return true;
            case BUTTON_1: return NotifyActions.REPLY;
            case BUTTON_2: return NotifyActions.MARK_READ;
            case BUTTON_3: return NotifyActions.NONE;
            case UNKNOWN_SENDERS: return UNKNOWN_NORMAL;
            case BADGE: return true;
            case QUICK_COMPOSE: return false;
            case SENT_SOUND: return false;

            case DELIVERY_SMS: return false;
            case DELIVERY_MMS: return false;
            case SEND_DELAY: return 0;
            case SIGNATURE: return -1L;
            case SIM: return -1;
            case GROUP_MMS: return true;
            case LONG_SMS_AS_MMS: return 0;
            case SPLIT_SMS: return false;
            case STRIP_ACCENTS: return ACCENTS_NEVER;
            case SEND_ENTER: return false;
            case CLOSE_KEYBOARD: return false;
            case VOICE_MEMO: return true;
            case SMS_COUNTER: return true;
            case MOBILE_ONLY: return false;
            case MY_NUMBER: return "";
            case SAVE_PICS: return false;
            case SAVE_SPACE: return true;
            case MMS_LIMIT: return 0;
            case VIDEO_WARNING: return false;
            case LINK_PREVIEWS: return PREVIEWS_ALL;
            case SMART_REPLY: return true;
            case AUTO_BLOCK_EMAIL: return false;
            case KEEP_TEXT: return 0;
            case KEEP_MEDIA: return 0;
            case DEBUG_LOG: return false;
            default: return null;
        }
    }

    static boolean bool(Context c, String key) {
        return sp(c).getBoolean(key, (Boolean) defaultOf(key));
    }

    static int integer(Context c, String key) {
        return sp(c).getInt(key, (Integer) defaultOf(key));
    }

    static long longValue(Context c, String key) {
        return sp(c).getLong(key, (Long) defaultOf(key));
    }

    static String string(Context c, String key) {
        return sp(c).getString(key, (String) defaultOf(key));
    }

    static void put(Context c, String key, Object value) {
        SharedPreferences.Editor e = sp(c).edit();
        putValue(e, key, value);
        e.apply();
    }

    static void putValue(SharedPreferences.Editor e, String key, Object value) {
        if (value == null) e.remove(key);
        else if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else if (value instanceof Long) e.putLong(key, (Long) value);
        else e.putString(key, value.toString());
    }

    // ---------------------------------------------------------------- look

    public static int themeMode(Context c) { return integer(c, THEME_MODE); }
    public static void setThemeMode(Context c, int mode) { put(c, THEME_MODE, mode); }

    public static int accent(Context c) { return integer(c, ACCENT); }
    public static void setAccent(Context c, int color) { put(c, ACCENT, color); }

    /** Sent bubble color, or 0 to follow the accent color. */
    public static int bubble(Context c) { return integer(c, BUBBLE); }
    public static void setBubble(Context c, int color) { put(c, BUBBLE, color); }

    // ---------------------------------------------------------------- pinned

    public static boolean isPinned(Context c, long threadId) {
        return set(c, KEY_PINNED).contains(String.valueOf(threadId));
    }

    public static void setPinned(Context c, long threadId, boolean pinned) {
        Set<String> s = set(c, KEY_PINNED);
        if (pinned) s.add(String.valueOf(threadId)); else s.remove(String.valueOf(threadId));
        sp(c).edit().putStringSet(KEY_PINNED, s).apply();
    }

    // ---------------------------------------------------------------- blocked

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

    /** A chat is blocked when everyone in it is blocked. */
    public static boolean isBlocked(Context c, List<String> addresses) {
        if (addresses.isEmpty()) return false;
        for (String a : addresses) {
            if (!isBlocked(c, a)) return false;
        }
        return true;
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

    static boolean looksLikeNumber(String s) {
        return s.matches("[+0-9 ()\\-.]{3,}");
    }

    private static Set<String> set(Context c, String key) {
        // getStringSet's result must not be changed in place, so copy it.
        return new HashSet<>(sp(c).getStringSet(key, Collections.<String>emptySet()));
    }
}
