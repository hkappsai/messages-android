package co.hkapps.messages;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The app's own heads-up card (Textra's "floatification"): slides over whatever is on screen,
 * stays for the chosen time, offers Reply and Open. Needs "Display over other apps".
 */
final class Popup {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Long, View> SHOWN = new HashMap<>();

    private Popup() {}

    static boolean allowed(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Call from any thread. */
    static void show(Context context, long threadId, String title, String text, Bitmap avatar) {
        Context app = context.getApplicationContext();
        if (!allowed(app)) return;
        int secs = ChatPrefs.integer(app, threadId, Prefs.POPUP_SECS);
        MAIN.post(() -> {
            dismissNow(threadId);
            Look look = Look.from(app);
            WindowManager wm = app.getSystemService(WindowManager.class);

            LinearLayout card = new LinearLayout(app);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) Format.dp(app, 14);
            card.setPadding(pad, pad, pad, pad / 2);
            card.setBackground(Look.rounded(look.dark ? 0xF0303134 : 0xF8FFFFFF, Format.dp(app, 20)));
            card.setElevation(Format.dp(app, 8));

            LinearLayout top = new LinearLayout(app);
            top.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(app);
            if (avatar != null) icon.setImageBitmap(avatar);
            int size = (int) Format.dp(app, 40);
            top.addView(icon, new LinearLayout.LayoutParams(size, size));
            LinearLayout texts = new LinearLayout(app);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(pad, 0, 0, 0);
            TextView name = new TextView(app);
            name.setText(title);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(look.dark ? 0xFFE8EAED : 0xFF202124);
            name.setTextSize(15);
            TextView body = new TextView(app);
            body.setText(text);
            body.setMaxLines(4);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setTextColor(look.dark ? 0xFFBDC1C6 : 0xFF3C4043);
            body.setTextSize(14);
            texts.addView(name);
            texts.addView(body);
            top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            card.addView(top);

            LinearLayout buttons = new LinearLayout(app);
            buttons.setGravity(Gravity.END);
            Button reply = new Button(app, null, android.R.attr.borderlessButtonStyle);
            reply.setText(R.string.reply);
            reply.setTextColor(look.accent);
            reply.setOnClickListener(v -> {
                dismissNow(threadId);
                app.startActivity(QuickReplyActivity.intent(app, threadId, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            Button open = new Button(app, null, android.R.attr.borderlessButtonStyle);
            open.setText("Open");
            open.setTextColor(look.accent);
            open.setOnClickListener(v -> {
                dismissNow(threadId);
                app.startActivity(new Intent(app, ThreadActivity.class)
                        .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            buttons.addView(open);
            buttons.addView(reply);
            card.addView(buttons);

            // Swipe the card sideways or up to dismiss it.
            card.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            v.setTranslationX(e.getRawX() - downX);
                            v.setTranslationY(Math.min(0, e.getRawY() - downY));
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (Math.abs(v.getTranslationX()) > v.getWidth() / 3f || v.getTranslationY() < -v.getHeight() / 3f) {
                                dismissNow(threadId);
                            } else if (Math.abs(e.getRawX() - downX) < 10 && Math.abs(e.getRawY() - downY) < 10) {
                                open.performClick();
                            } else {
                                v.animate().translationX(0).translationY(0).start();
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            int margin = (int) Format.dp(app, 10);
            lp.y = (int) Format.dp(app, 32);
            LinearLayout frame = new LinearLayout(app);
            frame.setPadding(margin, margin, margin, margin);
            frame.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            try {
                wm.addView(frame, lp);
            } catch (RuntimeException e) {
                return;
            }
            SHOWN.put(threadId, frame);
            card.setTranslationY(-Format.dp(app, 120));
            card.animate().translationY(0).setDuration(200).start();
            if (secs > 0) MAIN.postDelayed(() -> dismissNow(threadId), secs * 1000L);
        });
    }

    static void dismiss(long threadId) {
        MAIN.post(() -> dismissNow(threadId));
    }

    private static void dismissNow(long threadId) {
        View v = SHOWN.remove(threadId);
        if (v == null) return;
        try {
            v.getContext().getSystemService(WindowManager.class).removeView(v);
        } catch (RuntimeException ignored) {
            // Already removed.
        }
    }
}
