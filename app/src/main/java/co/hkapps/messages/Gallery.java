package co.hkapps.messages;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Saves pictures and videos from messages into the phone's gallery (Pictures/Messages). */
final class Gallery {

    private Gallery() {}

    /** Every picture and video of a message. Call off the main thread. */
    static int saveAll(Context context, Message m) {
        int saved = 0;
        for (Part p : m.parts) {
            if ((p.isImage() || p.isVideo()) && save(context, p) != null) saved++;
        }
        return saved;
    }

    /** One part. Returns its gallery URI, or null. Call off the main thread. */
    static Uri save(Context context, Part p) {
        byte[] data = Mms.read(context, p.uri);
        if (data == null) return null;
        return save(context, data, p.contentType, p.name);
    }

    static Uri save(Context context, byte[] data, String type, String name) {
        boolean video = type.startsWith("video/");
        if (!video && !type.startsWith("image/")) return null;
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        String file = "MSG_" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
        ContentResolver resolver = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
        v.put(MediaStore.MediaColumns.MIME_TYPE, type);
        Uri collection;
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Messages");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            collection = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            // Android 8-9: write the file ourselves and register it.
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Messages");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, file);
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(data);
            } catch (IOException | SecurityException e) {
                return null;
            }
            v.put(MediaStore.MediaColumns.DATA, out.getPath());
            collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            return resolver.insert(collection, v);
        }
        Uri uri;
        try {
            uri = resolver.insert(collection, v);
        } catch (RuntimeException e) {
            return null;
        }
        if (uri == null) return null;
        try (OutputStream o = resolver.openOutputStream(uri)) {
            if (o == null) return null;
            o.write(data);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            return null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }
}
