package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.Editable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Emoji picker with categories, recently used emoji and skin tones (long-press). Uses the phone's
 * own emoji font and hides emoji the phone can't draw.
 */
final class EmojiPanel extends LinearLayout {

    private static final String[] TONES = {"", "🏻", "🏼", "🏽", "🏾", "🏿"};
    static final String[] TONE_LABELS = {"Default yellow", "Light", "Medium-light", "Medium", "Medium-dark", "Dark"};
    private static final int RECENT_MAX = 40;

    private static List<List<String[]>> parsed;

    private final EditText target;
    private final Look look;
    private final GridView grid;
    private final LinearLayout tabs;
    private final List<String[]> shown = new ArrayList<>();
    private int category = -1;

    EmojiPanel(Context context, EditText target, Look look) {
        super(context);
        this.target = target;
        this.look = look;
        setOrientation(VERTICAL);

        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(context);
        tabScroll.addView(tabs);
        top.addView(tabScroll, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.ic_backspace);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(look.dark ? 0xFFBDC1C6 : 0xFF5F6368));
        back.setBackground(null);
        back.setContentDescription("Backspace");
        back.setOnClickListener(v -> target.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)));
        int size = (int) Format.dp(context, 44);
        top.addView(back, new LayoutParams(size, size));
        addView(top);

        grid = new GridView(context);
        grid.setColumnWidth((int) Format.dp(context, 44));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(new Adapter());
        grid.setOnItemClickListener((p, v, pos, id) -> insert(withTone(shown.get(pos), Prefs.integer(getContext(), Prefs.SKIN_TONE))));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            String[] e = shown.get(pos);
            if (!"*".equals(e[1])) return false;
            String[] options = new String[TONES.length];
            for (int i = 0; i < TONES.length; i++) options[i] = withTone(e, i);
            new AlertDialog.Builder(getContext())
                    .setItems(options, (d, which) -> insert(options[which]))
                    .show();
            return true;
        });
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        buildTabs();
        select(recents(context).isEmpty() ? 0 : -1);
    }

    private void buildTabs() {
        Context c = getContext();
        List<List<String[]>> all = data(c);
        addTab("🕘", -1);
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isEmpty()) addTab(withTone(all.get(i).get(0), 0), i);
        }
    }

    private void addTab(String label, int index) {
        TextView tab = new TextView(getContext());
        tab.setText(label);
        tab.setTextSize(20);
        tab.setTextColor(0xFF000000);
        tab.setGravity(Gravity.CENTER);
        int pad = (int) Format.dp(getContext(), 10);
        tab.setPadding(pad, pad / 2, pad, pad / 2);
        tab.setTag(index);
        tab.setContentDescription(index < 0 ? "Recently used" : EmojiData.CATEGORIES[index]);
        tab.setOnClickListener(v -> select(index));
        tabs.addView(tab);
    }

    private void select(int index) {
        category = index;
        shown.clear();
        if (index < 0) {
            for (String e : recents(getContext())) shown.add(new String[]{e, ""});
        } else {
            shown.addAll(data(getContext()).get(index));
        }
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View t = tabs.getChildAt(i);
            boolean on = Integer.valueOf(index).equals(t.getTag());
            t.setBackground(on ? Look.rounded((look.accent & 0x00FFFFFF) | 0x33000000, Format.dp(getContext(), 16)) : null);
        }
        ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void insert(String emoji) {
        Editable text = target.getText();
        int start = Math.max(0, target.getSelectionStart());
        int end = Math.max(0, target.getSelectionEnd());
        text.replace(Math.min(start, end), Math.max(start, end), emoji);
        addRecent(getContext(), emoji);
        if (category < 0) select(-1);
    }

    // ---------------------------------------------------------------- data

    /** {emoji text, "*" when it takes a skin tone}. Emoji this phone can't draw are dropped. */
    private static synchronized List<List<String[]>> data(Context c) {
        if (parsed != null) return parsed;
        Paint paint = new Paint();
        List<List<String[]>> result = new ArrayList<>();
        for (String category : EmojiData.DATA) {
            List<String[]> list = new ArrayList<>();
            for (String token : category.trim().split("\\s+")) {
                if (token.isEmpty()) continue;
                String text;
                String tone = "";
                if (token.startsWith("F") && token.length() == 3 && Character.isUpperCase(token.charAt(1))
                        && Character.isUpperCase(token.charAt(2))) {
                    text = flag(token.substring(1));
                } else {
                    if (token.endsWith("*")) {
                        tone = "*";
                        token = token.substring(0, token.length() - 1);
                    }
                    int cp = Integer.parseInt(token, 16);
                    text = new String(Character.toChars(cp));
                    // Older symbols need the "show as emoji" selector.
                    if (cp < 0x1F000) text += "️";
                }
                if (paint.hasGlyph(text)) list.add(new String[]{text, tone});
            }
            result.add(list);
        }
        parsed = result;
        return result;
    }

    private static String flag(String cc) {
        StringBuilder sb = new StringBuilder();
        for (char ch : cc.toCharArray()) sb.appendCodePoint(0x1F1E6 + (ch - 'A'));
        return sb.toString();
    }

    static String withTone(String[] emoji, int tone) {
        if (!"*".equals(emoji[1]) || tone <= 0 || tone >= TONES.length) return emoji[0];
        String base = emoji[0].replace("️", "");
        return base + TONES[tone];
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("emoji", Context.MODE_PRIVATE);
    }

    static List<String> recents(Context c) {
        List<String> list = new ArrayList<>();
        String saved = sp(c).getString("recent", "");
        for (String e : saved.split("\u0001")) if (!e.isEmpty()) list.add(e);
        return list;
    }

    static void addRecent(Context c, String emoji) {
        List<String> list = recents(c);
        list.remove(emoji);
        list.add(0, emoji);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        sp(c).edit().putString("recent", android.text.TextUtils.join("\u0001", list)).apply();
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(parent.getContext());
            t.setTextSize(26);
            // Emoji take the text color's transparency; keep them fully opaque.
            t.setTextColor(0xFF000000);
            t.setGravity(Gravity.CENTER);
            int pad = (int) Format.dp(parent.getContext(), 4);
            t.setPadding(0, pad, 0, pad);
            t.setText(withTone(shown.get(position), Prefs.integer(parent.getContext(), Prefs.SKIN_TONE)));
            return t;
        }
    }
}
