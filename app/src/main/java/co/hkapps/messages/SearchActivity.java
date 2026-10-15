package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds chats by name and messages by text (Textra: "For chats & messages"), with All, Locked
 * and Scheduled tabs. Long-press a result for delete, lock and unlock.
 */
public class SearchActivity extends BaseActivity {

    private static final String[] SCOPES = {"All", "Locked", "Scheduled"};

    private final List<Message> results = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int generation;
    private int scope;
    private String shownQuery = "";

    private EditText query;
    private TextView emptyText;
    private LinearLayout tabs;
    private ResultAdapter adapter;
    private MessageReader reader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        showUpButton();
        reader = new MessageReader(this);

        query = findViewById(R.id.query);
        query.setHint("For chats & messages");
        emptyText = findViewById(R.id.empty_text);
        emptyText.setText("Long-press a search result to get more options, like delete, lock & unlock.");
        tabs = findViewById(R.id.tabs);
        buildTabs();

        ListView list = findViewById(R.id.list);
        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Message m = results.get(position);
            Intent open = new Intent(this, ThreadActivity.class).putExtra(ThreadActivity.EXTRA_THREAD_ID, m.threadId);
            if (m.id >= 0) open.putExtra(ThreadActivity.EXTRA_MESSAGE_ID, m.id);
            startActivity(open);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showOptions(results.get(position));
            return true;
        });

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(search);
                handler.postDelayed(search, 250);
            }
        });
    }

    private void buildTabs() {
        tabs.removeAllViews();
        int pad = (int) Format.dp(this, 14);
        for (int i = 0; i < SCOPES.length; i++) {
            TextView t = new TextView(this);
            t.setText(SCOPES[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(pad, pad / 2, pad, pad / 2);
            boolean on = i == scope;
            t.setTextColor(on ? look.onAccent : look.accent);
            t.setBackground(on ? Look.rounded(look.accent, Format.dp(this, 16)) : null);
            int index = i;
            t.setOnClickListener(v -> {
                scope = index;
                buildTabs();
                search();
            });
            tabs.addView(t);
        }
    }

    private void search() {
        String text = query.getText().toString().trim();
        int current = ++generation;
        int which = scope;
        if (text.isEmpty() && which == 0) {
            results.clear();
            adapter.notifyDataSetChanged();
            emptyText.setText("Long-press a search result to get more options, like delete, lock & unlock.");
            return;
        }
        executor.execute(() -> {
            List<Message> found = new ArrayList<>();
            Set<String> hidden = Deleter.pending(this);
            if (which == 0) {
                // Chats whose name matches come first, then messages.
                String lower = text.toLowerCase(Locale.getDefault());
                for (Conversation c : reader.loadConversations()) {
                    String name = ChatPrefs.name(this, c.threadId);
                    if (name == null) name = c.name;
                    if (!name.toLowerCase(Locale.getDefault()).contains(lower) && !c.address().contains(text)) continue;
                    Message row = new Message(-1, false, c.threadId, c.address(), c.latest.preview(), c.latest.date,
                            c.latest.type, true);
                    row.senderName = name;
                    found.add(row);
                    if (found.size() >= 10) break;
                }
                for (Message m : reader.search(text)) if (!hidden.contains(m.key())) found.add(m);
            } else if (which == 1) {
                for (Message m : reader.locked()) {
                    if (text.isEmpty() || m.body.toLowerCase(Locale.getDefault()).contains(text.toLowerCase(Locale.getDefault()))) {
                        found.add(m);
                    }
                }
            } else {
                List<Scheduler.Item> items = Scheduler.all(this);
                for (Scheduler.Item item : items) {
                    if (!text.isEmpty() && !item.text.toLowerCase(Locale.getDefault()).contains(text.toLowerCase(Locale.getDefault()))) {
                        continue;
                    }
                    Message m = new Message(item.id, false, item.threadId, "", item.text, item.time,
                            Message.TYPE_SCHEDULED, true);
                    m.senderName = "To " + reader.names(item.recipients);
                    found.add(m);
                }
            }
            onUi(() -> {
                if (current != generation) return;  // The user kept typing.
                shownQuery = text;
                results.clear();
                results.addAll(found);
                adapter.notifyDataSetChanged();
                emptyText.setText(which == 1 ? "No locked messages. Lock messages from their menu so they can't "
                        + "be deleted by accident." : which == 2 ? "No scheduled messages. Hold the send button to "
                        + "schedule one." : getString(R.string.no_results));
            });
        });
    }

    private void showOptions(Message m) {
        if (m.id < 0 || m.scheduled()) {
            if (m.scheduled()) {
                new AlertDialog.Builder(this)
                        .setItems(new String[]{"Send now", "Delete"}, (d, which) -> {
                            Context app = getApplicationContext();
                            if (which == 0) {
                                if (requireDefault()) SmsWriter.run(() -> Scheduler.fire(app, m.id));
                            } else {
                                Scheduler.cancel(this, m.id);
                            }
                            search();
                        })
                        .show();
            }
            return;
        }
        Context app = getApplicationContext();
        new AlertDialog.Builder(this)
                .setItems(new String[]{m.locked ? "Unlock" : "Lock", "Copy", "Delete"}, (d, which) -> {
                    if (which == 1) {
                        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.body));
                        return;
                    }
                    if (!requireDefault()) return;
                    if (which == 0) {
                        SmsWriter.run(() -> {
                            SmsWriter.setLocked(app, m, !m.locked);
                            onUi(this::search);
                        });
                    } else {
                        if (m.locked) {
                            Toast.makeText(this, "To delete locked messages, unlock them first.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<String> keys = Collections.singletonList(m.key());
                        Deleter.schedule(this, keys);
                        search();
                        UndoBar.show(this, look, "Message deleted", () -> {
                            Deleter.undo(this, keys);
                            search();
                        });
                    }
                })
                .show();
    }

    /** Cuts the text so the match shows, and paints the match in the accent color. */
    private CharSequence highlight(String body, String match) {
        if (match.isEmpty()) return body;
        String lower = body.toLowerCase(Locale.getDefault());
        int at = lower.indexOf(match.toLowerCase(Locale.getDefault()));
        if (at < 0 || at + match.length() > body.length()) return body;

        int start = Math.max(0, at - 30);
        String shown = (start > 0 ? "…" : "") + body.substring(start);
        int from = at - start + (start > 0 ? 1 : 0);
        SpannableString span = new SpannableString(shown);
        span.setSpan(new ForegroundColorSpan(look.accent), from, from + match.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), from, from + match.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Message getItem(int position) { return results.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_conversation, parent, false);
            Message m = getItem(position);
            String title = m.senderName != null ? m.senderName : m.address;
            MainActivity.bindConversation(view, look, title, m.address, false, m, 0, false);
            TextView snippet = view.findViewById(R.id.snippet);
            String prefix = m.locked ? "🔒 " : m.scheduled() ? "🕒 " : m.id < 0 ? "Chat · " : "";
            CharSequence body = highlight(m.body, shownQuery);
            snippet.setText(prefix.isEmpty() ? body : android.text.TextUtils.concat(prefix, body));
            return view;
        }
    }
}
