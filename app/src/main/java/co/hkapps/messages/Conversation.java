package co.hkapps.messages;

import java.util.List;

/** One row of the conversation list. */
public class Conversation {
    public final long threadId;
    public final List<String> addresses;
    public final String name;
    public final Message latest;
    public final int unreadCount;

    public Conversation(long threadId, List<String> addresses, String name, Message latest, int unreadCount) {
        this.threadId = threadId;
        this.addresses = addresses;
        this.name = name;
        this.latest = latest;
        this.unreadCount = unreadCount;
    }

    public boolean isGroup() {
        return addresses.size() > 1;
    }

    /** Phone number for 1-to-1 chats, used for calling and blocking. */
    public String address() {
        return addresses.isEmpty() ? "" : addresses.get(0);
    }
}
