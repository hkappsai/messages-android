package co.hkapps.messages;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.List;

/** Default SMS app, theme, colors, notifications and blocked numbers. */
public class SettingsActivity extends BaseActivity {

    private interface ColorPick {
        void onPick(int color);
    }

    private TextView defaultStatus;
    private Button defaultButton;
    private LinearLayout blockedList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        showUpButton();
        colorHeaders(findViewById(android.R.id.content));

        defaultStatus = findViewById(R.id.default_status);
        defaultButton = findViewById(R.id.default_button);
        defaultButton.setOnClickListener(v -> DefaultSms.request(this));

        setUpThemeMode();

        // Picking a color rebuilds this screen so the bar and preview show it at once.
        addSwatches(findViewById(R.id.accent_colors), Prefs.accent(this), false, color -> {
            Prefs.setAccent(this, color);
            recreate();
        });
        addSwatches(findViewById(R.id.bubble_colors), Prefs.bubble(this), true, color -> {
            Prefs.setBubble(this, color);
            recreate();
        });
        addPreview(findViewById(R.id.preview));

        findViewById(R.id.notification_settings).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));

        blockedList = findViewById(R.id.blocked_list);

        TextView version = findViewById(R.id.version);
        try {
            version.setText("Messages " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            version.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isDefault = DefaultSms.isDefault(this);
        defaultStatus.setText(isDefault
                ? "Messages is your default SMS app."
                : "Another app is your default SMS app.");
        defaultButton.setVisibility(isDefault ? View.GONE : View.VISIBLE);
        fillBlocked();
    }

    private void setUpThemeMode() {
        RadioGroup group = findViewById(R.id.theme_mode);
        int[] ids = {R.id.mode_system, R.id.mode_light, R.id.mode_dark, R.id.mode_black};
        group.check(ids[Prefs.themeMode(this)]);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            for (int mode = 0; mode < ids.length; mode++) {
                if (ids[mode] == checkedId && mode != Prefs.themeMode(this)) {
                    Prefs.setThemeMode(this, mode);
                    recreate();
                }
            }
        });
    }

    /** A row of round color buttons. With withAuto, the first one means "same as accent" (0). */
    private void addSwatches(LinearLayout row, int selected, boolean withAuto, ColorPick pick) {
        int size = (int) Format.dp(this, 40);
        int margin = (int) Format.dp(this, 6);
        int ring = look.dark ? 0xFFFFFFFF : 0xFF202124;

        if (withAuto) {
            TextView auto = new TextView(this);
            auto.setText("A");
            auto.setGravity(Gravity.CENTER);
            auto.setTextColor(look.onAccent);
            auto.setContentDescription("Same as accent color");
            auto.setBackground(swatch(look.accent, selected == 0, ring));
            auto.setOnClickListener(v -> pick.onPick(0));
            row.addView(auto, swatchParams(size, margin));
        }
        for (int color : Look.COLORS) {
            View dot = new View(this);
            dot.setBackground(swatch(color, color == selected, ring));
            dot.setContentDescription(String.format("Color #%06X", color & 0xFFFFFF));
            dot.setOnClickListener(v -> pick.onPick(color));
            row.addView(dot, swatchParams(size, margin));
        }
    }

    private GradientDrawable swatch(int color, boolean selected, int ring) {
        GradientDrawable d = Look.circle(color);
        if (selected) d.setStroke((int) Format.dp(this, 3), ring);
        return d;
    }

    private static LinearLayout.LayoutParams swatchParams(int size, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
        p.setMargins(margin, margin, margin, margin);
        return p;
    }

    private void addPreview(LinearLayout container) {
        LayoutInflater inflater = LayoutInflater.from(this);
        String[][] sample = {{"in", "Are we still on for tonight?"}, {"out", "Yes! See you at 7."}};
        for (String[] line : sample) {
            View row = inflater.inflate(R.layout.item_message, container, false);
            ThreadActivity.bindBubble(row, look, line[0].equals("in"), line[1]);
            row.findViewById(R.id.time).setVisibility(View.GONE);
            container.addView(row);
        }
    }

    private void fillBlocked() {
        blockedList.removeAllViews();
        List<String> numbers = Prefs.blocked(this);
        int pad = (int) Format.dp(this, 16);

        if (numbers.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No blocked numbers. Long-press a conversation to block it.");
            none.setTextColor(defaultStatus.getTextColors().withAlpha(160));
            none.setPadding(pad, 0, pad, 0);
            blockedList.addView(none);
            return;
        }
        for (String number : numbers) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, 0, pad / 2, 0);

            TextView text = new TextView(this);
            text.setText(number);
            text.setTextSize(15);
            text.setTextColor(defaultStatus.getTextColors());
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button unblock = new Button(this, null, android.R.attr.borderlessButtonStyle);
            unblock.setText(R.string.unblock);
            unblock.setOnClickListener(v -> {
                Prefs.setBlocked(this, number, false);
                fillBlocked();
            });
            row.addView(unblock);
            blockedList.addView(row);
        }
    }
}
