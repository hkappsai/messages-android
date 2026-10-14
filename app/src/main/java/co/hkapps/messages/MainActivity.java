package co.hkapps.messages;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One row per SMS conversation. Pinned ones first, then newest first. */
public class MainActivity extends BaseActivity implements SwipeHelper.Callback {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int MENU_SEARCH = 1;
    private static final int MENU_MARK_ALL_READ = 2;
    private static final int MENU_SETTINGS = 3;

    private final List<Sms> conversations = new ArrayList<>();
    private final Set<Long> pinned = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            // Many rows can change at once (a long text, "mark all read"); reload once.
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 300);
        }
    };

    private ConversationAdapter adapter;
    private View banner;
    private TextView emptyText;
    private Button grantButton;
    private boolean openSettingsOnGrant;
    private boolean observing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ListView list = findViewById(R.id.list);
        banner = findViewById(R.id.banner);
        emptyText = findViewById(R.id.empty_text);
        grantButton = findViewById(R.id.grant);

        adapter = new ConversationAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> openThread(conversations.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(conversations.get(position));
            return true;
        });
        new SwipeHelper(list, this);

        ImageButton fab = findViewById(R.id.fab);
        fab.setBackground(Look.circle(look.accent));
        fab.setImageTintList(ColorStateList.valueOf(look.onAccent));
        fab.setOnClickListener(v -> startActivity(new Intent(this, NewMessageActivity.class)));

        findViewById(R.id.banner_button).setOnClickListener(v -> DefaultSms.request(this));

        grantButton.setOnClickListener(v -> {
            if (openSettingsOnGrant) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } else {
                requestPermissions(permissions(), REQUEST_PERMISSIONS);
            }
        });

        if (!hasSmsPermission()) requestPermissions(permissions(), REQUEST_PERMISSIONS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        banner.setVisibility(DefaultSms.isDefault(this) ? View.GONE : View.VISIBLE);
        if (hasSmsPermission()) {
            startObserving();
            load();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (observing) {
            getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
        handler.removeCallbacks(reload);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasSmsPermission()) {
            startObserving();
            load();
        } else {
            // After "Don't ask again" only the Settings screen can grant it.
            openSettingsOnGrant = !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS);
            emptyText.setText(R.string.permission_needed);
            grantButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SEARCH, 0, R.string.search)
                .setIcon(R.drawable.ic_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_MARK_ALL_READ, 1, R.string.mark_all_read);
        menu.add(0, MENU_SETTINGS, 2, R.string.settings);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SEARCH:
                startActivity(new Intent(this, SearchActivity.class));
                return true;
            case MENU_MARK_ALL_READ:
                if (requireDefault()) {
                    SmsWriter.run(() -> SmsWriter.markAllRead(getApplicationContext()));
                }
                return true;
            case MENU_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSwipeRight(int position) {
        if (position < conversations.size()) call(conversations.get(position));
    }

    @Override
    public void onSwipeLeft(int position) {
        if (position < conversations.size()) confirmDelete(conversations.get(position));
    }

    private String[] permissions() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.READ_SMS);
        list.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS);
        return list.toArray(new String[0]);
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void startObserving() {
        if (observing) return;
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        observing = true;
    }

    private void load() {
        grantButton.setVisibility(View.GONE);
        if (conversations.isEmpty()) emptyText.setText(R.string.loading);

        executor.execute(() -> {
            List<Sms> all = new SmsReader(this).loadConversations();
            List<Sms> visible = new ArrayList<>();
            Set<Long> pins = new HashSet<>();
            for (Sms sms : all) {
                if (Prefs.isBlocked(this, sms.address)) continue;
                if (Prefs.isPinned(this, sms.threadId)) pins.add(sms.threadId);
                visible.add(sms);
            }
            // Stable sort: pinned rows move up, everything keeps its date order.
            Collections.sort(visible, (a, b) ->
                    Boolean.compare(pins.contains(b.threadId), pins.contains(a.threadId)));

            onUi(() -> {
                conversations.clear();
                conversations.addAll(visible);
                pinned.clear();
                pinned.addAll(pins);
                adapter.notifyDataSetChanged();
                if (visible.isEmpty()) emptyText.setText(R.string.no_messages);
            });
        });
    }

    private void openThread(Sms sms) {
        startActivity(new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, sms.threadId));
    }

    private void showActions(Sms sms) {
        boolean isPinned = pinned.contains(sms.threadId);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(getString(isPinned ? R.string.unpin : R.string.pin));
        actions.add(() -> {
            Prefs.setPinned(this, sms.threadId, !isPinned);
            load();
        });
        if (sms.unreadCount > 0) {
            labels.add(getString(R.string.mark_read));
            actions.add(() -> {
                if (requireDefault()) {
                    SmsWriter.run(() -> SmsWriter.markRead(getApplicationContext(), sms.threadId));
                    Notifier.cancel(this, sms.threadId);
                }
            });
        }
        labels.add(getString(R.string.call));
        actions.add(() -> call(sms));
        labels.add(getString(R.string.block));
        actions.add(() -> confirmBlock(sms));
        labels.add(getString(R.string.delete));
        actions.add(() -> confirmDelete(sms));

        new AlertDialog.Builder(this)
                .setTitle(sms.displayName)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void call(Sms sms) {
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", sms.address, null)));
    }

    private void confirmBlock(Sms sms) {
        new AlertDialog.Builder(this)
                .setTitle("Block " + sms.displayName + "?")
                .setMessage("This conversation will be hidden and new texts from this number "
                        + "won't notify you. Nothing gets deleted; unblock any time in Settings.")
                .setPositiveButton(R.string.block, (d, w) -> {
                    Prefs.setBlocked(this, sms.address, true);
                    Notifier.cancel(this, sms.threadId);
                    Toast.makeText(this, "Blocked " + sms.displayName, Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(Sms sms) {
        if (!requireDefault()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete conversation?")
                .setMessage("All messages with " + sms.displayName + " will be deleted.")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Prefs.setPinned(this, sms.threadId, false);
                    Notifier.cancel(this, sms.threadId);
                    SmsWriter.run(() -> SmsWriter.deleteThread(getApplicationContext(), sms.threadId));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class ConversationAdapter extends BaseAdapter {
        @Override public int getCount() { return conversations.size(); }
        @Override public Sms getItem(int position) { return conversations.get(position); }
        @Override public long getItemId(int position) { return conversations.get(position).threadId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_conversation, parent, false);
            Sms sms = getItem(position);
            bindConversation(view, look, sms, pinned.contains(sms.threadId));
            return view;
        }
    }

    /** Fills one conversation row. Also used by search results. */
    static void bindConversation(View view, Look look, Sms sms, boolean isPinned) {
        // A recycled row may still be shifted from a swipe.
        view.findViewById(R.id.content).setTranslationX(0);
        view.findViewById(R.id.swipe_bg).setVisibility(View.INVISIBLE);

        TextView avatar = view.findViewById(R.id.avatar);
        TextView name = view.findViewById(R.id.name);
        TextView date = view.findViewById(R.id.date);
        TextView snippet = view.findViewById(R.id.snippet);
        TextView badge = view.findViewById(R.id.badge);

        avatar.setBackground(Look.circle(Look.avatarColor(sms.address)));
        avatar.setText(Format.initial(sms.displayName));
        name.setText(sms.displayName);
        date.setText(Format.shortDate(view.getContext(), sms.date));

        String prefix = sms.incoming() ? "" : sms.failed() ? "Not sent: " : "You: ";
        snippet.setText(prefix + sms.body);

        boolean unread = sms.unreadCount > 0;
        name.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        snippet.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        date.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        // The snippet always uses the secondary text color, so borrow it for read rows.
        if (unread) date.setTextColor(look.accent); else date.setTextColor(snippet.getTextColors());

        view.findViewById(R.id.pin).setVisibility(isPinned ? View.VISIBLE : View.GONE);
        badge.setVisibility(unread ? View.VISIBLE : View.GONE);
        if (unread) {
            badge.setText(sms.unreadCount > 99 ? "99+" : String.valueOf(sms.unreadCount));
            badge.setBackground(Look.rounded(look.accent, Format.dp(view.getContext(), 10)));
            badge.setTextColor(look.onAccent);
        }
    }
}
