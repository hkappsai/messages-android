package co.hkapps.messages;

import android.provider.Telephony;

/** One SMS row read from the system SMS provider. */
public class Sms {
    public final long id;
    public final long threadId;
    public final String address;
    public final String body;
    public final long date;
    public final int type;
    public final boolean read;
    /** Contact name for the address, or the address itself when no contact matches. */
    public String displayName;
    /** Unread messages in the whole conversation (filled for conversation rows only). */
    public int unreadCount;

    public Sms(long id, long threadId, String address, String body, long date, int type, boolean read) {
        this.id = id;
        this.threadId = threadId;
        this.address = address == null ? "" : address;
        this.body = body == null ? "" : body;
        this.date = date;
        this.type = type;
        this.read = read;
        this.displayName = this.address;
    }

    public boolean incoming() {
        return type == Telephony.Sms.MESSAGE_TYPE_INBOX;
    }

    public boolean failed() {
        return type == Telephony.Sms.MESSAGE_TYPE_FAILED;
    }

    public boolean sending() {
        return type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED;
    }
}
