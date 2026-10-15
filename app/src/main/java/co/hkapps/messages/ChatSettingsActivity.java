package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Customize this chat" (Textra's per-contact settings): people, name, color, and the chat's own
 * notification and sending settings. Anything left on "Default" follows the app settings.
 */
public class ChatSettingsActivity extends SettingsScreen {

    private List<String> people = new ArrayList<>();
    private List<String> names = new ArrayList<>();
    private List<Boolean> contacts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (threadId <= 0) {
            finish();
            return;
        }
        setTitle(R.string.customize);
        executor.execute(() -> {
            MessageReader reader = new MessageReader(this);
            List<String> found = reader.recipientsOf(threadId);
            List<String> n = new ArrayList<>();
            List<Boolean> known = new ArrayList<>();
            for (String p : found) {
                n.add(reader.lookupName(p));
                known.add(reader.isContact(p));
            }
            onUi(() -> {
                people = found;
                names = n;
                contacts = known;
                refresh();
            });
        });
    }

    @Override
    protected void build(Rows r) {
        boolean group = people.size() > 1;
        String custom = ChatPrefs.name(this, threadId);
        if (getActionBar() != null) {
            getActionBar().setSubtitle(custom != null ? custom : android.text.TextUtils.join(", ", names));
        }

        r.header(group ? people.size() + " people in chat" : "Contact");
        for (int i = 0; i < people.size(); i++) {
            String address = people.get(i);
            String name = names.get(i);
            boolean known = contacts.get(i);
            r.action(name, name.equals(address) ? (known ? null : "Not in your contacts") : address,
                    () -> personActions(address, name, known));
        }

        r.header("Chat");
        r.action(group ? "Chat name" : "Chat name", custom != null ? custom : "Not set (uses the names of the people)",
                () -> askText("Change chat name", "Name", custom == null ? "" : custom, false, text -> {
                    ChatPrefs.put(this, threadId, ChatPrefs.NAME, text.isEmpty() ? null : text);
                    refresh();
                }));
        int color = ChatPrefs.has(this, threadId, Prefs.CHAT_COLOR) ? ChatPrefs.integer(this, threadId, Prefs.CHAT_COLOR) : 0;
        r.action("Theme color", color == 0 ? "Default" : String.format("#%06X", color & 0xFFFFFF), () ->
                ColorPicker.show(this, look, "Theme color", Look.COLORS, color, "Default", picked -> {
                    ChatPrefs.put(this, threadId, Prefs.CHAT_COLOR, picked == 0 ? null : picked);
                    refresh();
                }));
        r.action("Media", "Every picture, video and file in this chat", () ->
                startActivity(new Intent(this, MediaActivity.class).putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)));

        Sections.notifications(this, r);
        Sections.sending(this, r, group);
        Sections.keep(this, r);

        r.header("More");
        boolean blocked = Prefs.isBlocked(this, people);
        r.action(blocked ? "Un-blocklist" : "Blocklist", blocked ? "This chat is hidden and silent"
                : "Hide this chat and don't notify", () -> {
            for (String p : people) Prefs.setBlocked(this, p, !blocked);
            Toast.makeText(this, blocked ? "Un-blocklisted." : "Blocklisted.", Toast.LENGTH_SHORT).show();
            refresh();
        });
        r.action("Reset to defaults", "Forget everything set for this chat", () -> new AlertDialog.Builder(this)
                .setTitle("Really reset?")
                .setPositiveButton("Reset", (d, w) -> {
                    ChatPrefs.clear(this, threadId);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void personActions(String address, String name, boolean known) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Call");
        actions.add(() -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null))));
        if (people.size() > 1) {
            labels.add("Message " + MessageReader.firstName(name));
            actions.add(() -> startActivity(new Intent(this, ThreadActivity.class)
                    .putExtra(ThreadActivity.EXTRA_ADDRESS, address)));
        }
        if (known) {
            labels.add("View contact");
            actions.add(() -> {
                long id = Avatars.contactId(this, address);
                if (id >= 0) open(new Intent(Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)));
            });
        } else {
            labels.add("Add contact");
            actions.add(() -> open(VCard.addNumberIntent(address)));
        }
        labels.add("Copy number");
        actions.add(() -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("number", address));
            Toast.makeText(this, "\"" + address + "\" copied to clipboard.", Toast.LENGTH_SHORT).show();
        });
        boolean blocked = Prefs.isBlocked(this, address);
        labels.add(blocked ? "Unblock " + address : "Block " + address);
        actions.add(() -> {
            Prefs.setBlocked(this, address, !blocked);
            refresh();
        });
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "To show or edit a contact, you need to install a suitable app", Toast.LENGTH_SHORT).show();
        }
    }
}
