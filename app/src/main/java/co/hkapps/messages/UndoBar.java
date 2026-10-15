package co.hkapps.messages;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A bar at the bottom of the screen: "3 messages deleted   UNDO", gone after the undo time. */
final class UndoBar {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UndoBar() {}

    static void show(Activity activity, Look look, String text, Runnable onUndo) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View old = content.findViewWithTag("undo_bar");
        if (old != null) content.removeView(old);

        int pad = (int) Format.dp(activity, 16);
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag("undo_bar");
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad / 4, pad / 2, pad / 4);
        bar.setBackground(Look.rounded(0xFF323232, Format.dp(activity, 8)));
        bar.setElevation(Format.dp(activity, 6));
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button undo = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        undo.setText(R.string.undo);
        undo.setTextColor(lighten(look.accent));
        bar.addView(undo);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int m = (int) Format.dp(activity, 12);
        lp.setMargins(m, m, m, (int) Format.dp(activity, 80));
        Runnable hide = () -> content.removeView(bar);
        undo.setOnClickListener(v -> {
            MAIN.removeCallbacks(hide);
            content.removeView(bar);
            onUndo.run();
        });
        if (content instanceof FrameLayout) content.addView(bar, lp);
        else content.addView(bar);
        MAIN.postDelayed(hide, Deleter.UNDO_MS);
    }

    private static int lighten(int color) {
        return android.graphics.Color.luminance(color) < 0.35f ? 0xFF8AB4F8 : color;
    }
}
