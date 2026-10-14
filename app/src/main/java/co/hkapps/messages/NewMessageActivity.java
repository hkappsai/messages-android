package co.hkapps.messages;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import java.util.List;
import java.util.Set;

/** Pick one or more people (or type numbers) to start a conversation or group. */
public class NewMessageActivity extends BaseActivity {

    private static final int CONTACT_LIMIT = 300;
    private static final int MENU_NEXT = 1;

    /** {name, number}. A null name means "add what the user typed". */
    private final List<String[]> rows = new ArrayList<>();
    /** {name, number} of everyone picked so far. */
    private final List<String[]> picked = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int generation;

    private EditText to;
    private LinearLayout chips;
    private View chipsScroll;
    private ContactAdapter adapter;
    private String body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_message);
        showUpButton();
        if (getActionBar() != null) getActionBar().setSubtitle("Hold a contact to start a group");

        // Text shared from another app, or carried over from an sms: link.
        body = getIntent().getStringExtra(ThreadActivity.EXTRA_BODY);
        if (body == null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            body = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        }

        to = findViewById(R.id.to);
        chips = findViewById(R.id.chips);
        chipsScroll = findViewById(R.id.chips_scroll);
        ListView list = findViewById(R.id.list);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        // Tapping one person with nobody else picked opens the chat at once;
        // otherwise the person joins the group being built.
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] row = rows.get(position);
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
            else if (picked.isEmpty() && !rows.isEmpty()) picked.add(new String[]{rows.get(0)[0], rows.get(0)[1]});
            open();
            return true;
        });

        search();
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
        executor.execute(() -> {
            List<String[]> contacts = loadContacts(query);
            onUi(() -> {
                if (current != generation) return;  // A newer search already started.
                rows.clear();
                if (hasDigit(query)) rows.add(new String[]{null, query});
                rows.addAll(contacts);
                adapter.notifyDataSetChanged();
                TextView empty = findViewById(R.id.empty_text);
                empty.setText(hasContactsPermission()
                        ? "No contacts found. Type a phone number."
                        : "Type a phone number.");
            });
        });
    }

    private List<String[]> loadContacts(String query) {
        List<String[]> result = new ArrayList<>();
        if (!hasContactsPermission()) return result;

        Uri uri = query.isEmpty() ? Phone.CONTENT_URI
                : Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query));
        Set<String> seen = new HashSet<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{Phone.DISPLAY_NAME, Phone.NUMBER},
                    null, null, Phone.DISPLAY_NAME + " COLLATE NOCASE ASC");
            while (c != null && c.moveToNext() && result.size() < CONTACT_LIMIT) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (TextUtils.isEmpty(number)) continue;
                // The same number often appears twice (phone and SIM, say).
                if (!seen.add(name + "|" + number.replaceAll("[^0-9+]", ""))) continue;
                result.add(new String[]{TextUtils.isEmpty(name) ? number : name, number});
            }
        } catch (RuntimeException ignored) {
            // Contacts provider unavailable: fall back to typing a number.
        } finally {
            if (c != null) c.close();
        }
        return result;
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
        Intent intent = new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_ADDRESSES, numbers);
        if (body != null) intent.putExtra(ThreadActivity.EXTRA_BODY, body);
        startActivity(intent);
        finish();
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
            } else {
                avatar.setBackground(Look.circle(Look.avatarColor(row[1])));
                avatar.setTextColor(0xFFFFFFFF);
                avatar.setText(Format.initial(row[0]));
                name.setText(row[0]);
                number.setText(row[1]);
                number.setVisibility(View.VISIBLE);
            }
            return view;
        }
    }
}
