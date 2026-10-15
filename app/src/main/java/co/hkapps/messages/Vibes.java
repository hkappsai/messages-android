package co.hkapps.messages;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;

/** Vibration patterns for notifications, including ones the user tapped out themselves. */
final class Vibes {

    static final String DEFAULT = "default";
    static final String NONE = "none";
    static final String CUSTOM_PREFIX = "custom:";

    static final String[] IDS = {
            DEFAULT, NONE, "single_short", "single_medium", "single_long",
            "multiple_short", "multiple_medium", "multiple_long",
    };
    static final String[] LABELS = {
            "Standard", "None", "Single short", "Single medium", "Single long",
            "Multiple short", "Multiple medium", "Multiple long",
    };

    private Vibes() {}

    /** Off/on timings in ms, starting with a pause. Null for the system default, empty for none. */
    static long[] pattern(Context c, String id) {
        if (id == null || DEFAULT.equals(id)) return null;
        switch (id) {
            case NONE: return new long[0];
            case "single_short": return new long[]{0, 150};
            case "single_medium": return new long[]{0, 400};
            case "single_long": return new long[]{0, 900};
            case "multiple_short": return new long[]{0, 120, 100, 120, 100, 120};
            case "multiple_medium": return new long[]{0, 300, 150, 300, 150, 300};
            case "multiple_long": return new long[]{0, 700, 250, 700, 250, 700};
            default:
                if (id.startsWith(CUSTOM_PREFIX)) {
                    try {
                        String saved = AppDb.get(c).pattern(Long.parseLong(id.substring(CUSTOM_PREFIX.length())));
                        if (saved != null) return parse(saved);
                    } catch (NumberFormatException ignored) {
                        // Fall back to the default.
                    }
                }
                return null;
        }
    }

    static String label(Context c, String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) return LABELS[i];
        }
        if (id != null && id.startsWith(CUSTOM_PREFIX)) {
            for (AppDb.Item item : AppDb.get(c).patterns()) {
                if ((CUSTOM_PREFIX + item.id).equals(id)) return item.name;
            }
        }
        return "Standard";
    }

    static long[] parse(String text) {
        String[] bits = text.split(",");
        long[] result = new long[bits.length];
        for (int i = 0; i < bits.length; i++) {
            try {
                result[i] = Long.parseLong(bits[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    static String format(List<Long> timings) {
        StringBuilder sb = new StringBuilder();
        for (long t : timings) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    /** Plays a pattern now, for previews and in-chat alerts. */
    static void play(Context c, long[] pattern) {
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        if (pattern == null) pattern = new long[]{0, 250};
        if (pattern.length == 0) return;
        v.vibrate(VibrationEffect.createWaveform(pattern, -1),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
    }
}
