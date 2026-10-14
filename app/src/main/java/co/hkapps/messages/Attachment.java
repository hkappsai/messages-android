package co.hkapps.messages;

/** A file ready to go into an outgoing MMS. */
public class Attachment {
    public final String contentType;
    public final String name;
    public final byte[] data;

    public Attachment(String contentType, String name, byte[] data) {
        this.contentType = contentType;
        this.name = name;
        this.data = data;
    }

    public boolean isImage() { return contentType.startsWith("image/"); }
    public boolean isAudio() { return contentType.startsWith("audio/"); }
    public boolean isVideo() { return contentType.startsWith("video/"); }
}
