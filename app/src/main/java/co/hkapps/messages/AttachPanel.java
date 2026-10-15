package co.hkapps.messages;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Textra's "+" panel: recent photos with a live camera, emoji, GIFs, and buttons for camera app,
 * contacts, events, location, voice memo, files and send later.
 */
final class AttachPanel extends LinearLayout {

    interface Host {
        void attachUris(List<Uri> uris);
        void attachData(Attachment attachment);
        void sendGif(String url, String preview);
        void openCamera();
        void pickFiles();
        void pickContact();
        void createEvent();
        void sendLocation();
        void recordVoice();
        void scheduleLater();
        void askPermissions(String[] permissions, int requestCode);
        android.widget.EditText input();
        int maxBytes();
    }

    static final int REQUEST_MEDIA = 30;
    static final int REQUEST_CAMERA_TILE = 31;

    private static final int TAB_GALLERY = 0;
    private static final int TAB_EMOJI = 1;
    private static final int TAB_GIF = 2;

    private final Host host;
    private final Look look;
    private final FrameLayout content;
    private final LinearLayout buttons;
    private int tab = -1;

    // Gallery
    private final List<Uri> media = new ArrayList<>();
    private final List<Boolean> isVideo = new ArrayList<>();
    private final Set<Uri> chosen = new LinkedHashSet<>();
    private Button addButton;
    private BaseAdapter galleryAdapter;

