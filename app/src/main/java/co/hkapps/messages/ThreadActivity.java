package co.hkapps.messages;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsMessage;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** One conversation as chat bubbles, with a box to reply. */
public class ThreadActivity extends BaseActivity {

    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_MESSAGE_ID = "message_id";

    /** The conversation on screen right now, so new texts in it don't notify. */
    static volatile long visibleThreadId = -1;

    private static final int MENU_CALL = 1;
    private static final int MENU_PIN = 2;
    private static final int MENU_BLOCK = 3;
    private static final int MENU_DELETE = 4;

    // Show a time stamp when the next message comes this much later.
    private static final long TIME_GAP_MS = 10 * 60 * 1000;

    private final List<Sms> messages = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 200);
        }
    };

    // Written on the background thread while the conversation gets resolved.
    private volatile long threadId = -1;
    private volatile String address;
    private long jumpToMessageId = -1;
    private boolean resumed;
    private boolean observing;
    private SmsReader reader;

    private MessageAdapter adapter;
    private ListView list;
    private EditText input;
    private ImageButton sendButton;
    private TextView counter;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thread);
        showUpButton();
        reader = new SmsReader(this);

        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        sendButton = findViewById(R.id.send);
        counter = findViewById(R.id.counter);
        emptyText = findViewById(R.id.empty_text);

        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));

        sendButton.setBackground(Look.circle(look.accent));
        sendButton.setImageTintList(ColorStateList.valueOf(look.onAccent));
        sendButton.setOnClickListener(v -> send());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateCounter(s); }
        });
        updateCounter(input.getText());

        readIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!observing) {
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
            observing = true;
        }
        if (threadId != -1) onConversationVisible();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        visibleThreadId = -1;
        if (observing) {
            getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
        handler.removeCallbacks(reload);
    }

    private void readIntent(Intent intent) {
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
        address = intent.getStringExtra(EXTRA_ADDRESS);
        jumpToMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);

        // Links like "smsto:5550100?body=Hi" from other apps.
        Uri data = intent.getData();
        if (data != null) address = addressFromUri(data);

        String body = intent.getStringExtra(EXTRA_BODY);
        if (body == null) body = intent.getStringExtra("sms_body");
        if (body == null) body = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (body == null) body = bodyFromUri(data);
        if (body != null) {
            input.setText(body);
            input.setSelection(body.length());
        }

        if (threadId == -1 && TextUtils.isEmpty(address)) {
            // Nobody to talk to yet: let the user pick someone.
            Intent pick = new Intent(this, NewMessageActivity.class);
            if (body != null) pick.putExtra(EXTRA_BODY, body);
            startActivity(pick);
            finish();
            return;
        }

        if (address != null) setTitle(address);
        executor.execute(() -> {
            if (threadId == -1) {
                try {
                    threadId = Telephony.Threads.getOrCreateThreadId(this, address);
                } catch (RuntimeException ignored) {
                    // No SMS access yet; sending still works by number.
                }
            }
            if (address == null) address = reader.addressOfThread(threadId);
            String name = reader.lookupName(address);
            onUi(() -> {
                setTitle(name);
                invalidateOptionsMenu();
                if (resumed) onConversationVisible();
            });
        });
    }

    /** The conversation is known and on screen: load it and clear its notification. */
    private void onConversationVisible() {
        long id = threadId;
        if (id == -1) return;
        visibleThreadId = id;
        Notifier.cancel(this, id);
        if (DefaultSms.isDefault(this)) {
            Context app = getApplicationContext();
            SmsWriter.run(() -> SmsWriter.markRead(app, id));
        }
        load();
    }

    private void load() {
        long id = threadId;
        if (id == -1) {
            emptyText.setText(R.string.no_messages);
            return;
        }
        executor.execute(() -> {
            List<Sms> result = reader.loadThread(id);
            onUi(() -> {
                messages.clear();
                messages.addAll(result);
                adapter.notifyDataSetChanged();
                if (result.isEmpty()) emptyText.setText(R.string.no_messages);
                if (jumpToMessageId != -1) {
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).id == jumpToMessageId) {
                            list.setSelection(i);
                            break;
                        }
                    }
                    jumpToMessageId = -1;
                }
            });
        });
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || TextUtils.isEmpty(address)) return;
        if (!requireDefault()) return;

        input.setText("");
        Context app = getApplicationContext();
        String to = address;
        SmsWriter.run(() -> SmsWriter.send(app, to, text));
    }

    /** Characters left in the current SMS, and how many SMS the text will take. */
    private void updateCounter(CharSequence text) {
        boolean empty = text.toString().trim().isEmpty();
        sendButton.setEnabled(!empty);
        sendButton.setAlpha(empty ? 0.4f : 1f);
        if (text.length() == 0) {
            counter.setVisibility(View.GONE);
            return;
        }
        int[] length = SmsMessage.calculateLength(text, false);
        int parts = length[0];
        int left = length[2];
        if (parts > 1 || left <= 20) {
            counter.setText(parts > 1 ? left + " / " + parts : String.valueOf(left));
            counter.setVisibility(View.VISIBLE);
        } else {
            counter.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (TextUtils.isEmpty(address)) return true;
        menu.add(0, MENU_CALL, 0, R.string.call)
                .setIcon(R.drawable.ic_call)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        if (threadId != -1) {
            menu.add(0, MENU_PIN, 1, Prefs.isPinned(this, threadId) ? R.string.unpin : R.string.pin);
        }
        menu.add(0, MENU_BLOCK, 2, Prefs.isBlocked(this, address) ? R.string.unblock : R.string.block);
        if (threadId != -1) menu.add(0, MENU_DELETE, 3, R.string.delete);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CALL:
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null)));
                return true;
            case MENU_PIN:
                Prefs.setPinned(this, threadId, !Prefs.isPinned(this, threadId));
                invalidateOptionsMenu();
                return true;
            case MENU_BLOCK: {
                boolean block = !Prefs.isBlocked(this, address);
                Prefs.setBlocked(this, address, block);
                Toast.makeText(this, block ? "Blocked" : "Unblocked", Toast.LENGTH_SHORT).show();
                invalidateOptionsMenu();
                return true;
            }
            case MENU_DELETE:
                confirmDeleteThread();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void confirmDeleteThread() {
        if (!requireDefault()) return;
        long id = threadId;
        new AlertDialog.Builder(this)
                .setTitle("Delete conversation?")
                .setMessage("All messages in this conversation will be deleted.")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Context app = getApplicationContext();
                    Prefs.setPinned(this, id, false);
                    SmsWriter.run(() -> SmsWriter.deleteThread(app, id));
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMessageActions(Sms sms) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (sms.failed()) {
            labels.add("Send again");
            actions.add(() -> {
                if (requireDefault()) {
                    Context app = getApplicationContext();
                    SmsWriter.run(() -> SmsWriter.resend(app, sms));
                }
            });
        }
        labels.add("Copy text");
        actions.add(() -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("message", sms.body));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        labels.add("Select text");
        actions.add(() -> showSelectableText(sms.body));
        labels.add("Share");
        actions.add(() -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sms.body),
                null)));
        labels.add(getString(R.string.delete));
        actions.add(() -> {
            if (!requireDefault()) return;
            new AlertDialog.Builder(this)
                    .setMessage("Delete this message?")
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        Context app = getApplicationContext();
                        SmsWriter.run(() -> SmsWriter.deleteMessage(app, sms.id));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Lets the user copy just part of a message. */
    private void showSelectableText(String text) {
        TextView view = new TextView(this);
        int pad = (int) Format.dp(this, 24);
        view.setPadding(pad, pad, pad, 0);
        view.setTextSize(16);
        view.setTextIsSelectable(true);
        view.setText(text);
        new AlertDialog.Builder(this)
                .setTitle("Select text")
                .setView(view)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private class MessageAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Sms getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return messages.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_message, parent, false);
            Sms sms = getItem(position);
            bindBubble(view, look, sms.incoming(), sms.body);

            // Group bubbles: only show the time at the end of a run of messages.
            TextView time = view.findViewById(R.id.time);
            Sms next = position + 1 < messages.size() ? messages.get(position + 1) : null;
            boolean endOfRun = next == null || next.incoming() != sms.incoming()
                    || next.date - sms.date > TIME_GAP_MS;
            if (sms.failed()) {
                time.setText("Not sent. Tap to try again");
                time.setTextColor(getColor(R.color.failed));
                time.setVisibility(View.VISIBLE);
            } else if (sms.sending()) {
                time.setText("Sending…");
                time.setTextColor(emptyText.getTextColors());
                time.setVisibility(View.VISIBLE);
            } else if (endOfRun) {
                time.setText(Format.messageTime(parent.getContext(), sms.date));
                time.setTextColor(emptyText.getTextColors());
                time.setVisibility(View.VISIBLE);
            } else {
                time.setVisibility(View.GONE);
            }

            // Links in the text take taps, so the bubble handles its own clicks.
            TextView body = view.findViewById(R.id.body);
            body.setOnLongClickListener(v -> {
                showMessageActions(sms);
                return true;
            });
            body.setOnClickListener(v -> {
                if (sms.failed()) showMessageActions(sms);
            });
            return view;
        }
    }

    /** Styles a message row: received on the left in grey, sent on the right in color. */
    static void bindBubble(View row, Look look, boolean incoming, CharSequence text) {
        LinearLayout layout = row.findViewById(R.id.row);
        TextView body = row.findViewById(R.id.body);
        layout.setGravity(incoming ? Gravity.START : Gravity.END);
        body.setBackground(Look.rounded(incoming ? look.bubbleIn : look.bubbleOut,
                Format.dp(row.getContext(), 18)));
        body.setTextColor(incoming ? look.bubbleInText : look.bubbleOutText);
        body.setLinkTextColor(incoming ? look.accent : look.bubbleOutText);
        body.setText(text);
    }

    /** First recipient of an sms:/smsto: link, without any "?body=" part. */
    static String addressFromUri(Uri uri) {
        if (uri == null || uri.getSchemeSpecificPart() == null) return null;
        String part = uri.getSchemeSpecificPart();
        int query = part.indexOf('?');
        if (query >= 0) part = part.substring(0, query);
        String first = part.split("[,;]")[0].trim();
        return first.isEmpty() ? null : first;
    }

    /** The "body=" value of an sms: link, if any. */
    static String bodyFromUri(Uri uri) {
        if (uri == null || uri.getSchemeSpecificPart() == null) return null;
        String part = uri.getSchemeSpecificPart();
        int query = part.indexOf('?');
        if (query < 0) return null;
        for (String pair : part.substring(query + 1).split("&")) {
            if (pair.startsWith("body=")) return Uri.decode(pair.substring(5));
        }
        return null;
    }
}
