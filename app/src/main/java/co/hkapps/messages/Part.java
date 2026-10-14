package co.hkapps.messages;

import android.net.Uri;

/** One attachment of a saved MMS. */
public class Part {
    public final long id;
    public final String contentType;
    public final String name;
    public final Uri uri;

    public Part(long id, String contentType, String name) {
        this.id = id;
        this.contentType = contentType == null ? "application/octet-stream" : contentType.toLowerCase();
        this.name = name;
        this.uri = Uri.parse("content://mms/part/" + id);
    }

    public boolean isImage() { return contentType.startsWith("image/"); }
    public boolean isGif() { return contentType.equals("image/gif"); }
    public boolean isAudio() { return contentType.startsWith("audio/") || contentType.equals("application/ogg"); }
    public boolean isVideo() { return contentType.startsWith("video/"); }
    public boolean isContact() { return contentType.contains("vcard"); }

    public String label() {
        if (isGif()) return "GIF";
        if (isImage()) return "Photo";
        if (isAudio()) return "Voice message";
        if (isVideo()) return "Video";
        if (isContact()) return "Contact card";
        return name != null ? name : "Attachment";
    }
}
