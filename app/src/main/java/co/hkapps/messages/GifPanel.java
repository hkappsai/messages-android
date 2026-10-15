package co.hkapps.messages;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** GIF search: trending, recently sent and search results, powered by Giphy. */
final class GifPanel extends LinearLayout {

    interface OnPick {
        /** url: the GIF to send; preview: its small version for recents. */
        void picked(String url, String preview);
    }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> items = new ArrayList<>();  // {preview, url}
    private final GridView grid;
    private final TextView status;
    private final EditText query;
    private final OnPick onPick;
    private final int maxBytes;
    private int generation;
    private boolean showingRecents;

    GifPanel(Context context, Look look, int maxBytes, OnPick onPick) {
        super(context);
        this.onPick = onPick;
        this.maxBytes = maxBytes;
        setOrientation(VERTICAL);
        int pad = (int) Format.dp(context, 8);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, 0, pad, 0);
        query = new EditText(context);
        query.setHint("Search for GIFs");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        top.addView(query, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView recents = new TextView(context);
        recents.setText("🕘");
        recents.setTextSize(20);
        recents.setPadding(pad, pad, pad, pad);
        recents.setContentDescription("Recently sent GIFs");
        recents.setOnClickListener(v -> showRecents());
        top.addView(recents);
        addView(top);

        status = new TextView(context);
        status.setGravity(Gravity.CENTER);
        status.setPadding(pad, pad, pad, pad);
        status.setTextSize(12);
        status.setText("Powered by GIPHY");
        addView(status);

        grid = new GridView(context);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(pad / 2);
        grid.setVerticalSpacing(pad / 2);
        grid.setPadding(pad / 2, 0, pad / 2, 0);
        grid.setAdapter(new Adapter());
        grid.setOnItemClickListener((p, v, pos, id) -> {
            String[] item = items.get(pos);
            onPick.picked(item[1], item[0]);
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (!showingRecents && total > 0 && first + visible >= total - 6) more();
            }
        });
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacksAndMessages(null);
                main.postDelayed(() -> load(false), 400);
            }
        });
        load(false);
    }

    private boolean loadingMore;

    private void more() {
        if (loadingMore) return;
        loadingMore = true;
        load(true);
    }

    private void load(boolean append) {
        showingRecents = false;
        int current = append ? generation : ++generation;
        String q = query.getText().toString();
        int offset = append ? items.size() : 0;
        if (!append) status.setText("Loading…");
        NET.execute(() -> {
            List<String[]> found = new ArrayList<>();
            String error = null;
            try {
                for (Giphy.Gif g : Giphy.search(q, offset)) {
                    String url = g.bestUrl(maxBytes);
                    if (url != null) found.add(new String[]{g.preview, url});
                }
            } catch (Exception e) {
                error = "Couldn't reach GIPHY. Check your internet connection.";
            }
            String problem = error;
            main.post(() -> {
                loadingMore = false;
                if (current != generation) return;
                if (!append) items.clear();
                items.addAll(found);
                ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                status.setText(problem != null ? problem : items.isEmpty() ? "No GIFs found" : "Powered by GIPHY");
            });
        });
    }

    private void showRecents() {
        showingRecents = true;
        generation++;
        items.clear();
        for (String[] r : AppDb.get(getContext()).gifRecents()) items.add(new String[]{r[1], r[0]});
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        status.setText(items.isEmpty() ? "GIFs you send show up here" : "Recently sent");
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView image = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(parent.getContext());
            int h = (int) Format.dp(parent.getContext(), 96);
            image.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0x22888888);
            ImageLoader.loadUrl(image, items.get(position)[0], (int) Format.dp(parent.getContext(), 160));
            return image;
        }
    }
}
