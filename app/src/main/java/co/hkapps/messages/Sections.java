package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings sections shared by the app-wide screens and a chat's own Customize screen, laid out
 * like Textra's: notification style, sending, and messages to keep.
 */
final class Sections {

    private Sections() {}

    static final String[] REPEAT_LABELS = {"Never", "Once", "Twice", "3 times", "5 times", "10 times", "50 times"};
    static final Object[] REPEAT_VALUES = {0, 1, 2, 3, 5, 10, 50};
    static final String[] LED_LABELS = {"None", "White", "Red", "Green", "Blue", "Cyan", "Magenta", "Orange"};
    static final Object[] LED_VALUES = {0, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF00FFFF, 0xFFFF00FF, 0xFFFF8800};
    static final String[] HEADSUP_LABELS = {"Android heads-up", "Messages pop-up", "No heads-up"};
    static final Object[] HEADSUP_VALUES = {Prefs.HEADSUP_ANDROID, Prefs.HEADSUP_POPUP, Prefs.HEADSUP_NONE};
    static final String[] POPUP_LABELS = {"Show for 2 secs", "Show for 5 secs", "Show for 10 secs", "Show for 20 secs",
            "Show for 30 secs", "Show for 60 secs", "Show for 3 mins", "Show until dismissed"};
    static final Object[] POPUP_VALUES = {2, 5, 10, 20, 30, 60, 180, 0};
    static final String[] WAKE_LABELS = {"No", "Always", "Yes (except on silent or DND)"};
    static final Object[] WAKE_VALUES = {Prefs.WAKE_NEVER, Prefs.WAKE_ALWAYS, Prefs.WAKE_UNLESS_QUIET};
    static final String[] TAP_LABELS = {"Full app", "Quick Reply with keyboard", "Quick Reply no keyboard"};
    static final Object[] TAP_VALUES = {Prefs.TAP_APP, Prefs.TAP_QUICK_REPLY, Prefs.TAP_QUICK_REPLY_NO_KEYBOARD};
    static final String[] DELAY_LABELS = {"No delay", "1 second", "2 seconds", "3 seconds", "4 seconds", "5 seconds",
            "6 seconds", "7 seconds", "8 seconds", "9 seconds"};
    static final Object[] DELAY_VALUES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    static final String[] LONG_LABELS = {"No", "When > 1 SMS", "When > 2 SMS", "When > 3 SMS"};
    static final Object[] LONG_VALUES = {0, 1, 2, 3};
    static final String[] ACCENT_LABELS = {"Never", "Always", "Only when fewer SMS"};
    static final Object[] ACCENT_VALUES = {Prefs.ACCENTS_NEVER, Prefs.ACCENTS_ALWAYS, Prefs.ACCENTS_WHEN_CHEAPER};
    static final String[] KEEP_LABELS = {"All", "25", "50", "100", "250", "500", "1000", "2500", "5000"};
    static final Object[] KEEP_VALUES = {0, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    // ---------------------------------------------------------------- notifications

    static void notifications(SettingsScreen s, Rows r) {
        long thread = s.threadId;
        r.header("Notifications");
        r.toggle("Notifications", r.perChat() ? "Turn off to mute this chat" : "Show new messages", Prefs.NOTIFY);
        r.row("Notification sound", Sounds.title(s, (String) r.get(Prefs.SOUND))
                        + (r.perChat() && !ChatPrefs.has(s, thread, Prefs.SOUND) ? " (default)" : ""), null,
                v -> s.pickRingtone(Prefs.SOUND));
        if (r.perChat() && ChatPrefs.has(s, thread, Prefs.SOUND)) {
            r.action("Use default sound", null, () -> r.set(Prefs.SOUND, null));
        }
        vibrate(s, r);
        r.choice("LED blink color", "On phones with a notification light", Prefs.LED, LED_LABELS, LED_VALUES);
        r.choice("Repeat notifications", "Every 2 minutes until read", Prefs.REPEAT, REPEAT_LABELS, REPEAT_VALUES);

        r.header("Heads-up notifications");
        r.note("Heads-up notifications slide down from the top of the screen. The Messages pop-up also "
                + "shows on the lock screen, which Android's doesn't, and lets you reply right there.");
        r.choice("Style when unlocked", Prefs.HEADSUP, HEADSUP_LABELS, HEADSUP_VALUES);
        if ((Integer) r.get(Prefs.HEADSUP) == Prefs.HEADSUP_POPUP) {
            if (!Popup.allowed(s)) {
                r.action("Allow \"Display over other apps\"", "Needed for the Messages pop-up. Tap, then turn it on "
                        + "for Messages.", () -> s.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", s.getPackageName(), null))));
            }
            r.choice("Pop-up when unlocked", Prefs.POPUP_SECS, POPUP_LABELS, POPUP_VALUES);
        }
        r.choice("Style when locked", Prefs.HEADSUP_LOCKED, HEADSUP_LABELS, HEADSUP_VALUES);
        if ((Integer) r.get(Prefs.HEADSUP_LOCKED) == Prefs.HEADSUP_POPUP && Build.VERSION.SDK_INT >= 34
                && !Notifier.manager(s).canUseFullScreenIntent()) {
            r.action("Allow full-screen notifications", "Needed to show the pop-up on the lock screen",
                    () -> s.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.fromParts("package", s.getPackageName(), null))));
        }
        r.choice("Wake up screen", Prefs.WAKE, WAKE_LABELS, WAKE_VALUES);

        r.header("Privacy");
        r.toggle("Show contact", "Name and picture in notifications", Prefs.SHOW_CONTACT);
        r.toggle("Show message text", "Text in notifications and pop-ups", Prefs.SHOW_TEXT);

        r.header("Notification actions");
        r.choice("Tapping opens", Prefs.TAP_OPENS, TAP_LABELS, TAP_VALUES);
        r.choice("Button 1", Prefs.BUTTON_1, NotifyActions.LABELS, NotifyActions.VALUES);
        r.choice("Button 2", Prefs.BUTTON_2, NotifyActions.LABELS, NotifyActions.VALUES);
        r.choice("Button 3", Prefs.BUTTON_3, NotifyActions.LABELS, NotifyActions.VALUES);
        r.toggle("Clearing marks as read", "Mark message(s) as read when clearing the notification", Prefs.CLEAR_MARKS_READ);
        if (!r.perChat()) {
            r.toggle("Auto close Quick Reply", "Close the Quick Reply pop-up after sending", Prefs.AUTO_CLOSE_QR);
        }

        r.header("In chat screen");
        r.toggle("Play notification sound", "When a message arrives in the open chat", Prefs.IN_CHAT_SOUND);
        r.toggle("Vibrate", "When a message arrives in the open chat", Prefs.IN_CHAT_VIBRATE);

        if (!r.perChat()) {
            r.header("Android");
            r.action("Android notification settings", "Importance, dots and Do Not Disturb exceptions",
                    () -> s.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, s.getPackageName())));
        }
    }

    private static void vibrate(SettingsScreen s, Rows r) {
        List<String> labels = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < Vibes.IDS.length; i++) {
            labels.add(Vibes.LABELS[i]);
            values.add(Vibes.IDS[i]);
        }
        for (AppDb.Item p : AppDb.get(s).patterns()) {
            labels.add(p.name);
            values.add(Vibes.CUSTOM_PREFIX + p.id);
        }
        r.choice("Vibrate pattern", Prefs.VIBRATE, labels.toArray(new String[0]), values.toArray());
        r.action("Make custom pattern…", "Tap the rhythm you want", () ->
                s.startActivity(new Intent(s, VibratePatternActivity.class)
                        .putExtra(ThreadActivity.EXTRA_THREAD_ID, s.threadId)));
    }

    // ---------------------------------------------------------------- sending

    static void sending(SettingsScreen s, Rows r, boolean group) {
        r.header("Sending");
        if (r.perChat() && Sims.multi(s)) {
            List<Sims.Sim> sims = Sims.active(s);
            String[] labels = new String[sims.size() + 1];
            Object[] values = new Object[sims.size() + 1];
            labels[0] = "Same SIM the last message came in on";
            values[0] = -1;
            for (int i = 0; i < sims.size(); i++) {
                labels[i + 1] = sims.get(i).label();
                values[i + 1] = sims.get(i).subId;
            }
            r.choice("SIM", Prefs.SIM, labels, values);
        }
        long sig = (Long) r.get(Prefs.SIGNATURE);
        String sigText = sig < 0 ? null : AppDb.get(s).signature(sig);
        r.action("Signature", sigText == null ? "None" : sigText, () ->
                s.startActivity(new Intent(s, SignaturesActivity.class).putExtra(ThreadActivity.EXTRA_THREAD_ID, s.threadId)));
        r.choice("Send delay", "Time to change your mind: tap the message to cancel", Prefs.SEND_DELAY,
                DELAY_LABELS, DELAY_VALUES);
        if (!r.perChat() || group) {
            r.choice("Group messaging", Prefs.GROUP_MMS,
                    new String[]{"Chat: one MMS, replies go to everyone", "Bulk: separate texts, replies only to you"},
                    new Object[]{true, false});
        }
        r.header("SMS");
        r.toggle("Delivery reports for SMS", "Show delivery status: Sent or Delivered", Prefs.DELIVERY_SMS);
        r.choice("Convert long SMS to MMS", "Some carriers split long messages into parts that arrive out of order",
                Prefs.LONG_SMS_AS_MMS, LONG_LABELS, LONG_VALUES);
        r.choice("Strip accents & diacritics", "Remove accents before sending, to fit more in each SMS",
                Prefs.STRIP_ACCENTS, ACCENT_LABELS, ACCENT_VALUES);
        r.toggle("Split long SMS messages", "If your carrier fails to send long messages, try splitting them",
                Prefs.SPLIT_SMS);
        r.header("MMS");
        r.toggle("Delivery reports for MMS", "Show delivery status: Sent or Delivered", Prefs.DELIVERY_MMS);
    }

    // ---------------------------------------------------------------- clean-up

    static void keep(SettingsScreen s, Rows r) {
        r.header("Messages to keep");
        r.choice("Text messages to keep", r.perChat() ? "For this chat" : "Per chat; the oldest go first",
                Prefs.KEEP_TEXT, KEEP_LABELS, KEEP_VALUES);
        r.choice("Media messages to keep", r.perChat() ? "For this chat" : "Per chat; the oldest go first",
                Prefs.KEEP_MEDIA, KEEP_LABELS, KEEP_VALUES);
        r.action("Free space now", "Delete messages over the limit right away (locked messages stay)", () -> {
            if (!DefaultSms.isDefault(s)) {
                DefaultSms.prompt(s);
                return;
            }
            new AlertDialog.Builder(s)
                    .setTitle("Free space now?")
                    .setMessage("Older messages over the limits above will be deleted.")
                    .setPositiveButton("Delete", (d, w) -> SmsWriter.run(() -> {
                        int n = Cleanup.run(s.getApplicationContext(), s.threadId);
                        s.runOnUiThread(() -> Toast.makeText(s, n == 1 ? "Deleted 1 message." : "Deleted " + n + " messages.",
                                Toast.LENGTH_SHORT).show());
                    }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }
}
