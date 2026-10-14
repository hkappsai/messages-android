package co.hkapps.messages;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows every message in one conversation as chat bubbles. */
public class ThreadActivity extends Activity {

    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_TITLE = "title";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Sms> messages = new ArrayList<>();
    private MessageAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        ListView list = findViewById(R.id.list);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        list.setEmptyView(findViewById(R.id.empty));
        adapter = new MessageAdapter();
        list.setAdapter(adapter);

        long threadId = getIntent().getLongExtra(EXTRA_THREAD_ID, -1);
        executor.execute(() -> {
            List<Sms> result = new SmsReader(this).loadThread(threadId);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                messages.addAll(result);
                adapter.notifyDataSetChanged();
                if (result.isEmpty()) {
                    ((TextView) findViewById(R.id.empty_text)).setText(R.string.no_messages);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class MessageAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Sms getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_message, parent, false);
            Sms sms = getItem(position);

            LinearLayout row = view.findViewById(R.id.row);
            TextView body = view.findViewById(R.id.body);
            TextView time = view.findViewById(R.id.time);

            // Received messages sit on the left in grey, sent ones on the right in blue.
            row.setGravity(sms.incoming ? Gravity.START : Gravity.END);
            body.setBackgroundResource(sms.incoming ? R.drawable.bubble_in : R.drawable.bubble_out);
            body.setTextColor(getColor(sms.incoming ? R.color.text_in : R.color.text_out));
            body.setText(sms.body);
            time.setText(Format.fullDate(parent.getContext(), sms.date));
            return view;
        }
    }
}
