package co.hkapps.messages;

import android.app.AlertDialog;
import android.os.Bundle;

import java.util.List;

/** Choose, add, change or delete signatures, for every chat or one chat. */
public class SignaturesActivity extends SettingsScreen {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Signatures");
    }

    @Override
    protected void build(Rows r) {
        AppDb db = AppDb.get(this);
        long chosen = (Long) r.get(Prefs.SIGNATURE);
        boolean overridden = r.perChat() && ChatPrefs.has(this, threadId, Prefs.SIGNATURE);
        r.note(r.perChat() ? "The signature added to messages in this chat."
                : "Added to the end of every message you write. A chat can pick its own in Customize.");
        r.header("Choose signature");
        if (r.perChat()) {
            long global = Prefs.longValue(this, Prefs.SIGNATURE);
            String g = global < 0 ? "none" : db.signature(global);
            r.action((overridden ? "" : "✓  ") + "Default (" + g + ")", null, () -> r.set(Prefs.SIGNATURE, null));
        }
        r.action((chosen < 0 && (overridden || !r.perChat()) ? "✓  " : "") + "[None]", null,
                () -> r.set(Prefs.SIGNATURE, -1L));
        List<AppDb.Item> all = db.signatures();
        for (AppDb.Item s : all) {
            boolean on = s.id == chosen && (overridden || !r.perChat());
            r.row((on ? "✓  " : "") + s.value, null, null, v -> r.set(Prefs.SIGNATURE, s.id))
                    .setOnLongClickListener(v -> {
                        edit(s);
                        return true;
                    });
        }
        r.action("+  New signature", null, () -> askText("New signature", "e.g. Sent from my phone", "", false, text -> {
            if (text.isEmpty()) return;
            long id = db.saveSignature(-1, text);
            r.set(Prefs.SIGNATURE, id);
        }));
        if (!all.isEmpty()) r.note("Long-press a signature to change or delete it.");
    }

    private void edit(AppDb.Item s) {
        new AlertDialog.Builder(this)
                .setTitle(s.value)
                .setItems(new String[]{"Change", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        askText("Change signature", null, s.value, false, text -> {
                            if (!text.isEmpty()) AppDb.get(this).saveSignature(s.id, text);
                            refresh();
                        });
                    } else {
                        AppDb.get(this).deleteSignature(s.id);
                        if (Prefs.longValue(this, Prefs.SIGNATURE) == s.id) Prefs.put(this, Prefs.SIGNATURE, -1L);
                        refresh();
                    }
                })
                .show();
    }
}
