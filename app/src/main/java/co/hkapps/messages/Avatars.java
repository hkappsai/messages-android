package co.hkapps.messages;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.InputStream;

/** Round contact pictures, or a colored circle with the first letter when there is none. */
public final class Avatars {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(64);

    private Avatars() {}

    /** Call off the main thread. */
    public static Bitmap get(Context context, String address, String name, int size, int color) {
        String key = address + "|" + size + "|" + color;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;
        Bitmap photo = photo(context, address, size);
        Bitmap result = photo != null ? round(photo, size) : letter(name, size, color);
        CACHE.put(key, result);
        return result;
    }

    static void clear() {
        CACHE.evictAll();
    }

    /** The contact id for a phone number, or -1. */
    static long contactId(Context context, String address) {
        if (TextUtils.isEmpty(address) || context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return -1;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getLong(0) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Bitmap photo(Context context, String address, int size) {
        long id = contactId(context, address);
        if (id < 0) return null;
        Uri contact = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id);
        try (InputStream in = ContactsContract.Contacts.openContactPhotoInputStream(
                context.getContentResolver(), contact, true)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap round(Bitmap source, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawOval(new RectF(0, 0, size, size), paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int edge = Math.min(source.getWidth(), source.getHeight());
        Rect src = new Rect((source.getWidth() - edge) / 2, (source.getHeight() - edge) / 2,
                (source.getWidth() + edge) / 2, (source.getHeight() + edge) / 2);
        canvas.drawBitmap(source, src, new Rect(0, 0, size, size), paint);
        return out;
    }

    static Bitmap letter(String name, int size, int color) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawOval(new RectF(0, 0, size, size), paint);
        paint.setColor(Look.textOn(color));
        paint.setTextSize(size * 0.42f);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        String text = name == null ? "#" : Format.initial(name);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, paint);
        return out;
    }
}
