package co.hkapps.messages;

import android.os.Bundle;

/** App-wide notification style (Textra's "Customize notifications"). Chats can override it. */
public class NotificationSettingsActivity extends SettingsScreen {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Notifications");
    }

    @Override
    protected void build(Rows r) {
        r.note("Tip: customize one chat's notifications from its menu > Customize.");
        r.action("Test notification", "Shows the newest unread chat's notification in 3 seconds, so you "
                + "can lock the phone or leave the app first", () -> {
            android.content.Context app = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> SmsWriter.run(() -> {
                if (!Incoming.test(app)) {
                    runOnUiThread(() -> android.widget.Toast.makeText(app, "No unread messages to show",
                            android.widget.Toast.LENGTH_SHORT).show());
                }
            }), 3000);
        });
        Sections.notifications(this, r);
    }
}
