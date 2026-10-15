package co.hkapps.messages;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import java.util.Calendar;
import java.util.List;

/**
 * Colors, fonts and bubble shapes for the current settings: light/dark/black mode, accent,
 * bubble colors, text font and size. A chat can carry its own accent color.
 */
public final class Look {

    /** Choices offered for the accent and bubble colors. */
    public static final int[] COLORS = {
            0xFF1A73E8, // blue
            0xFF3F51B5, // indigo
            0xFF8E24AA, // purple
            0xFFD81B60, // pink
            0xFFE53935, // red
            0xFFF4511E, // deep orange
            0xFFFFA000, // amber
            0xFF43A047, // green
            0xFF00897B, // teal
            0xFF00ACC1, // cyan
            0xFF6D4C41, // brown
            0xFF546E7A, // blue grey
    };

    /** Light choices for received bubbles. */
    public static final int[] SOFT_COLORS = {
            0xFFECEFF1, 0xFFE3F2FD, 0xFFE8EAF6, 0xFFF3E5F5, 0xFFFCE4EC, 0xFFFFEBEE,
            0xFFFFF3E0, 0xFFFFF8E1, 0xFFE8F5E9, 0xFFE0F2F1, 0xFFE0F7FA, 0xFFEFEBE9,
    };

    static final String[] FONTS = {"System", "Serif", "Monospace", "Condensed", "Light", "Medium"};
    static final String[] TEXT_SIZES = {"Small", "Normal", "Large", "Huge"};
    static final String[] SHAPES = {"Rounded", "Round with tail", "Square", "Pill"};
    private static final float[] SIZES_SP = {13f, 15f, 17f, 20f};

    public final int mode;
    public final boolean dark;
    public final boolean black;
    public final int accent;
    public final int onAccent;
    public final int bubbleOut;
    public final int bubbleOutText;
    public final int bubbleIn;
    public final int bubbleInText;
    public final int bar;
    public final int statusBar;
    public final int styleRes;
    public final int shape;
    public final int font;
    public final int textSize;

    private Look(Context context, int chatAccent) {
        mode = Prefs.themeMode(context);
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Prefs.MODE_DARK || mode == Prefs.MODE_BLACK
                || (mode == Prefs.MODE_SYSTEM && night == Configuration.UI_MODE_NIGHT_YES)
                || (mode == Prefs.MODE_SCHEDULE && isNight(context));
        black = mode == Prefs.MODE_BLACK;

        accent = chatAccent != 0 ? chatAccent : Prefs.accent(context);
        onAccent = textOn(accent);
        int chosenBubble = Prefs.bubble(context);
        // A chat's own color also colors its sent bubbles.
        bubbleOut = chatAccent != 0 ? chatAccent : chosenBubble != 0 ? chosenBubble : accent;
        int outText = Prefs.integer(context, Prefs.BUBBLE_OUT_TEXT);
        bubbleOutText = outText != 0 ? outText : textOn(bubbleOut);

        int inColor = Prefs.integer(context, Prefs.BUBBLE_IN);
        if (black) {
            bubbleIn = inColor != 0 ? darken(inColor) : 0xFF1E1E1E;
            bar = Color.BLACK;
            statusBar = Color.BLACK;
            styleRes = R.style.AppTheme_Black;
        } else if (dark) {
            bubbleIn = inColor != 0 ? darken(inColor) : 0xFF303134;
            bar = 0xFF1F1F1F;
            statusBar = 0xFF1F1F1F;
            styleRes = R.style.AppTheme_Dark;
        } else {
            bubbleIn = inColor != 0 ? inColor : 0xFFECEFF1;
            bar = accent;
            statusBar = darker(accent);
            styleRes = R.style.AppTheme_Light;
        }
        int inText = Prefs.integer(context, Prefs.BUBBLE_IN_TEXT);
        bubbleInText = inText != 0 ? inText : textOn(bubbleIn);
        shape = Prefs.integer(context, Prefs.BUBBLE_SHAPE);
        font = Prefs.integer(context, Prefs.FONT);
        textSize = Prefs.integer(context, Prefs.TEXT_SIZE);
    }

