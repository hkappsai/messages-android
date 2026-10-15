package co.hkapps.messages;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.List;

/**
 * What happens when a text or picture message arrives, after it is saved: blocking, the open
 * chat, unknown senders, muted chats, the notification, the pop-up and auto-saving pictures.
 * Call off the main thread.
 */
final class Incoming {

    private Incoming() {}

    /** mmsId: the saved MMS row, or -1 for an SMS. */
    static void handle(Context context, long threadId, List<String> recipients, String from, String text, long mmsId) {
        MessageReader reader = new MessageReader(context);
        DebugLog.log(context, "received in chat " + threadId + (mmsId >= 0 ? " (MMS)" : ""));

        // "Auto-block unknown email contacts": texts sent from email addresses by strangers.
        if (Prefs.bool(context, Prefs.AUTO_BLOCK_EMAIL) && from != null && from.contains("@")
                && !reader.isContact(from)) {
            Prefs.setBlocked(context, from, true);
        }
        if (Prefs.isBlocked(context, from) && recipients.size() <= 1) return;
        if (Prefs.isBlocked(context, recipients)) return;

        if (mmsId >= 0 && Prefs.bool(context, Prefs.SAVE_PICS)) {
            Message m = reader.message(true, mmsId);
            if (m != null) Gallery.saveAll(context, m);
        }

        if (threadId != -1 && threadId == ThreadActivity.visibleThreadId) {
            // The user is looking at this chat right now.
            SmsWriter.markRead(context, threadId);
            if (ChatPrefs.bool(context, threadId, Prefs.NOTIFY)) {
                if (ChatPrefs.bool(context, threadId, Prefs.IN_CHAT_SOUND)) Sounds.playNotification(context, threadId);
                if (ChatPrefs.bool(context, threadId, Prefs.IN_CHAT_VIBRATE)) {
                    Vibes.play(context, Vibes.pattern(context, ChatPrefs.string(context, threadId, Prefs.VIBRATE)));
                }
            }
            return;
        }
        if (ChatPrefs.muted(context, threadId)) return;
        alert(context, reader, threadId, recipients, from, text);
    }

    /**
     * "Test notification" in Settings: the newest chat with unread messages alerts exactly as if
     * its last message had just arrived. Returns false when no chat has unread messages.
     */
    static boolean test(Context context) {
        MessageReader reader = new MessageReader(context);
        for (Conversation c : reader.loadConversations()) {
            if (c.unreadCount == 0 || Prefs.isBlocked(context, c.addresses)) continue;
            String from = c.latest.address.isEmpty() ? c.address() : c.latest.address;
            alert(context, reader, c.threadId, c.addresses, from, c.latest.preview());
            return true;
        }
        return false;
    }

    private static void alert(Context context, MessageReader reader, long threadId, List<String> recipients,
                              String from, String text) {
        boolean silent = false;
        if (!reader.isContact(from)) {
            int unknown = Prefs.integer(context, Prefs.UNKNOWN_SENDERS);
            if (unknown == Prefs.UNKNOWN_NONE) return;
            silent = unknown == Prefs.UNKNOWN_SILENT;
        }

        // Work out reply suggestions first, so the notification can offer them.
        if (SmartReplies.available() && Prefs.bool(context, Prefs.SMART_REPLY)) {
            SmartReplies.suggest(context, threadId, reader.loadThread(threadId));
        }
        Notifier.notifyThread(context, threadId, !silent, silent);

        // Our own pop-up, when chosen instead of Android's heads-up.
        boolean locked = Screen.locked(context);
        if (!silent && !locked && Screen.on(context)
                && ChatPrefs.integer(context, threadId, Prefs.HEADSUP) == Prefs.HEADSUP_POPUP) {
            boolean group = recipients.size() > 1;
            String title = ChatPrefs.name(context, threadId);
            if (title == null) title = group ? reader.names(recipients) : reader.lookupName(from);
            String shown = text;
            if (group) shown = MessageReader.firstName(reader.lookupName(from)) + ": " + text;
            if (!ChatPrefs.bool(context, threadId, Prefs.SHOW_CONTACT)) title = "New message";
            if (!ChatPrefs.bool(context, threadId, Prefs.SHOW_TEXT)) shown = "Tap to read";
            Bitmap avatar = Avatars.get(context, group ? title : from, title, (int) Format.dp(context, 40),
                    Look.avatarColor(group ? title : from));
            Popup.show(context, threadId, title, shown, avatar);
        }
    }
}
