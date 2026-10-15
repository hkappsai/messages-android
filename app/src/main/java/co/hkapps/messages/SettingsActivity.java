package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/** Settings home, laid out like Textra's: General, Messaging, More stuff, About. */
public class SettingsActivity extends SettingsScreen {

    static final String[] UNKNOWN_LABELS = {"Show normally", "Show silently", "No notification"};
    static final Object[] UNKNOWN_VALUES = {Prefs.UNKNOWN_NORMAL, Prefs.UNKNOWN_SILENT, Prefs.UNKNOWN_NONE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings);
    }

    @Override
    protected void build(Rows r) {
        boolean isDefault = DefaultSms.isDefault(this);
        if (!isDefault) {
            r.action("Make default app", "Messages needs to be the default SMS app to send and receive",
                    () -> DefaultSms.request(this));
        }

        r.header("General");
        r.action("Customize look", "Colors, bubbles, font and emoji", () -> open(LookSettingsActivity.class));
        r.action("Notifications", "Sound, vibration, pop-ups, buttons and privacy", () -> open(NotificationSettingsActivity.class));
        r.action("Blocklist", Prefs.blocked(this).size() + " blocked", () -> open(BlocklistActivity.class));

        r.header("Messaging");
        r.action("Sending, SMS & MMS", "Delivery reports, signature, send delay, group messages, media",
                () -> open(MessagingSettingsActivity.class));

        r.header("More stuff");
        r.toggle("Quick Compose", "Send messages from the pull-down notification drawer", Prefs.QUICK_COMPOSE);
        r.toggle("Badge app icon", "Show unread message count on the app icon", Prefs.BADGE);
        r.choice("Unknown contact notifications", "Texts from numbers that are not in your contacts",
                Prefs.UNKNOWN_SENDERS, UNKNOWN_LABELS, UNKNOWN_VALUES);
        r.toggle("Auto-block unknown email contacts",
                "Blocklist chats from email addresses that are not in your contacts", Prefs.AUTO_BLOCK_EMAIL);
        String number = Prefs.string(this, Prefs.MY_NUMBER);
        r.action("Your mobile number", number.isEmpty() ? "[Not set] Used to leave you out of group chats" : number,
                () -> askText("Enter your mobile number", "Used to identify you in MMS group messaging", number, true,
                        text -> {
                            Prefs.put(this, Prefs.MY_NUMBER, text);
                            refresh();
                        }));
        Sections.keep(this, r);
        r.header("Backup");
        r.action("Back up & restore", "Save settings and messages to a file, or bring them back",
                () -> open(BackupActivity.class));
        r.action("Mark all as read", null, () -> {
            if (!isDefault) {
                Toast.makeText(this, "To mark all as read, Messages needs to be the default app.", Toast.LENGTH_SHORT).show();
                return;
            }
            Context app = getApplicationContext();
            SmsWriter.run(() -> SmsWriter.markAllRead(app));
            Notifier.cancelAllMessages(app);
            Toast.makeText(this, "Marked all as read", Toast.LENGTH_SHORT).show();
        });

        r.header("Debug");
        r.toggle("Enable debug logging", "Keeps a short log of sending and receiving (a tiny bit slower)", Prefs.DEBUG_LOG);
        r.action("Share debug log", "Send it as soon as you've reproduced a problem", () -> executor.execute(() -> {
            String log = AppDb.get(this).logText();
            onUi(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Messages debug log")
                    .putExtra(Intent.EXTRA_TEXT, log.isEmpty() ? "(empty: turn on debug logging first)" : log), null)));
        }));

        r.header("About");
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Leave blank.
        }
        String shown = version;
        r.action("About", "Version " + shown, () -> new AlertDialog.Builder(this)
                .setTitle("Messages " + shown)
                .setMessage("A fast, private messaging app built only on the Android framework. "
                        + "Your messages stay on your phone. The only internet use is web link previews and GIF "
                        + "search, and only when you use them.\n\nCopyright 2026 HK Apps. Apache License 2.0.")
                .setPositiveButton(android.R.string.ok, null)
                .show());
        r.action("What's new?", null, () -> new AlertDialog.Builder(this)
                .setTitle("What's new")
                .setMessage(WhatsNew.TEXT)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    @Override
    protected void onSettingChanged() {
        Notifier.updateQuickCompose(this);
        super.onSettingChanged();
    }

    private void open(Class<?> screen) {
        startActivity(new Intent(this, screen));
    }
}
