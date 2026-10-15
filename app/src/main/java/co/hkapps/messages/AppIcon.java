package co.hkapps.messages;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * "App icon color": the launcher shows one of several activity aliases, each with its own icon.
 * Only one alias is turned on at a time.
 */
final class AppIcon {

    static final String[] NAMES = {"Blue", "Purple", "Pink", "Red", "Orange", "Green", "Teal", "Black"};
    static final int[] COLORS = {0xFF1A73E8, 0xFF8E24AA, 0xFFD81B60, 0xFFE53935, 0xFFF4511E, 0xFF43A047,
            0xFF00897B, 0xFF202124};
    private static final String[] ALIASES = {".Launcher", ".LauncherPurple", ".LauncherPink", ".LauncherRed",
            ".LauncherOrange", ".LauncherGreen", ".LauncherTeal", ".LauncherBlack"};

    private AppIcon() {}

    static int current(Context c) {
        return Math.max(0, Math.min(ALIASES.length - 1, Prefs.integer(c, Prefs.ICON_COLOR)));
    }

    static void set(Context c, int index) {
        PackageManager pm = c.getPackageManager();
        // Turn the new one on first, so there is never a moment with no launcher icon.
        pm.setComponentEnabledSetting(component(c, index), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        for (int i = 0; i < ALIASES.length; i++) {
            if (i == index) continue;
            pm.setComponentEnabledSetting(component(c, i), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        Prefs.put(c, Prefs.ICON_COLOR, index);
    }

    private static ComponentName component(Context c, int index) {
        return new ComponentName(c.getPackageName(), c.getPackageName() + ALIASES[index]);
    }
}
