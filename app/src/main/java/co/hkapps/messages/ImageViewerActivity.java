package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen pictures: pinch or double-tap to zoom, swipe to the next picture in the chat,
 * save to gallery, share, and media details (Textra's gallery screen).
 */
public class ImageViewerActivity extends BaseActivity {

    static final String EXTRA_TYPE = "type";

    private final List<Part> pictures = new ArrayList<>();
    private int index;
    private ImageView image;
    private TextView counter;
    private View bar;
    private float scale = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x66000000);
        int pad = (int) Format.dp(this, 8);
        top.setPadding(pad, (int) Format.dp(this, 28), pad, pad);
        top.addView(button(R.drawable.ic_close, "Close", v -> finish()));
        counter = new TextView(this);
        counter.setTextColor(0xFFFFFFFF);
        counter.setPadding(pad, 0, pad, 0);
        top.addView(counter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(button(R.drawable.ic_save, "Save to gallery", v -> save()));
        top.addView(button(R.drawable.ic_share, "Share", v -> share()));
        top.addView(button(R.drawable.ic_info, "Media details", v -> details()));
        bar = top;
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);

        Uri start = getIntent().getData();
        String type = getIntent().getStringExtra(EXTRA_TYPE);
        pictures.add(new Part(-1, type == null ? "image/*" : type, null, start));
        show();
        setUpGestures(root);

        long threadId = getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        if (threadId > 0) {
            executor.execute(() -> {
                List<Part> all = new ArrayList<>();
                List<Message> media = new MessageReader(this).media(threadId);
                // Oldest first, so swiping left goes forward in time like the chat.
                for (int i = media.size() - 1; i >= 0; i--) {
                    for (Part p : media.get(i).parts) if (p.isImage()) all.add(p);
                }
                onUi(() -> {
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).uri.equals(start)) {
                            pictures.clear();
                            pictures.addAll(all);
                            index = i;
                            show();
                            return;
                        }
                    }
                });
            });
        }
    }

    private ImageButton button(int icon, String label, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        b.setBackground(null);
        b.setOnClickListener(click);
        int s = (int) Format.dp(this, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private void show() {
        scale = 1f;
        image.setScaleX(1f);
        image.setScaleY(1f);
        image.setTranslationX(0);
        image.setTranslationY(0);
        int edge = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        ImageLoader.load(image, pictures.get(index).uri, edge, false);
        counter.setText(pictures.size() > 1 ? (index + 1) + " of " + pictures.size() : "");
    }

    private void setUpGestures(View root) {
        ScaleGestureDetector scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1f, Math.min(5f, scale * d.getScaleFactor()));
                image.setScaleX(scale);
                image.setScaleY(scale);
                return true;
            }
        });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                scale = scale > 1f ? 1f : 2.5f;
                image.animate().scaleX(scale).scaleY(scale).translationX(0).translationY(0).setDuration(150).start();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale > 1f) {
                    image.setTranslationX(image.getTranslationX() - dx);
                    image.setTranslationY(image.getTranslationY() - dy);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scale > 1f || e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < Math.abs(e2.getY() - e1.getY())) {
                    if (Math.abs(e2.getY() - e1.getY()) > Format.dp(ImageViewerActivity.this, 120)) finish();
                    return true;
                }
                if (dx < 0 && index < pictures.size() - 1) index++;
                else if (dx > 0 && index > 0) index--;
                else return false;
                show();
                return true;
            }
        });
        root.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) gestures.onTouchEvent(e);
            return true;
        });
    }

    private void save() {
        Part p = pictures.get(index);
        executor.execute(() -> {
            byte[] data = Mms.read(this, p.uri);
            Uri saved = data == null ? null : Gallery.save(this, data, typeOf(p, data), p.name);
            onUi(() -> Toast.makeText(this, saved != null ? "Saved to gallery"
                    : "Something went wrong saving to the gallery.", Toast.LENGTH_SHORT).show());
        });
    }

    private void share() {
        Part p = pictures.get(index);
        executor.execute(() -> {
            byte[] data = Mms.read(this, p.uri);
            if (data == null) return;
            String type = typeOf(p, data);
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
            File file = new File(MmsFileProvider.dir(this, MmsFileProvider.DIR_SHARE),
                    "picture_" + System.currentTimeMillis() + "." + (ext == null ? "jpg" : ext));
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(data);
            } catch (Exception e) {
                return;
            }
            Uri uri = MmsFileProvider.uriFor(this, file);
            onUi(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType(type)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newRawUri("picture", uri));
                startActivity(Intent.createChooser(send, null));
            });
        });
    }

    private void details() {
        Part p = pictures.get(index);
        executor.execute(() -> {
            byte[] data = Mms.read(this, p.uri);
            if (data == null) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            String text = "Media type: " + typeOf(p, data)
                    + "\nResolution: " + o.outWidth + " × " + o.outHeight
                    + "\nSize: " + Formatter.formatShortFileSize(this, data.length);
            onUi(() -> new AlertDialog.Builder(this)
                    .setTitle("Media details")
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        });
    }

    private static String typeOf(Part p, byte[] data) {
        if (p.contentType != null && !p.contentType.endsWith("*")) return p.contentType;
        if (data.length > 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
        if (data.length > 3 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') return "image/png";
        return "image/jpeg";
    }
}
