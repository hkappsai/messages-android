package co.hkapps.messages;

/** One SMS row read from the system SMS provider. */
public class Sms {
    public final long threadId;
    public final String address;
    public final String body;
    public final long date;
    public final boolean incoming;
    public final boolean read;
    /** Contact name for the address, or the address itself when no contact matches. */
    public String displayName;

    public Sms(long threadId, String address, String body, long date, boolean incoming, boolean read) {
        this.threadId = threadId;
        this.address = address == null ? "" : address;
        this.body = body == null ? "" : body;
        this.date = date;
        this.incoming = incoming;
        this.read = read;
        this.displayName = this.address;
    }
}
