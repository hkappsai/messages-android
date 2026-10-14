package co.hkapps.messages;

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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds messages by their text. */
public class SearchActivity extends BaseActivity {

    private final List<Message> results = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int generation;
    private String shownQuery = "";

    private EditText query;
    private TextView emptyText;
    private ResultAdapter adapter;
    private MessageReader reader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        showUpButton();
        reader = new MessageReader(this);

        query = findViewById(R.id.query);
        emptyText = findViewById(R.id.empty_text);
        emptyText.setText(R.string.search_hint);

        ListView list = findViewById(R.id.list);
        adapter = new ResultAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Message sms = results.get(position);
            startActivity(new Intent(this, ThreadActivity.class)
                    .putExtra(ThreadActivity.EXTRA_THREAD_ID, sms.threadId)
                    .putExtra(ThreadActivity.EXTRA_MESSAGE_ID, sms.id));
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

    private void search() {
        String text = query.getText().toString().trim();
        int current = ++generation;
        if (text.isEmpty()) {
            results.clear();
            adapter.notifyDataSetChanged();
            emptyText.setText(R.string.search_hint);
            return;
        }
        executor.execute(() -> {
            List<Message> found = reader.search(text);
            onUi(() -> {
                if (current != generation) return;  // The user kept typing.
                shownQuery = text;
                results.clear();
                results.addAll(found);
                adapter.notifyDataSetChanged();
                emptyText.setText(R.string.no_results);
            });
        });
    }

    /** Cuts the text so the match shows, and paints the match in the accent color. */
    private CharSequence highlight(String body, String match) {
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
        @Override public long getItemId(int position) { return results.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_conversation, parent, false);
            Message sms = getItem(position);
            String title = sms.senderName != null ? sms.senderName : sms.address;
            MainActivity.bindConversation(view, look, title, sms.address, false, sms, 0, false);
            TextView snippet = view.findViewById(R.id.snippet);
            snippet.setText(highlight(sms.body, shownQuery));
            return view;
        }
    }
}
