package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Back up settings (and messages) to a file the user picks, and restore from one. */
public class BackupActivity extends SettingsScreen {

    private static final int REQUEST_SAVE = 80;
    private static final int REQUEST_OPEN = 81;

    private boolean withMessages = true;
    private String status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Back up & restore");
    }

    @Override
    protected void build(Rows r) {
        r.note("Settings are also backed up by Android with your Google account. A backup file lets you "
                + "move them, and your messages, to another phone or keep a copy.");
        r.header("Back up");
        r.action("Back up settings and messages", "Pictures included, so the file can be large", () -> save(true));
        r.action("Back up settings only", null, () -> save(false));
        r.header("Restore");
        r.action("Restore from a file", "Messages already on the phone are skipped", this::open);
        if (status != null) r.note(status);
    }

    private void save(boolean messages) {
        withMessages = messages;
        String name = "Messages-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, name), REQUEST_SAVE);
    }

    private void open() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQUEST_OPEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_SAVE) {
            boolean messages = withMessages;
            show("Backing up…");
            executor.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    int n = Backup.write(this, out, messages, this::show);
                    show(messages ? "Backed up settings and " + n + " messages." : "Backed up settings.");
                } catch (Exception e) {
                    show("Backup failed: " + e.getMessage());
                }
            });
        } else if (requestCode == REQUEST_OPEN) {
            new AlertDialog.Builder(this)
                    .setTitle("Restore")
                    .setMessage("Restore settings, and messages too? Restoring messages needs Messages to be "
                            + "the default SMS app.")
                    .setPositiveButton("Settings and messages", (d, w) -> {
                        if (!requireDefault()) return;
                        restore(uri, true);
                    })
                    .setNeutralButton("Settings only", (d, w) -> restore(uri, false))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void restore(Uri uri, boolean messages) {
        show("Restoring…");
        executor.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int n = Backup.restore(this, in, messages, this::show);
                show(messages ? "Restored settings and " + n + " messages." : "Restored settings.");
                onUi(() -> Toast.makeText(this, "Restored", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                show("That file couldn't be restored: " + e.getMessage());
            }
        });
    }

    private void show(String text) {
        onUi(() -> {
            status = text;
            refresh();
        });
    }
}
