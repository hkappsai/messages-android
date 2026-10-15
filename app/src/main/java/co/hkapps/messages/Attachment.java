package co.hkapps.messages;

/** A file ready to go into an outgoing MMS. */
public class Attachment {
    public final String contentType;
    public final String name;
    public final byte[] data;
    /** The file as picked, when data is a shrunk copy of it. */
    public Attachment original;

    public Attachment(String contentType, String name, byte[] data) {
        this.contentType = contentType;
        this.name = name;
        this.data = data;
    }

    public boolean isImage() { return contentType.startsWith("image/"); }
    public boolean isAudio() { return contentType.startsWith("audio/"); }
    public boolean isVideo() { return contentType.startsWith("video/"); }
    public boolean isContact() { return contentType.contains("vcard"); }
    public boolean isCalendar() { return contentType.contains("calendar"); }
}
