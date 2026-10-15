package co.hkapps.messages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads pictures, GIFs and video thumbnails into ImageViews off the main thread, with a cache. */
final class ImageLoader {

    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Drawable> CACHE = new LruCache<String, Drawable>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            if (value instanceof BitmapDrawable && ((BitmapDrawable) value).getBitmap() != null) {
                return ((BitmapDrawable) value).getBitmap().getByteCount();
            }
            // Animated GIFs decode frame by frame; count them as a fixed cost.
            return 2 * 1024 * 1024;
        }
    };

    private ImageLoader() {}

    /** Shows the picture at uri, at most maxPx on its longest edge. Video gets a still frame. */
    static void load(ImageView view, Uri uri, int maxPx, boolean video) {
        String key = uri + "@" + maxPx;
        view.setTag(key);
        Drawable cached = CACHE.get(key);
        if (cached != null) {
            show(view, cached);
            return;
        }
        view.setImageDrawable(null);
        Context context = view.getContext().getApplicationContext();
        DECODER.execute(() -> {
            Drawable d = video ? videoFrame(context, uri, maxPx) : decode(context, uri, maxPx);
            if (d == null) return;
            CACHE.put(key, d);
            view.post(() -> {
                if (key.equals(view.getTag())) show(view, d);
            });
        });
    }

    /** Shows a picture or GIF from the web (GIF search results). */
    static void loadUrl(ImageView view, String url, int maxPx) {
        String key = url + "@" + maxPx;
        view.setTag(key);
        Drawable cached = CACHE.get(key);
        if (cached != null) {
            show(view, cached);
            return;
        }
        view.setImageDrawable(null);
        Context context = view.getContext().getApplicationContext();
        DECODER.execute(() -> {
            Drawable d;
            try {
                byte[] data = Giphy.get(url, 8 * 1024 * 1024);
                d = decodeBytes(context, data, maxPx);
            } catch (Exception | OutOfMemoryError e) {
                d = null;
            }
            if (d == null) return;
            CACHE.put(key, d);
            Drawable ready = d;
            view.post(() -> {
                if (key.equals(view.getTag())) show(view, ready);
            });
        });
    }

    static Drawable decodeBytes(Context context, byte[] data, int maxPx) throws java.io.IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data));
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                float scale = Math.min(1f, maxPx / (float) Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            });
        }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= maxPx) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return b == null ? null : new BitmapDrawable(context.getResources(), b);
    }

    private static void show(ImageView view, Drawable d) {
        view.setImageDrawable(d);
        if (d instanceof Animatable) ((Animatable) d).start();
    }

    private static Drawable decode(Context context, Uri uri, int maxPx) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // decodeDrawable returns an animated drawable for GIFs.
                ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
                return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    float scale = Math.min(1f, maxPx / (float) Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                });
            }
            Bitmap bitmap = MediaPrep.decode(context, uri, maxPx);
            return new BitmapDrawable(context.getResources(), bitmap);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Drawable videoFrame(Context context, Uri uri, int maxPx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getFrameAtTime();
            if (frame == null) return null;
            float scale = Math.min(1f, maxPx / (float) Math.max(frame.getWidth(), frame.getHeight()));
            if (scale < 1f) {
                frame = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale),
                        Math.round(frame.getHeight() * scale), true);
            }
            return new BitmapDrawable(context.getResources(), frame);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // release() declares IOException on newer Android.
            }
        }
    }
}