    AttachPanel(Context context, Look look, Host host) {
        super(context);
        this.host = host;
        this.look = look;
        setOrientation(VERTICAL);
        setBackgroundColor(look.black ? 0xFF000000 : look.dark ? 0xFF1F1F1F : 0xFFF5F5F5);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(buttons);
        addView(scroll);

        content = new FrameLayout(context);
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Format.dp(context, 280)));

        ImageButton gallery = button(R.drawable.ic_image, "Photos & videos", v -> show(TAB_GALLERY));
        gallery.setOnLongClickListener(v -> {
            attachLatestPhoto();
            return true;
        });
        ImageButton camera = button(R.drawable.ic_camera, "Camera", v -> host.openCamera());
        camera.setOnLongClickListener(v -> {
            host.openCamera();
            return true;
        });
        button(R.drawable.ic_emoji, "Emoji", v -> show(TAB_EMOJI));
        if (Giphy.enabled()) button(R.drawable.ic_gif, "GIFs", v -> show(TAB_GIF));
        button(R.drawable.ic_mic, "Voice memo", v -> host.recordVoice());
        button(R.drawable.ic_contact, "Contact", v -> host.pickContact());
        button(R.drawable.ic_event, "Event", v -> host.createEvent());
        button(R.drawable.ic_location, "Location", v -> host.sendLocation());
        button(R.drawable.ic_attach, "Files", v -> host.pickFiles());
        button(R.drawable.ic_schedule, "Send later", v -> host.scheduleLater());
    }

    private ImageButton button(int icon, String label, View.OnClickListener click) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(icon);
        b.setContentDescription(label);
        b.setTooltipText(label);
        b.setImageTintList(ColorStateList.valueOf(look.dark ? 0xFFBDC1C6 : 0xFF5F6368));
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(click);
        int size = (int) Format.dp(getContext(), 48);
        buttons.addView(b, new LayoutParams(size, size));
        return b;
    }

    /** Opens on the gallery the first time, else on the last tab used. */
    void open() {
        show(tab < 0 ? TAB_GALLERY : tab);
    }

    void showEmoji() {
        show(TAB_EMOJI);
    }

    private void show(int which) {
        tab = which;
        content.removeAllViews();
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setAlpha(1f);
        switch (which) {
            case TAB_EMOJI:
                content.addView(new EmojiPanel(getContext(), host.input(), look));
                break;
            case TAB_GIF:
                content.addView(new GifPanel(getContext(), look, host.maxBytes(), (url, preview) -> host.sendGif(url, preview)));
                break;
            default:
                showGallery();
                break;
        }
    }

    // ---------------------------------------------------------------- gallery

    static String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    static boolean canReadMedia(Context c) {
        if (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                == PackageManager.PERMISSION_GRANTED) return true;
        for (String p : mediaPermissions()) {
            if (c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /** Called by the host after a permission answer. */
    void onPermissionsChanged() {
        if (tab == TAB_GALLERY || tab < 0) show(TAB_GALLERY);
    }

    private void showGallery() {
        Context c = getContext();
        if (!canReadMedia(c)) {
            LinearLayout ask = new LinearLayout(c);
            ask.setOrientation(VERTICAL);
            ask.setGravity(Gravity.CENTER);
            TextView t = new TextView(c);
            t.setText("See your recent photos here");
            t.setGravity(Gravity.CENTER);
            Button allow = new Button(c, null, android.R.attr.borderlessButtonStyle);
            allow.setText("Allow access");
            allow.setTextColor(look.accent);
            allow.setOnClickListener(v -> host.askPermissions(mediaPermissions(), REQUEST_MEDIA));
            Button pick = new Button(c, null, android.R.attr.borderlessButtonStyle);
            pick.setText("Pick from gallery instead");
            pick.setOnClickListener(v -> host.pickFiles());
            ask.addView(t);
            ask.addView(allow);
            ask.addView(pick);
            content.addView(ask);
            return;
        }
        loadMedia();
        FrameLayout frame = new FrameLayout(c);
        GridView grid = new GridView(c);
        grid.setNumColumns(3);
        int gap = (int) Format.dp(c, 2);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        galleryAdapter = new GalleryAdapter();
        grid.setAdapter(galleryAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (cameraCell() && pos == 0) return;
            Uri uri = media.get(pos - (cameraCell() ? 1 : 0));
            if (!chosen.remove(uri)) chosen.add(uri);
            galleryAdapter.notifyDataSetChanged();
            updateAddButton();
        });
        frame.addView(grid);

        addButton = new Button(c);
        addButton.setTextColor(look.onAccent);
        addButton.setBackground(Look.rounded(look.accent, Format.dp(c, 24)));
        addButton.setOnClickListener(v -> {
            List<Uri> list = new ArrayList<>(chosen);
            chosen.clear();
            updateAddButton();
            galleryAdapter.notifyDataSetChanged();
            host.attachUris(list);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int m = (int) Format.dp(c, 12);
        lp.setMargins(m, m, m, m);
        frame.addView(addButton, lp);
        updateAddButton();
        content.addView(frame);
    }

    private boolean cameraCell() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void updateAddButton() {
        if (addButton == null) return;
        addButton.setVisibility(chosen.isEmpty() ? GONE : VISIBLE);
        addButton.setText(chosen.size() == 1 ? "Choose 1" : "Choose " + chosen.size());
    }

    private void loadMedia() {
        media.clear();
        isVideo.clear();
        Uri files = MediaStore.Files.getContentUri("external");
        String where = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
                + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + "," + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        try (Cursor c = getContext().getContentResolver().query(files,
                new String[]{MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE},
                where, null, MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && media.size() < 150) {
                boolean video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                media.add(ContentUris.withAppendedId(base, c.getLong(0)));
                isVideo.add(video);
            }
        } catch (RuntimeException ignored) {
            // No media access.
        }
    }

    /** Long-press on the gallery button: send the newest photo right away (Textra tip). */
    private void attachLatestPhoto() {
        if (!canReadMedia(getContext())) {
            host.askPermissions(mediaPermissions(), REQUEST_MEDIA);
            return;
        }
        try (Cursor c = getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                List<Uri> one = new ArrayList<>();
                one.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                host.attachUris(one);
            }
        } catch (RuntimeException ignored) {
            // Nothing to attach.
        }
    }

    private class GalleryAdapter extends BaseAdapter {
        private View cameraCellView;

        @Override public int getCount() { return media.size() + (cameraCell() ? 1 : 0); }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return cameraCell() && position == 0 ? 0 : 1; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = parent.getContext();
            int h = (int) Format.dp(c, 120);
            if (getItemViewType(position) == 0) {
                // One camera cell only: the grid asks for this view several times while measuring,
                // and two previews would fight over the camera.
                if (cameraCellView != null) return cameraCellView;
                FrameLayout cell = new FrameLayout(c);
                cameraCellView = cell;
                cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
                cell.setBackgroundColor(0xFF000000);
                if (CameraTile.allowed(c)) {
                    CameraTile tile = new CameraTile(c, jpeg -> host.attachData(
                            new Attachment("image/jpeg", "photo-" + System.currentTimeMillis() + ".jpg", jpeg)));
                    cell.addView(tile);
                    ImageButton flip = new ImageButton(c);
                    flip.setImageResource(R.drawable.ic_camera);
                    flip.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
                    flip.setBackground(Look.circle(0x66000000));
                    flip.setContentDescription("Switch camera");
                    flip.setOnClickListener(v -> tile.flip());
                    int s = (int) Format.dp(c, 32);
                    cell.addView(flip, new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END));
                } else {
                    TextView t = new TextView(c);
                    t.setText("📷\nTap for camera");
                    t.setTextColor(0xFFFFFFFF);
                    t.setGravity(Gravity.CENTER);
                    t.setOnClickListener(v -> host.askPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_TILE));
                    cell.addView(t);
                }
                return cell;
            }
            FrameLayout cell = convertView instanceof FrameLayout ? (FrameLayout) convertView : null;
            ImageView image;
            TextView mark;
            if (cell == null) {
                cell = new FrameLayout(c);
                cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
                image = new ImageView(c);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(image);
                mark = new TextView(c);
                mark.setGravity(Gravity.CENTER);
                mark.setTextColor(look.onAccent);
                int s = (int) Format.dp(c, 26);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END);
                lp.setMargins(s / 4, s / 4, s / 4, s / 4);
                cell.addView(mark, lp);
            } else {
                image = (ImageView) cell.getChildAt(0);
                mark = (TextView) cell.getChildAt(1);
            }
            int index = position - (cameraCell() ? 1 : 0);
            Uri uri = media.get(index);
            ImageLoader.load(image, uri, (int) Format.dp(c, 140), isVideo.get(index));
            int order = indexOf(uri);
            if (order >= 0) {
                mark.setText(String.valueOf(order + 1));
                mark.setBackground(Look.circle(look.accent));
                image.setAlpha(0.7f);
            } else {
                mark.setText(isVideo.get(index) ? "▶" : "");
                mark.setBackground(isVideo.get(index) ? Look.circle(0x88000000) : null);
                image.setAlpha(1f);
            }
            return cell;
        }

        private int indexOf(Uri uri) {
            int i = 0;
            for (Uri u : chosen) {
                if (u.equals(uri)) return i;
                i++;
            }
            return -1;
        }
    }
}
