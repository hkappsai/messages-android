package co.hkapps.messages;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds settings screens out of plain rows: headers, switches, pick-one lists and actions.
 *
 * With a chat (threadId > 0) every row edits that chat's own value, and each list gets a
 * "Default" choice that goes back to the app-wide setting.
 */
final class Rows {

    interface OnChange {
        void changed();
    }

    private final BaseActivity activity;
    private final LinearLayout container;
    private final long threadId;
    private final OnChange onChange;
    private final int pad;

    Rows(BaseActivity activity, LinearLayout container, long threadId, OnChange onChange) {
        this.activity = activity;
        this.container = container;
        this.threadId = threadId;
        this.onChange = onChange;
        this.pad = (int) Format.dp(activity, 16);
    }

    boolean perChat() {
        return threadId > 0;
    }

    // ---------------------------------------------------------------- values

    Object get(String key) {
        Object def = Prefs.defaultOf(key);
        if (def instanceof Boolean) return perChat() ? ChatPrefs.bool(activity, threadId, key) : Prefs.bool(activity, key);
        if (def instanceof Integer) return perChat() ? ChatPrefs.integer(activity, threadId, key) : Prefs.integer(activity, key);
        if (def instanceof Long) return perChat() ? ChatPrefs.longValue(activity, threadId, key) : Prefs.longValue(activity, key);
        return perChat() ? ChatPrefs.string(activity, threadId, key) : Prefs.string(activity, key);
    }

    void set(String key, Object value) {
        if (perChat()) ChatPrefs.put(activity, threadId, key, value);
        else Prefs.put(activity, key, value);
        if (onChange != null) onChange.changed();
    }

    private boolean overridden(String key) {
        return perChat() && ChatPrefs.has(activity, threadId, key);
    }

    // ---------------------------------------------------------------- rows

    void header(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        view.setTextColor(activity.look.accent);
        view.setPadding(pad, pad + pad / 4, pad, pad / 2);
        container.addView(view);
    }

    void note(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(secondary());
        view.setPadding(pad, pad / 4, pad, pad / 2);
        container.addView(view);
    }

    /** Any view, full width, like a preview. */
    void view(View v) {
        container.addView(v);
    }

    /** A row with a title, an optional summary and an optional view at the end. */
    View row(CharSequence title, CharSequence summary, View end, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) Format.dp(activity, 56));
        row.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        if (click != null) {
            row.setBackgroundResource(selectable());
            row.setOnClickListener(click);
        }
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(primary());
        texts.addView(t);
        if (summary != null && summary.length() > 0) {
            TextView s = new TextView(activity);
            s.setText(summary);
            s.setTextSize(13);
            s.setTextColor(secondary());
            s.setPadding(0, (int) Format.dp(activity, 2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (end != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(pad);
            row.addView(end, lp);
        }
        container.addView(row);
        return row;
    }

    View action(CharSequence title, CharSequence summary, Runnable action) {
        return row(title, summary, null, v -> action.run());
    }

    /** On/off. Per chat it becomes Default / On / Off, so the chat can follow the app setting. */
    void toggle(String title, String summary, String key) {
        boolean on = (Boolean) get(key);
        if (perChat()) {
            String state = overridden(key) ? (on ? "On" : "Off") : "Default (" + (on ? "on" : "off") + ")";
            row(title, join(summary, state), null, v -> pick(title, key,
                    new String[]{"On", "Off"}, new Object[]{true, false}));
            return;
        }
        Switch sw = new Switch(activity);
        sw.setChecked(on);
        sw.setClickable(false);
        sw.setFocusable(false);
        row(title, summary, sw, v -> {
            sw.setChecked(!sw.isChecked());
            set(key, sw.isChecked());
        });
    }

    /** Pick one of several values. The summary shows the current choice. */
    void choice(String title, String key, String[] labels, Object[] values) {
        choice(title, null, key, labels, values);
    }

    void choice(String title, String summary, String key, String[] labels, Object[] values) {
        String current = labelOf(get(key), labels, values);
        String state = perChat() && !overridden(key) ? "Default (" + current + ")" : current;
        row(title, join(summary, state), null, v -> pick(title, key, labels, values));
    }

    private void pick(String title, String key, String[] labels, Object[] values) {
        List<String> shown = new ArrayList<>();
        List<Object> picked = new ArrayList<>();
        if (perChat()) {
            shown.add("Default (" + labelOf(globalValue(key), labels, values) + ")");
            picked.add(null);
        }
        for (int i = 0; i < labels.length; i++) {
            shown.add(labels[i]);
            picked.add(values[i]);
        }
        int checked = -1;
        if (perChat() && !overridden(key)) {
            checked = 0;
        } else {
            Object current = get(key);
            for (int i = 0; i < picked.size(); i++) {
                if (picked.get(i) != null && Objects.equals(picked.get(i), current)) {
                    checked = i;
                    break;
                }
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(shown.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    set(key, picked.get(which));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Object globalValue(String key) {
        Object def = Prefs.defaultOf(key);
        if (def instanceof Boolean) return Prefs.bool(activity, key);
        if (def instanceof Integer) return Prefs.integer(activity, key);
        if (def instanceof Long) return Prefs.longValue(activity, key);
        return Prefs.string(activity, key);
    }

    static String labelOf(Object value, String[] labels, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            if (Objects.equals(values[i], value)) return labels[i];
        }
        return value == null ? "" : value.toString();
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a + "\n" + b;
    }

    // ---------------------------------------------------------------- colors

    int primary() {
        return activity.look.dark ? 0xFFE8EAED : 0xFF202124;
    }

    int secondary() {
        return activity.look.dark ? 0xFF9AA0A6 : 0xFF5F6368;
    }

    private int selectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
