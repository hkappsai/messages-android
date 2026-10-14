package co.hkapps.messages;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Colors for the current theme settings: light/dark/black mode plus the chosen accent. */
public final class Look {

    /** Choices offered for the accent and sent bubble colors. */
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

    private Look(Context context) {
        mode = Prefs.themeMode(context);
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = mode == Prefs.MODE_DARK || mode == Prefs.MODE_BLACK
                || (mode == Prefs.MODE_SYSTEM && night == Configuration.UI_MODE_NIGHT_YES);
        black = mode == Prefs.MODE_BLACK;

        accent = Prefs.accent(context);
        onAccent = textOn(accent);
        int chosenBubble = Prefs.bubble(context);
        bubbleOut = chosenBubble != 0 ? chosenBubble : accent;
        bubbleOutText = textOn(bubbleOut);

        if (black) {
            bubbleIn = 0xFF1E1E1E;
            bar = Color.BLACK;
            statusBar = Color.BLACK;
            styleRes = R.style.AppTheme_Black;
        } else if (dark) {
            bubbleIn = 0xFF303134;
            bar = 0xFF1F1F1F;
            statusBar = 0xFF1F1F1F;
            styleRes = R.style.AppTheme_Dark;
        } else {
            bubbleIn = 0xFFECEFF1;
            bar = accent;
            statusBar = darker(accent);
            styleRes = R.style.AppTheme_Light;
        }
        bubbleInText = dark ? 0xFFE8EAED : 0xFF202124;
    }

    public static Look from(Context context) {
        return new Look(context);
    }

    /** Changes whenever anything that needs a screen rebuild changes. */
    public String key() {
        return mode + ":" + dark + ":" + accent + ":" + bubbleOut;
    }

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
}
