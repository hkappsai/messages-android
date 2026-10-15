package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocklisted numbers and chats (Textra's Blocklist): shows each one's last message, opens the
 * chat, un-blocks, deletes, or adds a number by hand.
 */
public class BlocklistActivity extends SettingsScreen {

    private final Map<String, Conversation> chats = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Blocklist");
        loadChats();
    }

    private void loadChats() {
        executor.execute(() -> {
            Map<String, Conversation> found = new HashMap<>();
            for (Conversation c : new MessageReader(this).loadConversations()) {
                for (String a : c.addresses) {
                    if (Prefs.isBlocked(this, a)) found.put(a, c);
                }
            }
            onUi(() -> {
                chats.clear();
                chats.putAll(found);
                refresh();
            });
        });
    }

    @Override
    protected void build(Rows r) {
        r.note("A blocklisted chat is not shown in the chat list, and you are not notified when receiving "
                + "messages. Messages you receive are kept, and come back when you un-blocklist.");
        r.action("+  Number to block", null, () -> askText("Number to block", "Phone number or sender name", "",
                true, text -> {
                    if (text.isEmpty()) return;
                    Prefs.setBlocked(this, text, true);
                    loadChats();
                }));
        List<String> numbers = Prefs.blocked(this);
        r.header(numbers.isEmpty() ? "Nothing blocklisted" : "Blocklisted");
        if (numbers.isEmpty()) {
            r.note("To blocklist, long-press in the chat list and pick \"Blocklist\" from the menu. "
                    + "You can also do this in a specific chat.");
        }
        MessageReader reader = new MessageReader(this);
        for (String number : numbers) {
            Conversation c = null;
            for (Map.Entry<String, Conversation> e : chats.entrySet()) {
                if (Prefs.sameAddress(e.getKey(), number)) c = e.getValue();
            }
            String name = reader.lookupName(number);
            String title = name.equals(number) ? number : name + " (" + number + ")";
            String summary = c == null ? "No messages" : "Last message: " + c.latest.preview();
            Conversation chat = c;
            r.action(title, summary, () -> actions(number, chat));
        }
    }

    private void actions(String number, Conversation chat) {
        List<String> labels = new ArrayList<>();
        List<Runnable> run = new ArrayList<>();
        labels.add("Un-blocklist");
        run.add(() -> {
            Prefs.setBlocked(this, number, false);
            refresh();
        });
        if (chat != null) {
            labels.add("View chat");
            run.add(() -> startActivity(new Intent(this, ThreadActivity.class)
                    .putExtra(ThreadActivity.EXTRA_THREAD_ID, chat.threadId)));
            labels.add("Delete chat");
            run.add(() -> new AlertDialog.Builder(this)
                    .setTitle("Delete chat?")
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        if (!requireDefault()) return;
                        SmsWriter.run(() -> {
                            SmsWriter.deleteThread(getApplicationContext(), chat.threadId);
                            onUi(this::loadChats);
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
        new AlertDialog.Builder(this)
                .setTitle(number)
                .setItems(labels.toArray(new String[0]), (d, w) -> run.get(w).run())
                .show();
    }
}
