package co.hkapps.messages;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

/** A dialog of round color swatches. 0 stands for "default". */
final class ColorPicker {

    interface OnPick {
        void picked(int color);
    }

    private ColorPicker() {}

    static void show(Activity a, Look look, String title, int[] colors, int current, String defaultLabel, OnPick pick) {
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(5);
        int pad = (int) Format.dp(a, 16);
        grid.setPadding(pad, pad, pad, 0);
        grid.setUseDefaultMargins(true);
        AlertDialog[] dialog = new AlertDialog[1];
        int size = (int) Format.dp(a, 44);
        int ring = look.dark ? 0xFFFFFFFF : 0xFF202124;
        if (defaultLabel != null) {
            TextView auto = new TextView(a);
            auto.setText("A");
            auto.setGravity(Gravity.CENTER);
            auto.setTextColor(0xFFFFFFFF);
            auto.setContentDescription(defaultLabel);
            GradientDrawable d = Look.circle(0xFF9E9E9E);
            if (current == 0) d.setStroke((int) Format.dp(a, 3), ring);
            auto.setBackground(d);
            auto.setOnClickListener(v -> {
                dialog[0].dismiss();
                pick.picked(0);
            });
            grid.addView(auto, params(size));
        }
        for (int color : colors) {
            View dot = new View(a);
            GradientDrawable d = Look.circle(color);
            if (color == current) d.setStroke((int) Format.dp(a, 3), ring);
            else if (Look.textOn(color) != 0xFFFFFFFF) d.setStroke((int) Format.dp(a, 1), 0x33000000);
            dot.setBackground(d);
            dot.setContentDescription(String.format("Color #%06X", color & 0xFFFFFF));
            dot.setOnClickListener(v -> {
                dialog[0].dismiss();
                pick.picked(color);
            });
            grid.addView(dot, params(size));
        }
        dialog[0] = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static GridLayout.LayoutParams params(int size) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = size;
        p.height = size;
        return p;
    }
}
