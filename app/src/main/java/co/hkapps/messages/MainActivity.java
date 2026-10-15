package co.hkapps.messages;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One row per conversation. Pinned ones first, then newest first. */
public class MainActivity extends BaseActivity implements SwipeHelper.Callback {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int MENU_SEARCH = 1;
    private static final int MENU_MARK_ALL_READ = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_KEEP = 4;
    private static final int MENU_BLOCKLIST = 5;

    /** Undo offered by another screen that closed right after deleting (a whole chat, say). */
    private static List<String> undoKeys;
    private static String undoLabel;

    static void offerUndo(List<String> keys, String label) {
        undoKeys = keys;
        undoLabel = label;
    }

    private static final ExecutorService AVATARS = Executors.newFixedThreadPool(2);

    private final List<Conversation> conversations = new ArrayList<>();
    private final Set<Long> pinned = new HashSet<>();
    private final Map<Long, String> drafts = new HashMap<>();
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
    private ListView list;
    private View banner;
    private TextView emptyText;
    private Button grantButton;
    private boolean openSettingsOnGrant;
    private boolean observing;
    private ActionMode actionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        banner = findViewById(R.id.banner);
        emptyText = findViewById(R.id.empty_text);
        grantButton = findViewById(R.id.grant);

        adapter = new ConversationAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(new SelectionMode());
        list.setOnItemClickListener((parent, view, position, id) -> openThread(conversations.get(position)));
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

