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
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pick a contact or type a number to start a conversation. */
public class NewMessageActivity extends BaseActivity {

    private static final int CONTACT_LIMIT = 300;

    /** {name, number}. A null name means "send to what the user typed". */
    private final List<String[]> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int generation;

    private EditText to;
    private ContactAdapter adapter;
    private String body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_message);
        showUpButton();

        // Text shared from another app, or carried over from an sms: link.
        body = getIntent().getStringExtra(ThreadActivity.EXTRA_BODY);
        if (body == null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            body = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        }

        to = findViewById(R.id.to);
        ListView list = findViewById(R.id.list);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> open(rows.get(position)[1]));

        to.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(search);
                handler.postDelayed(search, 150);
            }
        });
        to.setOnEditorActionListener((v, actionId, event) -> {
            String typed = to.getText().toString().trim();
            if (hasDigit(typed)) {
                open(typed);
            } else if (!rows.isEmpty()) {
                open(rows.get(0)[1]);
            }
            return true;
        });

        search();
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

    private void open(String address) {
        if (TextUtils.isEmpty(address)) return;
        Intent intent = new Intent(this, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, address);
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
