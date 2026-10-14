package co.hkapps.messages;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Applies the user's theme to every screen and rebuilds the screen when it changes. */
public abstract class BaseActivity extends Activity {

    /** Background work for reads. Writes go through SmsWriter.run instead. */
    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected Look look;
    private String lookKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        look = Look.from(this);
        lookKey = look.key();
        setTheme(look.styleRes);
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(look.statusBar);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) bar.setBackgroundDrawable(new ColorDrawable(look.bar));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings changed while this screen sat in the background.
        if (!Look.from(this).key().equals(lookKey)) recreate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void showUpButton() {
        ActionBar bar = getActionBar();
        if (bar != null) bar.setDisplayHomeAsUpEnabled(true);
    }

    /** Runs on the main thread, but only if the screen still exists. */
    protected void onUi(Runnable task) {
        runOnUiThread(() -> {
            if (!isDestroyed()) task.run();
        });
    }

    /** True when this is the default SMS app; otherwise explains and offers to switch. */
    protected boolean requireDefault() {
        if (DefaultSms.isDefault(this)) return true;
        DefaultSms.prompt(this);
        return false;
    }

    /** Paints every section header (tagged "header") in the accent color. */
    protected void colorHeaders(View view) {
        if (view instanceof TextView && "header".equals(view.getTag())) {
            ((TextView) view).setTextColor(look.accent);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) colorHeaders(group.getChildAt(i));
        }
    }
}