        // Housekeeping: deletes whose undo ran out while the app was closed, clean-up, Quick Compose.
        Context app = getApplicationContext();
        SmsWriter.run(() -> Deleter.purgeDue(app));
        Cleanup.schedule(app);
        Notifier.updateQuickCompose(app);
    }

    @Override
    protected void onResume() {
        super.onResume();
        banner.setVisibility(DefaultSms.isDefault(this) ? View.GONE : View.VISIBLE);
        if (hasSmsPermission()) {
            startObserving();
            load();
        }
        if (undoKeys != null) {
            List<String> keys = undoKeys;
            UndoBar.show(this, look, undoLabel, () -> {
                Deleter.undo(this, keys);
                load();
            });
            undoKeys = null;
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
        menu.add(0, MENU_KEEP, 2, "Keep (notes to self)");
        menu.add(0, MENU_BLOCKLIST, 3, "Blocklisted");
        menu.add(0, MENU_SETTINGS, 4, R.string.settings);
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
                    Context app = getApplicationContext();
                    SmsWriter.run(() -> SmsWriter.markAllRead(app));
                    Notifier.cancelAllMessages(app);
                    Toast.makeText(this, "Marked all as read", Toast.LENGTH_SHORT).show();
                }
                return true;
            case MENU_KEEP:
                startActivity(new Intent(this, ThreadActivity.class).putExtra(ThreadActivity.EXTRA_THREAD_ID, Keep.THREAD_ID));
                return true;
            case MENU_BLOCKLIST:
                startActivity(new Intent(this, BlocklistActivity.class));
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
        if (position < conversations.size()) delete(Collections.singletonList(conversations.get(position)));
    }

    private String[] permissions() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.READ_SMS);
        list.add(Manifest.permission.READ_CONTACTS);
        list.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS);
        return list.toArray(new String[0]);
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void startObserving() {
        if (observing) return;
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        getContentResolver().registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer);
        observing = true;
    }

    private void load() {
        grantButton.setVisibility(View.GONE);
        if (conversations.isEmpty()) emptyText.setText(R.string.loading);

        executor.execute(() -> {
            List<Conversation> all = new MessageReader(this).loadConversations();
            Set<String> hidden = Deleter.pending(this);
            Map<Long, String> draftMap = AppDb.get(this).drafts();
            List<Conversation> visible = new ArrayList<>();
            Set<Long> pins = new HashSet<>();
            for (Conversation c : all) {
                if (hidden.contains("thread:" + c.threadId)) continue;
                if (Prefs.isBlocked(this, c.addresses)) continue;
                if (Prefs.isPinned(this, c.threadId)) pins.add(c.threadId);
                visible.add(c);
            }
            // The Keep chat sits with the pinned chats once it has something in it.
            Message keepLatest = Keep.latest(this);
            if (keepLatest != null) {
                visible.add(0, new Conversation(Keep.THREAD_ID, Collections.<String>emptyList(), Keep.NAME, keepLatest, 0));
                pins.add(Keep.THREAD_ID);
            }
            // Stable sort: pinned rows move up, everything keeps its date order.
            Collections.sort(visible, (a, b) ->
                    Boolean.compare(pins.contains(b.threadId), pins.contains(a.threadId)));

            onUi(() -> {
                conversations.clear();
                conversations.addAll(visible);
                pinned.clear();
                pinned.addAll(pins);
                drafts.clear();
                drafts.putAll(draftMap);
                adapter.notifyDataSetChanged();
                if (visible.isEmpty()) emptyText.setText(R.string.no_messages);
            });
        });
    }

    private void openThread(Conversation c) {
        startActivity(new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, c.threadId));
    }

    private void call(Conversation c) {
        if (c.isGroup() || Keep.is(c.threadId)) {
            Toast.makeText(this, "Can't call a group", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", c.address(), null)));
    }

    /** Deletes with a 20 second undo, like Textra. */
    private void delete(List<Conversation> picked) {
        if (!requireDefault()) return;
        List<String> keys = new ArrayList<>();
        for (Conversation c : picked) {
            if (Keep.is(c.threadId)) continue;
            keys.add("thread:" + c.threadId);
            Notifier.cancel(this, c.threadId);
        }
        if (keys.isEmpty()) return;
        Deleter.schedule(this, keys);
        load();
        UndoBar.show(this, look, keys.size() == 1 ? "Chat deleted" : keys.size() + " chats deleted", () -> {
            Deleter.undo(this, keys);
            load();
        });
    }

    private List<Conversation> checked() {
        List<Conversation> picked = new ArrayList<>();
        android.util.SparseBooleanArray c = list.getCheckedItemPositions();
        for (int i = 0; i < c.size(); i++) {
            if (c.valueAt(i) && c.keyAt(i) < conversations.size()) picked.add(conversations.get(c.keyAt(i)));
        }
        return picked;
    }

    /** Long-press in the chat list: select chats, then act on all of them. */
    private class SelectionMode implements AbsListView.MultiChoiceModeListener {
        private static final int DELETE = 1, PIN = 2, READ = 3, UNREAD = 4, MUTE = 5, BLOCK = 6, ALL = 7, CUSTOMIZE = 8;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            actionMode = mode;
            menu.add(0, DELETE, 0, R.string.delete).setIcon(R.drawable.ic_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, PIN, 1, R.string.pin).setIcon(R.drawable.ic_pin).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, READ, 2, R.string.mark_read).setIcon(R.drawable.ic_check).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, UNREAD, 3, R.string.mark_unread);
            menu.add(0, MUTE, 4, "Turn notifications off");
            menu.add(0, BLOCK, 5, "Blocklist");
            menu.add(0, CUSTOMIZE, 6, R.string.customize);
            menu.add(0, ALL, 7, "Select all");
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            List<Conversation> picked = checked();
            boolean allPinned = !picked.isEmpty();
            boolean allMuted = !picked.isEmpty();
            for (Conversation c : picked) {
                if (!pinned.contains(c.threadId)) allPinned = false;
                if (!ChatPrefs.muted(MainActivity.this, c.threadId)) allMuted = false;
            }
            menu.findItem(PIN).setTitle(allPinned ? R.string.unpin : R.string.pin);
            menu.findItem(MUTE).setTitle(allMuted ? "Turn notifications on" : "Turn notifications off");
            menu.findItem(CUSTOMIZE).setVisible(picked.size() == 1 && !Keep.is(picked.get(0).threadId));
            return true;
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            int n = list.getCheckedItemCount();
            mode.setTitle(n == 1 ? "1 selected" : n + " selected");
            mode.invalidate();
            adapter.notifyDataSetChanged();
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            List<Conversation> picked = checked();
            Context app = getApplicationContext();
            switch (item.getItemId()) {
                case DELETE:
                    delete(picked);
                    break;
                case PIN: {
                    boolean pin = item.getTitle().toString().equals(getString(R.string.pin));
                    for (Conversation c : picked) if (!Keep.is(c.threadId)) Prefs.setPinned(app, c.threadId, pin);
                    Toast.makeText(app, pin ? "Pinned to top of chat list." : "Un-pinned.", Toast.LENGTH_SHORT).show();
                    load();
                    break;
                }
                case READ:
                    if (!requireDefault()) return true;
                    SmsWriter.run(() -> {
                        for (Conversation c : picked) SmsWriter.markRead(app, c.threadId);
                    });
                    for (Conversation c : picked) Notifier.cancel(app, c.threadId);
                    break;
                case UNREAD:
                    if (!requireDefault()) return true;
                    SmsWriter.run(() -> {
                        for (Conversation c : picked) if (!Keep.is(c.threadId)) SmsWriter.markUnread(app, c.threadId);
                    });
                    break;
                case MUTE: {
                    boolean mute = item.getTitle().toString().endsWith("off");
                    for (Conversation c : picked) {
                        if (!Keep.is(c.threadId)) ChatPrefs.put(app, c.threadId, Prefs.NOTIFY, mute ? Boolean.FALSE : null);
                    }
                    Toast.makeText(app, mute ? "Muted." : "Un-muted.", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                    break;
                }
                case BLOCK:
                    for (Conversation c : picked) {
                        for (String a : c.addresses) Prefs.setBlocked(app, a, true);
                        Notifier.cancel(app, c.threadId);
                    }
                    Toast.makeText(app, "Blocklisted. See Settings > Blocklist to un-blocklist", Toast.LENGTH_LONG).show();
                    load();
                    break;
                case CUSTOMIZE:
                    if (!picked.isEmpty()) {
                        startActivity(new Intent(MainActivity.this, ChatSettingsActivity.class)
                                .putExtra(ThreadActivity.EXTRA_THREAD_ID, picked.get(0).threadId));
                    }
                    break;
                case ALL:
                    for (int i = 0; i < conversations.size(); i++) list.setItemChecked(i, true);
                    return true;
                default:
                    return false;
            }
            mode.finish();
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            adapter.notifyDataSetChanged();
        }
    }

    private class ConversationAdapter extends BaseAdapter {
        @Override public int getCount() { return conversations.size(); }
        @Override public Conversation getItem(int position) { return conversations.get(position); }
        @Override public long getItemId(int position) { return conversations.get(position).threadId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_conversation, parent, false);
            Conversation c = getItem(position);
            String title = Keep.is(c.threadId) ? Keep.NAME : ChatPrefs.name(MainActivity.this, c.threadId);
            if (title == null) title = c.name;
            bindConversation(view, look, title, c.address(), c.isGroup(), c.latest, c.unreadCount,
                    pinned.contains(c.threadId));
            boolean muted = !Keep.is(c.threadId) && ChatPrefs.muted(MainActivity.this, c.threadId);
            view.findViewById(R.id.muted).setVisibility(muted ? View.VISIBLE : View.GONE);
            String draft = drafts.get(c.threadId);
            if (draft != null) {
                TextView snippet = view.findViewById(R.id.snippet);
                android.text.SpannableString s = new android.text.SpannableString("Draft: " + draft);
                s.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.failed)), 0, 6, 0);
                snippet.setText(s);
            }
            if (Keep.is(c.threadId)) {
                TextView avatar = view.findViewById(R.id.avatar);
                avatar.setText("📌");
                avatar.setBackground(Look.circle(look.accent));
            } else {
                loadPhoto(view.findViewById(R.id.avatar), c, title);
            }
            boolean checked = list.isItemChecked(position);
            view.findViewById(R.id.content).setBackgroundColor(checked ? blend(look.accent)
                    : resolveBackground());
            return view;
        }
    }

    private int resolveBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        return tv.data;
    }

    private int blend(int accent) {
        int bg = resolveBackground();
        float a = 0.2f;
        int r = (int) (((accent >> 16) & 0xFF) * a + ((bg >> 16) & 0xFF) * (1 - a));
        int g = (int) (((accent >> 8) & 0xFF) * a + ((bg >> 8) & 0xFF) * (1 - a));
        int b = (int) ((accent & 0xFF) * a + (bg & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Contact pictures load in the background and replace the letter avatar. */
    private void loadPhoto(TextView avatar, Conversation c, String title) {
        if (c.isGroup()) return;
        String address = c.address();
        avatar.setTag(address);
        int size = (int) Format.dp(this, 48);
        int color = Look.chatAccent(this, c.threadId, c.addresses);
        int bg = color != 0 ? color : Look.avatarColor(address);
        Context app = getApplicationContext();
        AVATARS.execute(() -> {
            Bitmap b = Avatars.get(app, address, title, size, bg);
            avatar.post(() -> {
                if (!address.equals(avatar.getTag())) return;
                avatar.setText("");
                avatar.setBackground(new BitmapDrawable(getResources(), b));
            });
        });
    }

    /** Fills one conversation row. Also used by search results. */
    static void bindConversation(View view, Look look, String title, String address, boolean group,
                                 Message latest, int unreadCount, boolean isPinned) {
        // A recycled row may still be shifted from a swipe.
        view.findViewById(R.id.content).setTranslationX(0);
        view.findViewById(R.id.swipe_bg).setVisibility(View.INVISIBLE);

        TextView avatar = view.findViewById(R.id.avatar);
        TextView name = view.findViewById(R.id.name);
        TextView date = view.findViewById(R.id.date);
        TextView snippet = view.findViewById(R.id.snippet);
        TextView badge = view.findViewById(R.id.badge);

        avatar.setTag(null);
        avatar.setBackground(Look.circle(Look.avatarColor(group ? title : address)));
        avatar.setText(group ? "👥" : Format.initial(title));
        name.setText(title);
        name.setTypeface(look.typeface());
        date.setText(Format.shortDate(view.getContext(), latest.date));

        String prefix;
        if (latest.incoming()) {
            prefix = group && latest.senderName != null ? latest.senderName + ": " : "";
        } else if (latest.failed()) {
            prefix = "Not sent: ";
        } else if (latest.sending()) {
            prefix = "Sending: ";
        } else if (latest.threadId == Keep.THREAD_ID) {
            prefix = "";
        } else {
            prefix = "You: ";
        }
        snippet.setText(prefix + latest.preview());

        boolean unread = unreadCount > 0;
        name.setTypeface(look.typeface(), unread ? Typeface.BOLD : Typeface.NORMAL);
        snippet.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        date.setTypeface(null, unread ? Typeface.BOLD : Typeface.NORMAL);
        // The snippet always uses the secondary text color, so borrow it for read rows.
        if (unread) date.setTextColor(look.accent); else date.setTextColor(snippet.getTextColors());

        view.findViewById(R.id.pin).setVisibility(isPinned ? View.VISIBLE : View.GONE);
        View muted = view.findViewById(R.id.muted);
        if (muted != null) muted.setVisibility(View.GONE);
        badge.setVisibility(unread ? View.VISIBLE : View.GONE);
        if (unread) {
            badge.setText(unreadCount > 99 ? "99+" : String.valueOf(unreadCount));
            badge.setBackground(Look.rounded(look.accent, Format.dp(view.getContext(), 10)));
            badge.setTextColor(look.onAccent);
        }
    }
}
