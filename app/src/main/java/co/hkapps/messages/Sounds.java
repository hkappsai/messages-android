package co.hkapps.messages;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;

/** Notification sounds: names for settings, and the short sounds the app plays itself. */
final class Sounds {

    private Sounds() {}

    /** "Default (Pixie Dust)", "Silent" or the ringtone's title. */
    static String title(Context c, String value) {
        if (value == null || "default".equals(value)) {
            Ringtone r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            String name = r == null ? null : safeTitle(c, r);
            if (name == null) return "Default";
            // Some phones already call it "Default (Pixie Dust)".
            return name.startsWith("Default") ? name : "Default (" + name + ")";
        }
        if (value.isEmpty()) return "Silent";
        Ringtone r = RingtoneManager.getRingtone(c, Uri.parse(value));
        String name = r == null ? null : safeTitle(c, r);
        return name == null ? "Unknown" : name;
    }

    private static String safeTitle(Context c, Ringtone r) {
        try {
            return r.getTitle(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The chat's notification sound, played while the chat is open ("In chat screen"). */
    static void playNotification(Context c, long threadId) {
        String value = ChatPrefs.string(c, threadId, Prefs.SOUND);
        if ("".equals(value)) return;
        Uri uri = value == null || "default".equals(value)
                ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(value);
        try {
            Ringtone r = RingtoneManager.getRingtone(c, uri);
            if (r == null) return;
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            r.play();
        } catch (RuntimeException ignored) {
            // No sound is fine.
        }
    }

    /** A short "whoosh" after sending, when "Play sent sound" is on. */
    static void playSent(Context c) {
        if (!Prefs.bool(c, Prefs.SENT_SOUND)) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tone::release, 400);
        } catch (RuntimeException ignored) {
            // Audio busy.
        }
    }
}
