package co.hkapps.messages;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads a picked file and shrinks pictures until they fit the carrier's MMS limit. */
public final class MediaPrep {

    /** Thrown when a file can't be made small enough to send. */
    public static final class TooLargeException extends IOException {
        TooLargeException(String message) { super(message); }
    }

    private static final int MAX_READ = 50 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;

    private MediaPrep() {}

    /** Call off the main thread. */
    public static Attachment prepare(Context context, Uri uri, int maxBytes) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String type = resolver.getType(uri);
        if (type == null) type = "application/octet-stream";
        String name = displayName(context, uri);

        byte[] data;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open file");
            data = readLimited(in);
        }

        if (type.startsWith("image/")) {
            boolean gif = type.equals("image/gif");
            if (data.length <= maxBytes && (gif || type.equals("image/jpeg") || type.equals("image/png"))) {
                return new Attachment(type, name, data);
            }
            // Too big, or a format other phones may not show (HEIC, WebP): send a JPEG.
            // A GIF that's too big loses its animation here.
            return new Attachment("image/jpeg", baseName(name) + ".jpg", compress(context, uri, maxBytes));
        }
        if (data.length > maxBytes) {
            throw new TooLargeException("This file is " + (data.length / 1024) + " KB. Your carrier allows "
                    + (maxBytes / 1024) + " KB per message.");
        }
        return new Attachment(type, name, data);
    }

    /** JPEG-encodes a picture, lowering quality and size until it fits. */
    static byte[] compress(Context context, Uri uri, int maxBytes) throws IOException {
        Bitmap bitmap = decode(context, uri, MAX_EDGE);
        int quality = 85;
        while (true) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            if (out.size() <= maxBytes) return out.toByteArray();
            if (quality > 45) {
                quality -= 10;
            } else {
                int w = bitmap.getWidth() * 3 / 4;
                int h = bitmap.getHeight() * 3 / 4;
                if (w < 160 || h < 160) throw new TooLargeException("Picture can't be made small enough");
                bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
                quality = 75;
            }
        }
    }

    /** Decodes upright, with the longest edge at most maxEdge pixels. */
    static Bitmap decode(Context context, Uri uri, int maxEdge) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder reads the camera's rotation tag for us.
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                float scale = Math.min(1f, maxEdge / (float) Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= maxEdge) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("Not a picture");

        int rotation = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (IOException ignored) {
            // No EXIF data: keep as is.
        }
        float scale = Math.min(1f, maxEdge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (rotation == 0 && scale == 1f) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postRotate(rotation);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
            // Fall back to the URI.
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_READ) throw new TooLargeException("File is too large");
        }
        return out.toByteArray();
    }
}