    public static Look from(Context context) {
        return new Look(context, 0);
    }

    /** The look inside one chat: its own color when it has one. */
    public static Look forChat(Context context, long threadId, List<String> recipients) {
        int color = chatAccent(context, threadId, recipients);
        return new Look(context, color);
    }

    /** A chat's chosen color, or its auto color, or 0 for the app accent. */
    static int chatAccent(Context context, long threadId, List<String> recipients) {
        if (threadId > 0 && ChatPrefs.has(context, threadId, Prefs.CHAT_COLOR)) {
            return ChatPrefs.integer(context, threadId, Prefs.CHAT_COLOR);
        }
        if (Prefs.bool(context, Prefs.AUTO_COLOR) && recipients != null && !recipients.isEmpty()) {
            return avatarColor(recipients.size() == 1 ? recipients.get(0) : String.valueOf(threadId));
        }
        return 0;
    }

    /** The color for a chat's notification. */
    static int chatColor(Context context, long threadId, List<String> recipients) {
        int c = chatAccent(context, threadId, recipients);
        return c != 0 ? c : Prefs.accent(context);
    }

    /** Changes whenever anything that needs a screen rebuild changes. */
    public String key() {
        return mode + ":" + dark + ":" + accent + ":" + bubbleOut + ":" + bubbleIn + ":" + bubbleInText
                + ":" + bubbleOutText + ":" + shape + ":" + font + ":" + textSize;
    }

    // ---------------------------------------------------------------- text

    public float messageTextSize() {
        return SIZES_SP[Math.max(0, Math.min(SIZES_SP.length - 1, textSize))];
    }

    public Typeface typeface() {
        switch (font) {
            case 1: return Typeface.SERIF;
            case 2: return Typeface.MONOSPACE;
            case 3: return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case 4: return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case 5: return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            default: return Typeface.DEFAULT;
        }
    }

    // ---------------------------------------------------------------- bubbles

    /** A bubble background in the chosen shape. Tails point at the sender's side. */
    public GradientDrawable bubble(Context c, int color, boolean incoming, boolean lastInRun) {
        float r = Format.dp(c, 18);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        switch (shape) {
            case 1: {
                float small = lastInRun ? Format.dp(c, 4) : r;
                // top-left, top-right, bottom-right, bottom-left
                d.setCornerRadii(incoming
                        ? new float[]{r, r, r, r, r, r, small, small}
                        : new float[]{r, r, r, r, small, small, r, r});
                break;
            }
            case 2:
                d.setCornerRadius(Format.dp(c, 6));
                break;
            case 3:
                d.setCornerRadius(Format.dp(c, 100));
                break;
            default:
                d.setCornerRadius(r);
                break;
        }
        return d;
    }

    // ---------------------------------------------------------------- colors

    /** Dark text on light colors, white text on dark ones. */
    public static int textOn(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF202124 : Color.WHITE;
    }

    public static int darker(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.8f;
        return Color.HSVToColor(hsv);
    }

    /** A light color made fit for a dark screen. */
    private static int darken(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 2.5f);
        hsv[2] = 0.28f;
        return Color.HSVToColor(hsv);
    }

    /** A steady color per contact, so the same person always gets the same avatar color. */
    public static int avatarColor(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return COLORS[(hash & 0x7fffffff) % COLORS.length];
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusPx);
        d.setColor(color);
        return d;
    }

    /** True between the chosen night start and end times. */
    static boolean isNight(Context context) {
        Calendar now = Calendar.getInstance();
        int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int from = Prefs.integer(context, Prefs.NIGHT_FROM);
        int to = Prefs.integer(context, Prefs.NIGHT_TO);
        return from <= to ? minutes >= from && minutes < to : minutes >= from || minutes < to;
    }
}
