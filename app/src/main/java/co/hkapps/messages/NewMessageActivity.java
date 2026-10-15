package co.hkapps.messages;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pick one or more people (or type numbers) to start a chat or group. Tabs like Textra's:
 * Recents, Starred and contact Groups; also the target for "Share" from other apps.
 */
public class NewMessageActivity extends BaseActivity {

    private static final int CONTACT_LIMIT = 400;
    private static final int MENU_NEXT = 1;

    private static final int TAB_ALL = 0;
    private static final int TAB_RECENTS = 1;
    private static final int TAB_STARRED = 2;
    private static final int TAB_GROUPS = 3;
    private static final String[] TABS = {"All", "Recents", "Starred", "Groups"};

    /** {name, number}. A null name means "add what the user typed"; "#group:ID" adds a whole group. */
    private final List<String[]> rows = new ArrayList<>();
    /** {name, number} of everyone picked so far. */
    private final List<String[]> picked = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int generation;
    private int tab = TAB_ALL;

    private EditText to;
    private LinearLayout chips;
    private View chipsScroll;
    private LinearLayout tabs;
    private ContactAdapter adapter;
    private String body;
    private Intent shared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_message);
        showUpButton();
        if (getActionBar() != null) getActionBar().setSubtitle("Hold a contact to start a group");

        // Text or files shared from another app, a forward, or a body from an sms: link.
        shared = getIntent();
        body = shared.getStringExtra(ThreadActivity.EXTRA_BODY);
        if (body == null && (Intent.ACTION_SEND.equals(shared.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(shared.getAction()))) {
            body = shared.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (isSharing() && getActionBar() != null) {
            setTitle("Send to");
            getActionBar().setSubtitle(null);
        }
        // Direct share: Android already picked the chat.
        String shortcut = shared.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if (shortcut != null && shortcut.startsWith("chat_")) {
            try {
                openThread(Long.parseLong(shortcut.substring(5)));
                return;
            } catch (NumberFormatException ignored) {
                // Fall back to picking.
            }
        }

        to = findViewById(R.id.to);
        chips = findViewById(R.id.chips);
        chipsScroll = findViewById(R.id.chips_scroll);
        tabs = findViewById(R.id.tabs);
        ListView list = findViewById(R.id.list);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        // Tapping one person with nobody else picked opens the chat at once;
        // otherwise the person joins the group being built.
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] row = rows.get(position);
            if (row[1].startsWith("#keep")) {
                openThread(Keep.THREAD_ID);
                return;
            }
            if (row[1].startsWith("#thread:")) {
                openThread(Long.parseLong(row[1].substring(8)));
                return;
            }
            if (row[1].startsWith("#group:")) {
                addGroup(row[1].substring(7));
                return;
            }
            String[] person = {row[0] != null ? row[0] : row[1], row[1]};
            if (picked.isEmpty()) {
                picked.add(person);
                open();
            } else {
                addPicked(person);
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String[] row = rows.get(position);
            if (row[1].startsWith("#")) return false;
            addPicked(new String[]{row[0] != null ? row[0] : row[1], row[1]});
            return true;
        });

        to.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                // A comma or semicolon after a number adds it as a person.
                String text = s.toString();
                if (text.endsWith(",") || text.endsWith(";")) {
                    String typed = text.substring(0, text.length() - 1).trim();
                    if (hasDigit(typed)) {
                        addPicked(new String[]{typed, typed});
                        return;
                    }
                }
                handler.removeCallbacks(search);
                handler.postDelayed(search, 150);
            }
        });
        to.setOnEditorActionListener((v, actionId, event) -> {
            String typed = to.getText().toString().trim();
            if (hasDigit(typed)) picked.add(new String[]{typed, typed});
            else if (picked.isEmpty()) {
                for (String[] r : rows) {
                    if (!r[1].startsWith("#")) {
                        picked.add(new String[]{r[0] != null ? r[0] : r[1], r[1]});
                        break;
                    }
                }
            }
            open();
            return true;
        });

        buildTabs();
        search();
    }

    private boolean isSharing() {
        return Intent.ACTION_SEND.equals(shared.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(shared.getAction())
                || shared.hasExtra(ThreadActivity.EXTRA_FORWARD) || shared.hasExtra(ThreadActivity.EXTRA_STREAMS);
    }

    private void buildTabs() {
        tabs.removeAllViews();
        int pad = (int) Format.dp(this, 14);
        for (int i = 0; i < TABS.length; i++) {
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(pad, pad / 2, pad, pad / 2);
            boolean on = i == tab;
            t.setTextColor(on ? look.onAccent : look.accent);
            t.setBackground(on ? Look.rounded(look.accent, Format.dp(this, 16)) : null);
            int index = i;
            t.setOnClickListener(v -> {
                tab = index;
                buildTabs();
                search();
            });
            tabs.addView(t);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!picked.isEmpty()) {
            menu.add(0, MENU_NEXT, 0, R.string.next)
                    .setIcon(R.drawable.ic_forward)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_NEXT) {
            String typed = to.getText().toString().trim();
            if (hasDigit(typed)) picked.add(new String[]{typed, typed});
            open();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addPicked(String[] person) {
        for (String[] p : picked) {
            if (Prefs.sameAddress(p[1], person[1])) return;
        }
        picked.add(person);
        to.setText("");
        showChips();
    }

    private void addGroup(String groupId) {
        executor.execute(() -> {
            List<String[]> members = groupMembers(groupId);
            onUi(() -> {
                for (String[] m : members) addPicked(m);
            });
        });
    }

    private void showChips() {
        chips.removeAllViews();
        int pad = (int) Format.dp(this, 10);
        int gap = (int) Format.dp(this, 4);
        for (String[] person : new ArrayList<>(picked)) {
            TextView chip = new TextView(this);
            chip.setText(person[0] + "  ✕");
            chip.setTextColor(look.onAccent);
            chip.setBackground(Look.rounded(look.accent, Format.dp(this, 16)));
            chip.setPadding(pad, gap, pad, gap);
            chip.setContentDescription("Remove " + person[0]);
            chip.setOnClickListener(v -> {
                picked.remove(person);
                showChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, 0, gap, 0);
            chips.addView(chip, lp);
        }
        chipsScroll.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
        to.setHint(picked.isEmpty() ? R.string.to_hint : R.string.add_more_hint);
        invalidateOptionsMenu();
    }

    private void search() {
        String query = to.getText().toString().trim();
        int current = ++generation;
        int which = tab;
        executor.execute(() -> {
            List<String[]> found;
            switch (which) {
                case TAB_RECENTS: found = recents(query); break;
                case TAB_STARRED: found = loadContacts(query, true); break;
                case TAB_GROUPS: found = groups(query); break;
                default: found = loadContacts(query, false); break;
            }
            List<String[]> top = new ArrayList<>();
            // Sharing to a chat you already have, or to Keep, is one tap away.
            if (isSharing() && query.isEmpty() && which == TAB_ALL) {
                top.add(new String[]{Keep.NAME + " (notes to self)", "#keep"});
                top.addAll(recentChats(5));
            }
            onUi(() -> {
                if (current != generation) return;  // A newer search already started.
                rows.clear();
                if (hasDigit(query)) rows.add(new String[]{null, query});
                rows.addAll(top);
                rows.addAll(found);
                adapter.notifyDataSetChanged();
                TextView empty = findViewById(R.id.empty_text);
                empty.setText(which == TAB_STARRED ? "Starred contacts appear here, so they are only a single tap away. "
                        + "You can star contacts in your contacts app."
                        : which == TAB_GROUPS ? "Contact groups from your contacts app appear here."
                        : which == TAB_RECENTS ? "Contacts you've recently texted appear here."
                        : hasContactsPermission() ? "No contacts found. Type a phone number." : "Type a phone number.");
            });
        });
    }

    private List<String[]> loadContacts(String query, boolean starredOnly) {
        List<String[]> result = new ArrayList<>();
        if (!hasContactsPermission()) return result;

        Uri uri = query.isEmpty() ? Phone.CONTENT_URI
                : Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query));
        Set<String> seen = new HashSet<>();
        List<String> where = new ArrayList<>();
        if (starredOnly) where.add(Phone.STARRED + " = 1");
        if (Prefs.bool(this, Prefs.MOBILE_ONLY)) where.add(Phone.TYPE + " = " + Phone.TYPE_MOBILE);
        try (Cursor c = getContentResolver().query(uri, new String[]{Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
                where.isEmpty() ? null : TextUtils.join(" AND ", where), null,
                Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            while (c != null && c.moveToNext() && result.size() < CONTACT_LIMIT) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (TextUtils.isEmpty(number)) continue;
                // The same number often appears twice (phone and SIM, say).
                if (!seen.add(name + "|" + number.replaceAll("[^0-9+]", ""))) continue;
                CharSequence label = Phone.getTypeLabel(getResources(), c.getInt(2), c.getString(3));
                result.add(new String[]{TextUtils.isEmpty(name) ? number : name, number, label.toString()});
            }
        } catch (RuntimeException ignored) {
            // Contacts provider unavailable: fall back to typing a number.
        }
        return result;
    }

    /** People texted most recently, newest first. */
    private List<String[]> recents(String query) {
        List<String[]> result = new ArrayList<>();
        MessageReader reader = new MessageReader(this);
        Map<String, String> seen = new LinkedHashMap<>();
        for (Conversation c : reader.loadConversations()) {
            if (c.isGroup()) continue;
            String a = c.address();
            if (seen.containsKey(a) || Prefs.isBlocked(this, a)) continue;
            seen.put(a, c.name);
            if (!query.isEmpty() && !c.name.toLowerCase().contains(query.toLowerCase()) && !a.contains(query)) continue;
            result.add(new String[]{c.name, a});
            if (result.size() >= 60) break;
        }
        return result;
    }

    /** The newest chats, shown first when sharing. */
    private List<String[]> recentChats(int max) {
        List<String[]> result = new ArrayList<>();
        for (Conversation c : new MessageReader(this).loadConversations()) {
            if (Prefs.isBlocked(this, c.addresses)) continue;
            String name = ChatPrefs.name(this, c.threadId);
            result.add(new String[]{name != null ? name : c.name, "#thread:" + c.threadId});
            if (result.size() >= max) break;
        }
        return result;
    }

    /** Contact groups ("Family", "Work"); tapping one adds all its members. */
    private List<String[]> groups(String query) {
        List<String[]> result = new ArrayList<>();
        if (!hasContactsPermission()) return result;
        try (Cursor c = getContentResolver().query(ContactsContract.Groups.CONTENT_SUMMARY_URI,
                new String[]{ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.SUMMARY_COUNT},
                ContactsContract.Groups.DELETED + " = 0", null, ContactsContract.Groups.TITLE + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                String title = c.getString(1);
                if (title == null || c.getInt(2) == 0) continue;
                if (!query.isEmpty() && !title.toLowerCase().contains(query.toLowerCase())) continue;
                result.add(new String[]{title, "#group:" + c.getLong(0), c.getInt(2) + " people"});
            }
        } catch (RuntimeException ignored) {
            // No groups.
        }
        return result;
    }

    private List<String[]> groupMembers(String groupId) {
        List<String[]> people = new ArrayList<>();
        Set<Long> contacts = new HashSet<>();
        try (Cursor c = getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.GroupMembership.CONTACT_ID},
                ContactsContract.Data.MIMETYPE + " = ? AND "
                        + ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID + " = ?",
                new String[]{ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId}, null)) {
            while (c != null && c.moveToNext()) contacts.add(c.getLong(0));
        } catch (RuntimeException ignored) {
            return people;
        }
        for (long id : contacts) {
            try (Cursor c = getContentResolver().query(Phone.CONTENT_URI, new String[]{Phone.DISPLAY_NAME, Phone.NUMBER},
                    Phone.CONTACT_ID + " = ?", new String[]{String.valueOf(id)},
                    Phone.IS_SUPER_PRIMARY + " DESC, " + Phone.TYPE + " = " + Phone.TYPE_MOBILE + " DESC")) {
                if (c != null && c.moveToFirst()) people.add(new String[]{c.getString(0), c.getString(1)});
            } catch (RuntimeException ignored) {
                // Skip.
            }
        }
        return people;
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private void open() {
        if (picked.isEmpty()) return;
        String[] numbers = new String[picked.size()];
        for (int i = 0; i < picked.size(); i++) numbers[i] = picked.get(i)[1];
        Intent intent = withShared(new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_ADDRESSES, numbers));
        startActivity(intent);
        finish();
    }

    private void openThread(long threadId) {
        startActivity(withShared(new Intent(this, ThreadActivity.class).putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)));
        finish();
    }

    /** Carries the shared text, files or forwarded message on to the chat. */
    private Intent withShared(Intent intent) {
        if (body != null) intent.putExtra(ThreadActivity.EXTRA_BODY, body);
        String forward = shared.getStringExtra(ThreadActivity.EXTRA_FORWARD);
        if (forward != null) intent.putExtra(ThreadActivity.EXTRA_FORWARD, forward);
        ArrayList<Uri> streams = new ArrayList<>();
        ArrayList<Uri> extra = shared.getParcelableArrayListExtra(ThreadActivity.EXTRA_STREAMS);
        if (extra != null) streams.addAll(extra);
        if (Intent.ACTION_SEND_MULTIPLE.equals(shared.getAction())) {
            ArrayList<Uri> many = shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) streams.addAll(many);
        } else if (Intent.ACTION_SEND.equals(shared.getAction())) {
            Uri one = shared.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) streams.add(one);
        }
        if (!streams.isEmpty()) {
            intent.putParcelableArrayListExtra(ThreadActivity.EXTRA_STREAMS, streams);
            // Pass on the read access the sharing app gave us.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData clip = android.content.ClipData.newRawUri("shared", streams.get(0));
            for (int i = 1; i < streams.size(); i++) clip.addItem(new android.content.ClipData.Item(streams.get(i)));
            intent.setClipData(clip);
        }
        return intent;
    }

    private class ContactAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public String[] getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_contact, parent, false);
            String[] row = getItem(position);
            TextView avatar = view.findViewById(R.id.avatar);
            TextView name = view.findViewById(R.id.name);
            TextView number = view.findViewById(R.id.number);

            if (row[0] == null) {
                avatar.setBackground(Look.circle(look.accent));
                avatar.setTextColor(look.onAccent);
                avatar.setText("+");
                name.setText("Send to " + row[1]);
                number.setVisibility(View.GONE);
            } else if (row[1].startsWith("#")) {
                avatar.setBackground(Look.circle(look.accent));
                avatar.setTextColor(look.onAccent);
                avatar.setText(row[1].startsWith("#keep") ? "📌" : row[1].startsWith("#group") ? "👥" : Format.initial(row[0]));
                name.setText(row[0]);
                number.setText(row.length > 2 ? row[2] : row[1].startsWith("#thread") ? "Recent chat" : "");
                number.setVisibility(number.length() > 0 ? View.VISIBLE : View.GONE);
            } else {
                avatar.setBackground(Look.circle(Look.avatarColor(row[1])));
                avatar.setTextColor(0xFFFFFFFF);
                avatar.setText(Format.initial(row[0]));
                name.setText(row[0]);
                number.setText(row.length > 2 && row[2] != null ? row[1] + "  ·  " + row[2] : row[1]);
                number.setVisibility(View.VISIBLE);
            }
            return view;
        }
    }
}
