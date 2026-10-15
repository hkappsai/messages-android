package co.hkapps.messages;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** "Make custom pattern": tap out a rhythm, then save it as a vibration pattern. */
public class VibratePatternActivity extends BaseActivity {

    private final List<Long> timings = new ArrayList<>();
    private long lastChange;
    private boolean recording;
    private TextView pad;
    private Button stop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Make custom pattern");
        showUpButton();
        int p = (int) Format.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        pad = new TextView(this);
        pad.setText("Tap to record");
        pad.setGravity(Gravity.CENTER);
        pad.setTextSize(20);
        pad.setTextColor(look.onAccent);
        pad.setBackground(Look.rounded(look.accent, Format.dp(this, 24)));
        pad.setOnTouchListener((v, e) -> {
            long now = SystemClock.uptimeMillis();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (!recording) {
                    recording = true;
                    timings.clear();
                    timings.add(0L);
                    stop.setEnabled(true);
                    pad.setText("Tap the rhythm…");
                } else {
                    timings.add(now - lastChange);  // pause
                }
                lastChange = now;
                v.setAlpha(0.6f);
                Vibes.play(this, new long[]{0, 5000});
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (recording) {
                    timings.add(now - lastChange);  // buzz
                    lastChange = now;
                }
                v.setAlpha(1f);
                android.os.Vibrator vib = getSystemService(android.os.Vibrator.class);
                if (vib != null) vib.cancel();
                return true;
            }
            return false;
        });
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        Button again = new Button(this, null, android.R.attr.borderlessButtonStyle);
        again.setText("Start again");
        again.setOnClickListener(v -> reset());
        Button play = new Button(this, null, android.R.attr.borderlessButtonStyle);
        play.setText("Play");
        play.setOnClickListener(v -> Vibes.play(this, pattern()));
        stop = new Button(this, null, android.R.attr.borderlessButtonStyle);
        stop.setText("Save");
        stop.setTextColor(look.accent);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> save());
        buttons.addView(again);
        buttons.addView(play);
        buttons.addView(stop);
        root.addView(buttons);
        setContentView(root);
    }

    private void reset() {
        recording = false;
        timings.clear();
        pad.setText("Tap to record");
        stop.setEnabled(false);
    }

    private long[] pattern() {
        long[] p = new long[timings.size()];
        for (int i = 0; i < p.length; i++) p[i] = Math.min(timings.get(i), 3000);
        return p;
    }

    private void save() {
        if (timings.size() < 2) return;
        EditText name = new EditText(this);
        name.setHint("Bzz…");
        int p = (int) Format.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        long threadId = getIntent().getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        new AlertDialog.Builder(this)
                .setTitle("Name your new pattern")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    List<Long> clipped = new ArrayList<>();
                    for (long t : pattern()) clipped.add(t);
                    long id = AppDb.get(this).savePattern(-1, n.isEmpty() ? "Bzz..." : n, Vibes.format(clipped));
                    String value = Vibes.CUSTOM_PREFIX + id;
                    if (threadId > 0) ChatPrefs.put(this, threadId, Prefs.VIBRATE, value);
                    else Prefs.put(this, Prefs.VIBRATE, value);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
