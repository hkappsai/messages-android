package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * A settings page made of {@link Rows}. The page rebuilds itself after every change and when it
 * comes back on screen, so summaries always show current values.
 */
abstract class SettingsScreen extends BaseActivity {

    private static final int REQUEST_RINGTONE = 70;

    /** The chat being customized, or -1 for app-wide settings. */
    protected long threadId = -1;
    private LinearLayout content;
    private ScrollView scroll;
    private Rows current;
    private String ringtoneKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        threadId = getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, (int) Format.dp(this, 24));
        scroll.addView(content);
        setContentView(scroll);
        showUpButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    protected void refresh() {
        int y = scroll.getScrollY();
        content.removeAllViews();
        current = new Rows(this, content, threadId, this::onSettingChanged);
        build(current);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** Called after a row changed a value. */
    protected void onSettingChanged() {
        refresh();
    }

    protected abstract void build(Rows rows);

    // ---------------------------------------------------------------- shared pickers

    /** Android's ringtone picker for a sound setting. */
    void pickRingtone(String key) {
        ringtoneKey = key;
        String value = (String) current.get(key);
        Uri existing = "default".equals(value) ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                : value == null || value.isEmpty() ? null : Uri.parse(value);
        Intent pick = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing);
        try {
            startActivityForResult(pick, REQUEST_RINGTONE);
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this, "Your phone doesn't currently support this", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RINGTONE && resultCode == RESULT_OK && data != null && ringtoneKey != null) {
            Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            String value;
            if (picked == null) value = "";
            else if (RingtoneManager.isDefault(picked)) value = "default";
            else value = picked.toString();
            current.set(ringtoneKey, value);
        }
    }

    /** A text box dialog. */
    interface OnText {
        void entered(String text);
    }

    void askText(String title, String hint, String value, boolean phone, OnText onText) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        if (phone) field.setInputType(InputType.TYPE_CLASS_PHONE);
        field.setSelection(field.length());
        int pad = (int) Format.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> onText.entered(field.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
