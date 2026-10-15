package co.hkapps.messages;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;

/** Lock-screen state and "Wake up screen" for new messages. */
final class Screen {

    private Screen() {}

    static boolean locked(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    static boolean on(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    /** Phone on silent, vibrate or Do Not Disturb. */
    static boolean quiet(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    @SuppressWarnings("deprecation")
    static void wakeIfWanted(Context c, long threadId) {
        int wake = ChatPrefs.integer(c, threadId, Prefs.WAKE);
        if (wake == Prefs.WAKE_NEVER || on(c)) return;
        if (wake == Prefs.WAKE_UNLESS_QUIET && quiet(c)) return;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm == null) return;
        // The only way an app can still turn the screen on for a moment.
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "messages:wake");
        lock.acquire(5000);
    }
}
