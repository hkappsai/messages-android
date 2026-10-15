package co.hkapps.messages;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import java.util.Calendar;

/** "Customize look": screen color, theme colors, bubbles, font, text size, emoji, app icon. */
public class LookSettingsActivity extends SettingsScreen {

    private static final String[] MODE_LABELS = {"Same as Android", "Light", "Dark", "Black (saves battery on OLED)",
            "Light & night mode"};
    private static final Object[] MODE_VALUES = {Prefs.MODE_SYSTEM, Prefs.MODE_LIGHT, Prefs.MODE_DARK, Prefs.MODE_BLACK,
            Prefs.MODE_SCHEDULE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Customize look");
    }

    @Override
    protected void onSettingChanged() {
        // Colors and fonts apply to the whole screen, so rebuild it.
        recreate();
    }

    @Override
    protected void build(Rows r) {
        addPreview(r);

        r.header("Screen");
        r.choice("Screen color", Prefs.THEME_MODE, MODE_LABELS, MODE_VALUES);
        if (Prefs.themeMode(this) == Prefs.MODE_SCHEDULE) {
            int from = Prefs.integer(this, Prefs.NIGHT_FROM);
            int to = Prefs.integer(this, Prefs.NIGHT_TO);
            r.action("Dark from", time(from), () -> pickTime(Prefs.NIGHT_FROM, from));
            r.action("Dark until", time(to), () -> pickTime(Prefs.NIGHT_TO, to));
        }

        r.header("Colors");
        r.action("Theme color", "Bars, buttons and links", () -> ColorPicker.show(this, look, "Theme color",
                Look.COLORS, Prefs.accent(this), null, c -> r.set(Prefs.ACCENT, c)));
        r.action("Sent bubble color", Prefs.bubble(this) == 0 ? "Same as theme color" : null, () ->
                ColorPicker.show(this, look, "Sent bubble color", Look.COLORS, Prefs.bubble(this),
                        "Same as theme color", c -> r.set(Prefs.BUBBLE, c)));
        r.action("Sent text color", Prefs.integer(this, Prefs.BUBBLE_OUT_TEXT) == 0 ? "Automatic" : null, () ->
                ColorPicker.show(this, look, "Sent text color", new int[]{0xFFFFFFFF, 0xFF202124, 0xFFFFF59D, 0xFFB3E5FC},
                        Prefs.integer(this, Prefs.BUBBLE_OUT_TEXT), "Automatic", c -> r.set(Prefs.BUBBLE_OUT_TEXT, c)));
        r.action("Received bubble color", Prefs.integer(this, Prefs.BUBBLE_IN) == 0 ? "Grey" : null, () ->
                ColorPicker.show(this, look, "Received bubble color", Look.SOFT_COLORS,
                        Prefs.integer(this, Prefs.BUBBLE_IN), "Grey", c -> r.set(Prefs.BUBBLE_IN, c)));
        r.action("Received text color", Prefs.integer(this, Prefs.BUBBLE_IN_TEXT) == 0 ? "Automatic" : null, () ->
                ColorPicker.show(this, look, "Received text color", new int[]{0xFF202124, 0xFFFFFFFF, 0xFF1A237E, 0xFF4A148C},
                        Prefs.integer(this, Prefs.BUBBLE_IN_TEXT), "Automatic", c -> r.set(Prefs.BUBBLE_IN_TEXT, c)));
        r.toggle("Auto-theme contacts", "Give each chat its own color", Prefs.AUTO_COLOR);

        r.header("Bubbles & text");
        r.choice("Bubble style", Prefs.BUBBLE_SHAPE, Look.SHAPES, new Object[]{0, 1, 2, 3});
        r.choice("Text font", Prefs.FONT, Look.FONTS, new Object[]{0, 1, 2, 3, 4, 5});
        r.choice("Text size", Prefs.TEXT_SIZE, Look.TEXT_SIZES, new Object[]{0, 1, 2, 3});
        r.choice("Emoji skin tone", "Long-press an emoji to pick one just once", Prefs.SKIN_TONE,
                EmojiPanel.TONE_LABELS, new Object[]{0, 1, 2, 3, 4, 5});

        r.header("App icon");
        r.action("App icon color", AppIcon.NAMES[AppIcon.current(this)], () -> new AlertDialog.Builder(this)
                .setTitle("App icon color")
                .setSingleChoiceItems(AppIcon.NAMES, AppIcon.current(this), (d, which) -> {
                    d.dismiss();
                    AppIcon.set(this, which);
                    new AlertDialog.Builder(this)
                            .setMessage("Your app icon has changed! If you had it on your home screen, add it back "
                                    + "from the app drawer.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    refresh();
                })
                .show());
    }

    private void addPreview(Rows r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) Format.dp(this, 12), 0, (int) Format.dp(this, 4));
        LayoutInflater inflater = LayoutInflater.from(this);
        String[][] sample = {{"in", "Make me beautiful"}, {"in", "Hi!"}, {"out", "Me too!"}, {"out", "Yo"}};
        for (int i = 0; i < sample.length; i++) {
            View row = inflater.inflate(R.layout.item_message, box, false);
            boolean in = sample[i][0].equals("in");
            boolean last = i + 1 == sample.length || !sample[i + 1][0].equals(sample[i][0]);
            ThreadActivity.bindBubble(row, look, in, sample[i][1], last);
            row.findViewById(R.id.time).setVisibility(View.GONE);
            box.addView(row);
        }
        r.view(box);
    }

    private String time(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.getTimeFormat(this).format(c.getTime());
    }

    private void pickTime(String key, int minutes) {
        new TimePickerDialog(this, (v, h, m) -> {
            Prefs.put(this, key, h * 60 + m);
            recreate();
        }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(this)).show();
    }
}
