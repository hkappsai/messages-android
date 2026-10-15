package co.hkapps.messages;

import android.os.Bundle;

/** Sending, SMS and MMS settings. */
public class MessagingSettingsActivity extends SettingsScreen {

    private static final String[] LIMIT_LABELS = {"Carrier's limit", "100 KB", "300 KB", "600 KB", "1 MB", "2 MB",
            "5 MB"};
    private static final Object[] LIMIT_VALUES = {0, 100, 300, 600, 1024, 2048, 5120};
    private static final String[] PREVIEW_LABELS = {"Off", "For received messages", "For sent messages", "For all messages"};
    private static final Object[] PREVIEW_VALUES = {Prefs.PREVIEWS_OFF, Prefs.PREVIEWS_RECEIVED, Prefs.PREVIEWS_SENT,
            Prefs.PREVIEWS_ALL};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sending, SMS & MMS");
    }

    @Override
    protected void build(Rows r) {
        Sections.sending(this, r, true);

        r.choice("Carrier send limit", "Pictures and videos are shrunk to fit. Pick a smaller value if your "
                + "carrier fails messages as too large.", Prefs.MMS_LIMIT, LIMIT_LABELS, LIMIT_VALUES);
        r.toggle("Auto save", "Save received pictures and videos to the gallery", Prefs.SAVE_PICS);
        r.toggle("Save storage space", "Keep the shrunk copy of what you send, not the original", Prefs.SAVE_SPACE);
        r.toggle("Video warning", "Ask before opening received videos", Prefs.VIDEO_WARNING);
        r.choice("Web previews", "Show a snippet of the web page a link points to", Prefs.LINK_PREVIEWS,
                PREVIEW_LABELS, PREVIEW_VALUES);

        r.header("Composing");
        r.toggle("Send using Enter", "The Enter key sends instead of starting a new line", Prefs.SEND_ENTER);
        r.toggle("Close keyboard after sending", null, Prefs.CLOSE_KEYBOARD);
        r.toggle("Quick voice memos", "With nothing typed, the send button records a voice memo", Prefs.VOICE_MEMO);
        r.toggle("Show SMS character counter", "Once a message gets long", Prefs.SMS_COUNTER);
        if (SmartReplies.available()) {
            r.toggle("Smart Reply", "Suggested replies, worked out on your phone; nothing leaves it", Prefs.SMART_REPLY);
        }
        r.toggle("Play sent sound", null, Prefs.SENT_SOUND);
        r.toggle("Mobile contacts only", "When starting a new message, only show mobile numbers", Prefs.MOBILE_ONLY);
    }
}
