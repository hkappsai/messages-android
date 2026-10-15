package co.hkapps.messages;

/** The buttons a new-message notification can carry (Textra's "Define notification actions"). */
final class NotifyActions {

    static final String NONE = "none";
    static final String REPLY = "reply";
    static final String QUICK_REPLY = "quick_reply";
    static final String MARK_READ = "mark_read";
    static final String MARK_UNREAD = "mark_unread";
    static final String CALL = "call";
    static final String DELETE_LAST = "delete_last";
    static final String BLOCK = "block";
    static final String OPEN = "open";

    static final String[] VALUES = {REPLY, QUICK_REPLY, MARK_READ, MARK_UNREAD, CALL, DELETE_LAST, BLOCK, OPEN, NONE};
    static final String[] LABELS = {
            "Reply (in the notification)",
            "Quick Reply pop-up",
            "Mark as read",
            "Leave unread",
            "Call",
            "Delete last message",
            "Block",
            "Open app",
            "Not used",
    };

    private NotifyActions() {}

    static String label(String value) {
        switch (value) {
            case REPLY: return "Reply";
            case QUICK_REPLY: return "Quick reply";
            case MARK_READ: return "Mark read";
            case MARK_UNREAD: return "Leave unread";
            case CALL: return "Call";
            case DELETE_LAST: return "Delete";
            case BLOCK: return "Block";
            case OPEN: return "Open";
            default: return "";
        }
    }
}
