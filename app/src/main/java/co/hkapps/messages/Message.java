package co.hkapps.messages;

import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;

/** One SMS or MMS, or a scheduled text that hasn't gone out yet. */
public class Message {

    /** Type of a scheduled text. Real types match Telephony.Sms types and MMS message boxes. */
    public static final int TYPE_SCHEDULED = -1;
    /** Type of a text waiting out the send delay. */
    public static final int TYPE_DELAYED = -2;

    public final long id;
    public final boolean mms;
    public final long threadId;
    /** The other person: sender of a received message, or recipient of a sent SMS. */
    public final String address;
    public final String body;
    public final long date;
    public final int type;
    public final boolean read;
    /** Pictures, audio, video and other files (MMS only). */
    public final List<Part> parts = new ArrayList<>();
    /** Reactions other messages left on this one. */
    public final List<Reactions.Entry> reactions = new ArrayList<>();
    /** Sender's name, filled in for received messages in group chats. */
    public String senderName;
    /** SIM the message came in on or went out from, or -1. */
    public int subId = -1;
    /** Delivery report: STATUS_NONE, STATUS_PENDING, STATUS_DELIVERED or STATUS_FAILED. */
    public int status = STATUS_NONE;
    /** Locked messages are skipped by deletes and clean-up. */
    public boolean locked;
    /** When the sender's phone sent it (received messages), or 0. */
    public long dateSent;
    /** MMS size in bytes, or 0. */
    public long size;

    public static final int STATUS_NONE = -1;
    public static final int STATUS_DELIVERED = 0;
    public static final int STATUS_PENDING = 32;
    public static final int STATUS_FAILED = 64;

    public Message(long id, boolean mms, long threadId, String address, String body, long date,
                   int type, boolean read) {
        this.id = id;
        this.mms = mms;
        this.threadId = threadId;
        this.address = address == null ? "" : address;
        this.body = body == null ? "" : body;
        this.date = date;
        this.type = type;
        this.read = read;
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

    public boolean scheduled() {
        return type == TYPE_SCHEDULED;
    }

    public boolean delayed() {
        return type == TYPE_DELAYED;
    }

    /** Not a real message yet: scheduled or waiting out the send delay. */
    public boolean notSentYet() {
        return scheduled() || delayed();
    }

    /** Key used for undo-able deletes and selections: "sms:12" or "mms:7". */
    public String key() {
        return (mms ? "mms:" : "sms:") + id;
    }

    /** Short text for lists and notifications, like "Photo" for a picture with no caption. */
    public String preview() {
        if (!body.isEmpty()) return body;
        if (parts.isEmpty()) return "";
        return parts.get(0).label();
    }
}
