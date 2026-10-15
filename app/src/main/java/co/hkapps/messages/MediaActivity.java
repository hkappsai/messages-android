package co.hkapps.messages;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Every picture, video and file in one chat, newest first (Textra's chat media screen). */
public class MediaActivity extends BaseActivity {

    private final List<Part> parts = new ArrayList<>();
    private final List<Message> owners = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Media");
        showUpButton();
        long threadId = getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);

        FrameLayout root = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        int gap = (int) Format.dp(this, 2);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> open(pos));
        root.addView(grid);
        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setText("Loading…");
        root.addView(empty);
        setContentView(root);

        executor.execute(() -> {
            List<Message> media = new MessageReader(this).media(threadId);
            onUi(() -> {
                for (Message m : media) {
                    for (Part p : m.parts) {
                        parts.add(p);
                        owners.add(m);
                    }
                }
                adapter.notifyDataSetChanged();
                empty.setText("No media in this chat yet");
                empty.setVisibility(parts.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void open(int pos) {
        Part p = parts.get(pos);
        if (p.isImage()) {
            startActivity(new Intent(this, ImageViewerActivity.class).setData(p.uri)
                    .putExtra(ImageViewerActivity.EXTRA_TYPE, p.contentType)
                    .putExtra(ThreadActivity.EXTRA_THREAD_ID, getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1)));
        } else {
            // Open the chat at that message for anything else.
            Message m = owners.get(pos);
            startActivity(new Intent(this, ThreadActivity.class)
                    .putExtra(ThreadActivity.EXTRA_THREAD_ID, m.threadId)
                    .putExtra(ThreadActivity.EXTRA_MESSAGE_ID, m.id));
        }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return parts.size(); }
        @Override public Object getItem(int position) { return parts.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int h = (int) Format.dp(parent.getContext(), 120);
            Part p = parts.get(position);
            FrameLayout cell = new FrameLayout(parent.getContext());
            cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            cell.setBackgroundColor(look.bubbleIn);
            if (p.isImage() || p.isVideo()) {
                ImageView image = new ImageView(parent.getContext());
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                ImageLoader.load(image, p.uri, (int) Format.dp(parent.getContext(), 140), p.isVideo());
                cell.addView(image);
                if (p.isVideo()) {
                    TextView play = new TextView(parent.getContext());
                    play.setText("▶");
                    play.setTextColor(0xFFFFFFFF);
                    play.setTextSize(22);
                    cell.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                }
            } else {
                TextView t = new TextView(parent.getContext());
                t.setGravity(Gravity.CENTER);
                t.setTextColor(look.bubbleInText);
                t.setText((p.isAudio() ? "🎤" : p.isContact() ? "👤" : p.isCalendar() ? "📅" : "📎") + "\n" + p.label());
                cell.addView(t);
            }
            return cell;
        }
    }
}
