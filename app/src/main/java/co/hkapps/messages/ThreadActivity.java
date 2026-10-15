package co.hkapps.messages;

import android.Manifest;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.Patterns;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * One conversation as chat bubbles, with a box to reply, the "+" panel for photos, emoji, GIFs and
 * more, multi-select, and per-chat settings like SIM, signature and send delay.
 */
public class ThreadActivity extends BaseActivity implements AttachPanel.Host {

    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_ADDRESSES = "addresses";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_BUBBLE = "bubble";
    /** Files to attach right away (shared from another app, or forwarded). */
    public static final String EXTRA_STREAMS = "streams";
    /** "sms:12" / "mms:7": a message to forward into this chat. */
    public static final String EXTRA_FORWARD = "forward";

    /** The conversation on screen right now, so new texts in it don't notify. */
    static volatile long visibleThreadId = -1;

    private static final int MENU_CALL = 1;
    private static final int MENU_CUSTOMIZE = 2;
    private static final int MENU_MEDIA = 3;
    private static final int MENU_PIN = 4;
    private static final int MENU_MUTE = 5;
    private static final int MENU_UNREAD = 6;
    private static final int MENU_HOME = 7;
    private static final int MENU_BLOCK = 8;
    private static final int MENU_EMAIL = 9;
    private static final int MENU_DELETE = 10;
    private static final int MENU_ADD_CONTACT = 11;

    private static final int REQUEST_PICK = 10;
    private static final int REQUEST_CAMERA = 11;
    private static final int REQUEST_AUDIO_PERMISSION = 12;
    private static final int REQUEST_CONTACT = 13;
    private static final int REQUEST_LOCATION = 14;
    private static final String STATE_CAMERA_FILE = "camera_file";

    // Show a time stamp when the next message comes this much later.
    private static final long TIME_GAP_MS = 10 * 60 * 1000;
    // Room kept free in an MMS for headers, layout and text.
    private static final int MMS_OVERHEAD = 16 * 1024;

    private final List<Message> messages = new ArrayList<>();
    private final Map<Long, Scheduler.Item> scheduled = new HashMap<>();
    private final List<Attachment> pending = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 200);
        }
    };

    /** Texts waiting out the send delay, by their temporary id. */
    private final Map<Long, Delayed> delayed = new HashMap<>();

    private static final class Delayed {
        long id;
        long due;
        List<String> to;
        String text;
        List<Attachment> files;
        int subId;
        Runnable fire;
    }

    /** Link previews worked out so far: url to info (or null for "nothing to show"). */
    private final Map<String, LinkPreview.Info> previews = new HashMap<>();
    private final Set<String> previewsLoading = new HashSet<>();

    // Written on the background thread while the conversation gets resolved.
    private volatile long threadId = -1;
    private volatile List<String> recipients = Collections.emptyList();
    private long jumpToMessageId = -1;
    private boolean resumed;
    private boolean observing;
    private boolean keep;
    private boolean bubble;
    private MessageReader reader;
    private File cameraFile;
    private int lastIncomingSub = -1;
    private int chosenSub = -2;

    private final VoiceRecorder recorder = new VoiceRecorder();
    private final Runnable recordingTick = new Runnable() {
        @Override
        public void run() {
            if (!recorder.isRecording()) return;
            long ms = recorder.elapsedMillis();
            recordingTime.setText("● Recording  " + formatDuration(ms));
            if (ms >= VoiceRecorder.MAX_SECONDS * 1000L) {
                stopRecording(true);
            } else {
                handler.postDelayed(this, 250);
            }
        }
    };

    private MediaPlayer player;
    private long playingPartId = -1;
    private final Runnable playbackTick = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            View bar = list.findViewWithTag("progress:" + playingPartId);
            if (bar instanceof ProgressBar) {
                ((ProgressBar) bar).setMax(Math.max(1, player.getDuration()));
                ((ProgressBar) bar).setProgress(player.getCurrentPosition());
            }
            handler.postDelayed(this, 200);
        }
    };
    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (delayed.isEmpty()) return;
            adapter.notifyDataSetChanged();
            handler.postDelayed(this, 500);
        }
    };

    private MessageAdapter adapter;
    private ListView list;
    private EditText input;
    private ImageButton sendButton;
    private ImageButton attachButton;
    private TextView counter;
    private TextView simButton;
    private TextView signatureView;
    private TextView emptyText;
    private View composeRow;
    private View recordingBar;
    private TextView recordingTime;
    private View pendingScroll;
    private LinearLayout pendingRow;
    private View smartScroll;
    private LinearLayout smartRow;
    private FrameLayout panelSlot;
    private AttachPanel panel;
    private ActionMode actionMode;
    private float revealOffset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long earlyThread = getIntent().getLongExtra(EXTRA_THREAD_ID, -1);
        keep = Keep.is(earlyThread);
        super.onCreate(savedInstanceState);
        // The chat's own color, when it has one, before any view is built.
        if (earlyThread > 0) applyLook(Look.forChat(this, earlyThread, null));
        setContentView(R.layout.activity_thread);
        bubble = getIntent().getBooleanExtra(EXTRA_BUBBLE, false);
        if (!bubble) showUpButton();
        reader = new MessageReader(this);
        if (savedInstanceState != null && savedInstanceState.getString(STATE_CAMERA_FILE) != null) {
            cameraFile = new File(savedInstanceState.getString(STATE_CAMERA_FILE));
        }

        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        sendButton = findViewById(R.id.send);
        attachButton = findViewById(R.id.attach);
        counter = findViewById(R.id.counter);
        simButton = findViewById(R.id.sim);
        signatureView = findViewById(R.id.signature);
        emptyText = findViewById(R.id.empty_text);
        composeRow = findViewById(R.id.compose_row);
        recordingBar = findViewById(R.id.recording_bar);
        recordingTime = findViewById(R.id.recording_time);
        pendingScroll = findViewById(R.id.pending_scroll);
        pendingRow = findViewById(R.id.pending);
        smartScroll = findViewById(R.id.smart_scroll);
        smartRow = findViewById(R.id.smart);
        panelSlot = findViewById(R.id.panel);

        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setMultiChoiceModeListener(new SelectionMode());
        setUpTimeReveal();

        styleCompose();
        sendButton.setOnClickListener(v -> onSendTapped());
        sendButton.setOnLongClickListener(v -> {
            pickScheduleTime();
            return true;
        });
        attachButton.setOnClickListener(v -> togglePanel(false));
        findViewById(R.id.emoji).setOnClickListener(v -> togglePanel(true));
        input.setOnClickListener(v -> hidePanel());
        input.setOnFocusChangeListener((v, focus) -> {
            if (focus) hidePanel();
        });
        simButton.setOnClickListener(v -> pickSim());

        ImageButton recordingSend = findViewById(R.id.recording_send);
        recordingSend.setBackground(Look.circle(look.accent));
        recordingSend.setImageTintList(ColorStateList.valueOf(look.onAccent));
        recordingSend.setOnClickListener(v -> stopRecording(true));
        findViewById(R.id.recording_cancel).setOnClickListener(v -> stopRecording(false));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateComposeState();
                if (s.length() > 0) smartScroll.setVisibility(View.GONE);
            }
        });
        setUpEnterToSend();
        updateComposeState();

        readIntent(getIntent());
    }

    private void applyLook(Look chatLook) {
        look = chatLook;
        getWindow().setStatusBarColor(look.statusBar);
        ActionBar bar = getActionBar();
        if (bar != null) bar.setBackgroundDrawable(new ColorDrawable(look.bar));
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) bar.setBackgroundDrawable(new ColorDrawable(look.bar));
    }

    private void styleCompose() {
        sendButton.setBackground(Look.circle(look.accent));
        sendButton.setImageTintList(ColorStateList.valueOf(look.onAccent));
        input.setTextSize(look.messageTextSize());
        input.setTypeface(look.typeface());
    }

    private void setUpEnterToSend() {
        if (!Prefs.bool(this, Prefs.SEND_ENTER)) return;
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
                | EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(6);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                onSendTapped();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraFile != null) outState.putString(STATE_CAMERA_FILE, cameraFile.getPath());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!observing && !keep) {
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer);
            observing = true;
        }
        updateSignature();
        updateSimButton();
        if (keep || threadId != -1) onConversationVisible();
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
        if (recorder.isRecording()) stopRecording(false);
        stopPlayer();
        saveDraft();
    }

    @Override
    protected void onDestroy() {
        // Texts still waiting out their delay go now rather than being lost.
        for (Delayed d : new ArrayList<>(delayed.values())) {
            handler.removeCallbacks(d.fire);
            d.fire.run();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panelSlot.getVisibility() == View.VISIBLE) {
            hidePanel();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ opening

    private void readIntent(Intent intent) {
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
        jumpToMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);

        if (keep) {
            setTitle(Keep.NAME);
            input.setHint("Type something to keep…");
            recipients = Collections.emptyList();
            readSharedFiles(intent);
            String body = intent.getStringExtra(EXTRA_BODY);
            if (body == null) body = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (body != null) input.setText(body);
            invalidateOptionsMenu();
            return;
        }

        List<String> to = new ArrayList<>();
        String[] many = intent.getStringArrayExtra(EXTRA_ADDRESSES);
        if (many != null) to.addAll(Arrays.asList(many));
        String one = intent.getStringExtra(EXTRA_ADDRESS);
        if (one != null) to.add(one);
        // Links like "smsto:5550100?body=Hi" from other apps.
        Uri data = intent.getData();
        if (data != null && !"content".equals(data.getScheme())) to.addAll(addressesFromUri(data));
        recipients = Collections.unmodifiableList(to);

        String body = intent.getStringExtra(EXTRA_BODY);
        if (body == null) body = intent.getStringExtra("sms_body");
        if (body == null) body = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (body == null && data != null && !"content".equals(data.getScheme())) body = bodyFromUri(data);
        if (body != null) {
            input.setText(body);
            input.setSelection(body.length());
        }
        readSharedFiles(intent);
        String forward = intent.getStringExtra(EXTRA_FORWARD);

        if (threadId == -1 && to.isEmpty()) {
            // Nobody to talk to yet: let the user pick someone.
            Intent pick = new Intent(this, NewMessageActivity.class);
            if (body != null) pick.putExtra(EXTRA_BODY, body);
            startActivity(pick);
            finish();
            return;
        }

        if (!to.isEmpty()) setTitle(TextUtils.join(", ", to));
        boolean hasBody = body != null;
        executor.execute(() -> {
            if (threadId == -1) {
                try {
                    threadId = Telephony.Threads.getOrCreateThreadId(this, new HashSet<>(recipients));
                } catch (RuntimeException ignored) {
                    // No SMS access yet; sending still works by number.
                }
            }
            if (recipients.isEmpty()) recipients = Collections.unmodifiableList(reader.recipientsOf(threadId));
            List<String> people = recipients;
            String custom = ChatPrefs.name(this, threadId);
            String title = custom != null ? custom : reader.names(people);
            String draft = hasBody ? null : AppDb.get(this).draft(threadId);
            Message forwarded = forward == null ? null : loadForward(forward);
            Look chatLook = Look.forChat(this, threadId, people);
            boolean contact = people.size() == 1 && reader.isContact(people.get(0));
            onUi(() -> {
                if (!chatLook.key().equals(look.key())) {
                    applyLook(chatLook);
                    styleCompose();
                    adapter.notifyDataSetChanged();
                }
                knownContact = contact;
                setTitle(title);
                if (getActionBar() != null) {
                    getActionBar().setSubtitle(people.size() > 1 ? people.size() + " people" : null);
                }
                if (draft != null && input.length() == 0) {
                    input.setText(draft);
                    input.setSelection(draft.length());
                }
                if (forwarded != null) applyForward(forwarded);
                invalidateOptionsMenu();
                updateComposeState();
                updateSignature();
                updateSimButton();
                if (resumed) onConversationVisible();
            });
        });
    }

    private boolean knownContact = true;

    private Message loadForward(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return null;
        try {
            return reader.message(key.startsWith("mms"), Long.parseLong(key.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void applyForward(Message m) {
        if (!m.body.isEmpty() && input.length() == 0) {
            input.setText(m.body);
            input.setSelection(m.body.length());
        }
        for (Part p : m.parts) {
            executor.execute(() -> {
                byte[] data = Mms.read(this, p.uri);
                if (data == null) return;
                Attachment a = new Attachment(p.contentType, p.name != null ? p.name : "file", data);
                onUi(() -> {
                    pending.add(a);
                    showPending();
                });
            });
        }
    }

    /** Pictures and files shared from another app ("Share" → Messages). */
    private void readSharedFiles(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(EXTRA_STREAMS);
        if (many != null) uris.addAll(many);
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) uris.addAll(streams);
        } else {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) uris.add(one);
        }
        for (Uri u : uris) addAttachment(u);
    }

    private boolean isGroup() {
        return recipients.size() > 1;
    }

    /** The conversation is known and on screen: load it and clear its notification. */
    private void onConversationVisible() {
        long id = threadId;
        if (keep) {
            load();
            return;
        }
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
        if (!keep && id == -1) {
            emptyText.setText(R.string.no_messages);
            return;
        }
        executor.execute(() -> {
            List<Message> result;
            List<Scheduler.Item> later = Collections.emptyList();
            Set<String> hidden = Deleter.pending(this);
            if (keep) {
                result = Keep.load(this);
            } else {
                result = Reactions.apply(reader.loadThread(id));
                later = Scheduler.forThread(this, id);
            }
            List<Message> visible = new ArrayList<>();
            int lastSub = -1;
            for (Message m : result) {
                if (hidden.contains(keyOf(m))) continue;
                visible.add(m);
                if (m.incoming() && m.subId >= 0) lastSub = m.subId;
            }
            List<String> smart = keep ? Collections.<String>emptyList() : SmartReplies.suggest(this, id, visible);
            for (Scheduler.Item item : later) {
                Message s = new Message(item.id, false, id, "", item.text, item.time, Message.TYPE_SCHEDULED, true);
                for (int i = 0; i < item.files.size(); i++) {
                    String[] f = item.files.get(i);
                    s.parts.add(new Part(i, f[1], f[2], Uri.fromFile(new File(f[0]))));
                }
                visible.add(s);
            }
            List<Scheduler.Item> laterCopy = later;
            int sub = lastSub;
            onUi(() -> {
                messages.clear();
                messages.addAll(visible);
                for (Delayed d : delayed.values()) messages.add(delayedMessage(d));
                scheduled.clear();
                for (Scheduler.Item item : laterCopy) scheduled.put(item.id, item);
                lastIncomingSub = sub;
                updateSimButton();
                adapter.notifyDataSetChanged();
                if (visible.isEmpty()) emptyText.setText(keep ? "Keep notes, links and pictures here. Share things "
                        + "from other apps to Messages and pick Keep." : getString(R.string.no_messages));
                showSmartReplies(smart);
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

    private String keyOf(Message m) {
        return keep ? "note:" + m.id : m.key();
    }

    // ------------------------------------------------------------ composing

    /** Send with text or files; with nothing typed, the button records a voice memo. */
    private void onSendTapped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) {
            if (Prefs.bool(this, Prefs.VOICE_MEMO) && !keep) startRecording();
            return;
        }
        List<Attachment> files = new ArrayList<>(pending);
        if (keep) {
            Context app = getApplicationContext();
            input.setText("");
            clearPending();
            SmsWriter.run(() -> {
                Keep.add(app, text, files);
                onUi(this::load);
            });
            return;
        }
        if (recipients.isEmpty() || !requireDefault()) return;

        String full = withSignature(text);
        List<String> to = recipients;
        int sub = chosenSub();
        input.setText("");
        clearPending();
        AppDb.get(this).setDraft(threadId, null);
        smartScroll.setVisibility(View.GONE);
        Sounds.playSent(this);
        if (Prefs.bool(this, Prefs.CLOSE_KEYBOARD)) hideKeyboard();

        int delaySecs = ChatPrefs.integer(this, threadId, Prefs.SEND_DELAY);
        if (delaySecs > 0) {
            queueDelayed(to, full, files, sub, delaySecs);
            return;
        }
        Context app = getApplicationContext();
        SmsWriter.run(() -> Sender.send(app, to, full, files, sub));
    }

    private String withSignature(String text) {
        String sig = keep ? null : signature();
        if (sig == null || text.isEmpty()) return text;
        return text + "\n" + sig;
    }

    private String signature() {
        long id = ChatPrefs.longValue(this, threadId, Prefs.SIGNATURE);
        return id < 0 ? null : AppDb.get(this).signature(id);
    }

    private void updateSignature() {
        String sig = keep ? null : signature();
        signatureView.setVisibility(sig == null ? View.GONE : View.VISIBLE);
        if (sig != null) signatureView.setText("— " + sig);
        signatureView.setOnClickListener(v -> startActivity(new Intent(this, SignaturesActivity.class)
                .putExtra(EXTRA_THREAD_ID, threadId)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** Mic or send icon, and the SMS counter for plain 1-to-1 texts. */
    private void updateComposeState() {
        CharSequence text = input.getText();
        boolean empty = text.toString().trim().isEmpty() && pending.isEmpty();
        boolean mic = empty && Prefs.bool(this, Prefs.VOICE_MEMO) && !keep;
        sendButton.setImageResource(mic ? R.drawable.ic_mic : R.drawable.ic_send);
        sendButton.setContentDescription(getString(mic ? R.string.record : R.string.send));
        sendButton.setAlpha(empty && !mic ? 0.5f : 1f);

        if (keep || text.length() == 0 || isGroup() || !pending.isEmpty() || !Prefs.bool(this, Prefs.SMS_COUNTER)) {
            counter.setVisibility(View.GONE);
            return;
        }
        int[] length = SmsMessage.calculateLength(withSignature(text.toString()), false);
        int parts = length[0];
        int left = length[2];
        // Textra shows the counter once the text gets long.
        if (parts > 1 || left <= 20 || input.getLineCount() >= 2) {
            counter.setText(parts > 1 ? left + " / " + parts : String.valueOf(left));
            counter.setVisibility(View.VISIBLE);
        } else {
            counter.setVisibility(View.GONE);
        }
    }

    private void saveDraft() {
        if (keep || threadId <= 0) return;
        String text = input.getText().toString();
        long id = threadId;
        Context app = getApplicationContext();
        SmsWriter.run(() -> AppDb.get(app).setDraft(id, text));
    }

    // ------------------------------------------------------------ send delay

    private void queueDelayed(List<String> to, String text, List<Attachment> files, int sub, int secs) {
        Delayed d = new Delayed();
        d.id = System.nanoTime();
        d.due = System.currentTimeMillis() + secs * 1000L;
        d.to = to;
        d.text = text;
        d.files = files;
        d.subId = sub;
        Context app = getApplicationContext();
        d.fire = () -> {
            if (delayed.remove(d.id) == null) return;
            SmsWriter.run(() -> Sender.send(app, d.to, d.text, d.files, d.subId));
            if (!isDestroyed()) load();
        };
        delayed.put(d.id, d);
        handler.postDelayed(d.fire, secs * 1000L);
        messages.add(delayedMessage(d));
        adapter.notifyDataSetChanged();
        handler.removeCallbacks(countdown);
        handler.post(countdown);
    }

    private Message delayedMessage(Delayed d) {
        Message m = new Message(d.id, false, threadId, "", d.text, d.due, Message.TYPE_DELAYED, true);
        for (Attachment a : d.files) m.parts.add(new Part(0, a.contentType, a.name, Uri.EMPTY));
        return m;
    }

    /** Tapping a text in its delay stops it and puts it back in the box. */
    private void cancelDelayed(long id) {
        Delayed d = delayed.remove(id);
        if (d == null) return;
        handler.removeCallbacks(d.fire);
        input.setText(d.text);
        input.setSelection(d.text.length());
        pending.addAll(d.files);
        showPending();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).delayed() && messages.get(i).id == id) messages.remove(i);
        }
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ SIM

    private int chosenSub() {
        if (chosenSub != -2) return chosenSub;
        return Sims.forChat(this, threadId, lastIncomingSub);
    }

    private void updateSimButton() {
        if (keep || !Sims.canRead(this)) {
            simButton.setVisibility(View.GONE);
            return;
        }
        List<Sims.Sim> sims = Sims.active(this);
        if (sims.size() < 2) {
            simButton.setVisibility(View.GONE);
            return;
        }
        Sims.Sim sim = Sims.byId(this, chosenSub());
        simButton.setText(sim == null ? "SIM" : "SIM " + (sim.slot + 1));
        simButton.setTextColor(look.accent);
        simButton.setVisibility(View.VISIBLE);
    }

    private void pickSim() {
        List<Sims.Sim> sims = Sims.active(this);
        if (sims.isEmpty()) return;
        String[] labels = new String[sims.size()];
        int checked = -1;
        for (int i = 0; i < sims.size(); i++) {
            labels[i] = sims.get(i).label();
            if (sims.get(i).subId == chosenSub()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send from")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    chosenSub = sims.get(which).subId;
                    // Remember the choice for this chat.
                    if (threadId > 0) ChatPrefs.put(this, threadId, Prefs.SIM, chosenSub);
                    updateSimButton();
                })
                .show();
    }

    // ------------------------------------------------------------ smart replies

    private void showSmartReplies(List<String> replies) {
        smartRow.removeAllViews();
        if (replies.isEmpty() || input.length() > 0 || keep) {
            smartScroll.setVisibility(View.GONE);
            return;
        }
        int gap = (int) Format.dp(this, 4);
        for (String r : replies) {
            TextView chip = new TextView(this);
            chip.setText(r);
            chip.setTextColor(look.accent);
            chip.setTextSize(14);
            int pad = (int) Format.dp(this, 12);
            chip.setPadding(pad, pad / 2, pad, pad / 2);
            android.graphics.drawable.GradientDrawable bg = Look.rounded(0, Format.dp(this, 16));
            bg.setStroke((int) Format.dp(this, 1), look.accent);
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                input.setText(r);
                onSendTapped();
            });
            chip.setOnLongClickListener(v -> {
                input.setText(r);
                input.setSelection(r.length());
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, 0, gap, 0);
            smartRow.addView(chip, lp);
        }
        smartScroll.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ "+" panel

    private void togglePanel(boolean emoji) {
        if (panelSlot.getVisibility() == View.VISIBLE && !emoji) {
            hidePanel();
            return;
        }
        hideKeyboard();
        if (panel == null) {
            panel = new AttachPanel(this, look, this);
            panelSlot.addView(panel);
        }
        if (emoji) panel.showEmoji(); else panel.open();
        panelSlot.setVisibility(View.VISIBLE);
        attachButton.setRotation(45);
    }

    private void hidePanel() {
        panelSlot.setVisibility(View.GONE);
        attachButton.setRotation(0);
    }

    @Override public EditText input() { return input; }

    @Override
    public int maxBytes() {
        int used = 0;
        for (Attachment a : pending) used += a.data.length;
        return Mms.maxMessageSize(this) - MMS_OVERHEAD - used;
    }

    @Override
    public void attachUris(List<Uri> uris) {
        for (Uri u : uris) addAttachment(u);
        hidePanel();
    }

    @Override
    public void attachData(Attachment attachment) {
        executor.execute(() -> {
            Attachment ready = attachment;
            if (attachment.isImage() && attachment.data.length > maxBytes() && !keep) {
                try {
                    File tmp = new File(MmsFileProvider.dir(this, MmsFileProvider.DIR_CAMERA), "shot-" + System.nanoTime() + ".jpg");
                    try (OutputStream out = new FileOutputStream(tmp)) {
                        out.write(attachment.data);
                    }
                    ready = MediaPrep.prepare(this, Uri.fromFile(tmp), maxBytes());
                    tmp.delete();
                } catch (IOException e) {
                    onUi(() -> Toast.makeText(this, "Couldn't attach that picture", Toast.LENGTH_SHORT).show());
                    return;
                }
            }
            Attachment done = ready;
            onUi(() -> {
                pending.add(done);
                showPending();
            });
        });
    }

    @Override
    public void sendGif(String url, String preview) {
        hidePanel();
        Toast.makeText(this, "Attaching…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                byte[] data = Giphy.get(url, 10 * 1024 * 1024);
                AppDb.get(this).addGifRecent(url, preview);
                Attachment gif = new Attachment("image/gif", "giphy.gif", data);
                onUi(() -> {
                    if (data.length > maxBytes() && !keep) {
                        Toast.makeText(this, "This GIF is too large for your mobile carrier. Try another one.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    pending.add(gif);
                    showPending();
                });
            } catch (IOException e) {
                onUi(() -> Toast.makeText(this, "Failed to connect to Giphy, please check your internet connection",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void openCamera() {
        hidePanel();
        takePhoto();
    }

    @Override
    public void pickFiles() {
        hidePanel();
        String[] items = {"Photo or GIF", "Video", "Audio", "Any file"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: pick("image/*"); break;
                        case 1: pick("video/*"); break;
                        case 2: pick("audio/*"); break;
                        default: pick("*/*"); break;
                    }
                })
                .show();
    }

    @Override
    public void pickContact() {
        hidePanel();
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQUEST_CONTACT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No contacts app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void createEvent() {
        hidePanel();
        EventDialog.show(this, event -> attachData(new Attachment("text/calendar", "event.ics", Ics.build(event))));
    }

    @Override
    public void sendLocation() {
        hidePanel();
        if (!Locations.allowed(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        Toast.makeText(this, "Finding your location…", Toast.LENGTH_SHORT).show();
        Locations.find(this, link -> {
            if (isDestroyed()) return;
            if (link == null) {
                Toast.makeText(this, "Couldn't find your location. Is location turned on?", Toast.LENGTH_LONG).show();
                return;
            }
            Editable e = input.getText();
            if (e.length() > 0 && e.charAt(e.length() - 1) != ' ') e.append(' ');
            e.append(link);
            input.setSelection(input.length());
        });
    }

    @Override
    public void recordVoice() {
        hidePanel();
        startRecording();
    }

    @Override
    public void scheduleLater() {
        hidePanel();
        pickScheduleTime();
    }

    @Override
    public void askPermissions(String[] permissions, int requestCode) {
        requestPermissions(permissions, requestCode);
    }

    // ------------------------------------------------------------ attachments

    private void pick(String type) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType(type)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can pick files", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        cameraFile = new File(MmsFileProvider.dir(this, MmsFileProvider.DIR_CAMERA),
                "photo-" + System.currentTimeMillis() + ".jpg");
        Uri uri = MmsFileProvider.uriFor(this, cameraFile);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("photo", uri));
        try {
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Unable to access camera", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_CAMERA && cameraFile != null) {
            addAttachment(MmsFileProvider.uriFor(this, cameraFile));
        } else if (requestCode == REQUEST_PICK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    addAttachment(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addAttachment(data.getData());
            }
        } else if (requestCode == REQUEST_CONTACT && data != null && data.getData() != null) {
            shareContact(data.getData());
        }
    }

    /** Like Textra: send the contact as a card (MMS) or as plain text (SMS). */
    private void shareContact(Uri contact) {
        new AlertDialog.Builder(this)
                .setTitle("Share contact")
                .setItems(new String[]{"Send as vCard", "Send as text"}, (d, which) -> executor.execute(() -> {
                    if (which == 0) {
                        byte[] card = VCard.fromContact(this, contact);
                        if (card == null) return;
                        VCard.Card parsed = VCard.parse(card);
                        String name = parsed.name != null ? parsed.name : "contact";
                        Attachment a = new Attachment("text/x-vcard", name.replaceAll("[^A-Za-z0-9]", "_") + ".vcf", card);
                        onUi(() -> {
                            pending.add(a);
                            showPending();
                        });
                    } else {
                        String text = VCard.asText(this, contact);
                        onUi(() -> {
                            Editable e = input.getText();
                            if (e.length() > 0) e.append('\n');
                            e.append(text);
                        });
                    }
                }))
                .show();
    }

    /** Shrinks the file to fit what's left of the MMS size limit, then shows it above the box. */
    private void addAttachment(Uri uri) {
        executor.execute(() -> {
            int room = keep ? 50 * 1024 * 1024 : maxBytes();
            String error = null;
            Attachment attachment = null;
            if (room < 20 * 1024) {
                error = "No room left in this message. Send it, then attach more.";
            } else {
                try {
                    attachment = MediaPrep.prepare(this, uri, room);
                } catch (MediaPrep.TooLargeException e) {
                    error = e.getMessage();
                } catch (IOException | RuntimeException e) {
                    error = "Couldn't attach that file";
                }
            }
            Attachment ready = attachment;
            String problem = error;
            onUi(() -> {
                if (ready == null) {
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                    return;
                }
                pending.add(ready);
                showPending();
            });
        });
    }

    private void showPending() {
        pendingRow.removeAllViews();
        int size = (int) Format.dp(this, 72);
        int margin = (int) Format.dp(this, 4);
        for (Attachment a : new ArrayList<>(pending)) {
            FrameLayout box = new FrameLayout(this);
            View preview;
            if (a.isImage()) {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(thumbnail(a.data, size));
                preview = image;
            } else {
                TextView label = new TextView(this);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(look.bubbleInText);
                label.setText(a.isAudio() ? "🎤\nVoice" : a.isVideo() ? "🎞\nVideo" : a.isContact() ? "👤\nContact"
                        : a.isCalendar() ? "📅\nEvent" : "📎\nFile");
                preview = label;
            }
            preview.setBackground(Look.rounded(look.bubbleIn, Format.dp(this, 12)));
            preview.setClipToOutline(true);
            box.addView(preview, new FrameLayout.LayoutParams(size, size));

            ImageButton remove = new ImageButton(this);
            remove.setImageResource(R.drawable.ic_close);
            remove.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            remove.setBackground(Look.circle(0xAA000000));
            remove.setContentDescription("Remove");
            remove.setOnClickListener(v -> {
                pending.remove(a);
                showPending();
            });
            int r = (int) Format.dp(this, 24);
            box.addView(remove, new FrameLayout.LayoutParams(r, r, Gravity.TOP | Gravity.END));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            pendingRow.addView(box, lp);
        }
        pendingScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        updateComposeState();
    }

    private void clearPending() {
        pending.clear();
        showPending();
    }

    private static Bitmap thumbnail(byte[] data, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    // ------------------------------------------------------------ voice memos

    private void startRecording() {
        if (recipients.isEmpty() || !requireDefault()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }
        stopPlayer();
        try {
            recorder.start(this);
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't start the microphone", Toast.LENGTH_SHORT).show();
            return;
        }
        composeRow.setVisibility(View.GONE);
        recordingBar.setVisibility(View.VISIBLE);
        handler.post(recordingTick);
    }

    private void stopRecording(boolean send) {
        handler.removeCallbacks(recordingTick);
        composeRow.setVisibility(View.VISIBLE);
        recordingBar.setVisibility(View.GONE);
        if (!send) {
            recorder.cancel();
            return;
        }
        File file = recorder.stop();
        if (file == null) {
            Toast.makeText(this, "Hold on a bit longer to record", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> to = recipients;
        int sub = chosenSub();
        Context app = getApplicationContext();
        Sounds.playSent(this);
        SmsWriter.run(() -> {
            byte[] data = Mms.readFile(file);
            file.delete();
            if (data == null) return;
            Attachment memo = new Attachment(VoiceRecorder.CONTENT_TYPE, "voice.amr", data);
            Sender.send(app, to, "", Collections.singletonList(memo), sub);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_AUDIO_PERMISSION && granted) startRecording();
        if (requestCode == REQUEST_LOCATION && granted) sendLocation();
        if (requestCode == AttachPanel.REQUEST_MEDIA || requestCode == AttachPanel.REQUEST_CAMERA_TILE) {
            if (panel != null) panel.onPermissionsChanged();
            if (panelSlot.getVisibility() != View.VISIBLE) togglePanel(false);
        }
    }

    private void togglePlay(Part part) {
        if (player != null && playingPartId == part.id) {
            if (player.isPlaying()) player.pause(); else player.start();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(this, part.uri);
            player.setOnCompletionListener(mp -> stopPlayer());
            player.prepare();
            player.start();
            playingPartId = part.id;
            handler.post(playbackTick);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "Can't play this recording", Toast.LENGTH_SHORT).show();
            stopPlayer();
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPlayer() {
        handler.removeCallbacks(playbackTick);
        if (player != null) {
            player.release();
            player = null;
            playingPartId = -1;
            adapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------ scheduling

    private void pickScheduleTime() {
        if (keep) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) {
            Toast.makeText(this, "Type a message, then hold Send to schedule it", Toast.LENGTH_LONG).show();
            return;
        }
        if (recipients.isEmpty() || threadId == -1 || !requireDefault()) return;
        SchedulePicker.show(this, time -> schedule(withSignature(text), time));
    }

    private void schedule(String text, long time) {
        if (time < System.currentTimeMillis() + 30_000) {
            Toast.makeText(this, "That time has already passed", Toast.LENGTH_SHORT).show();
            return;
        }
        Scheduler.add(this, time, threadId, recipients, text, new ArrayList<>(pending));
        input.setText("");
        clearPending();
        AppDb.get(this).setDraft(threadId, null);
        load();
        Toast.makeText(this, "Sends " + Format.messageTime(this, time), Toast.LENGTH_SHORT).show();
        if (!Scheduler.canBeExact(this) && Build.VERSION.SDK_INT >= 31) {
            new AlertDialog.Builder(this)
                    .setTitle("Send on time?")
                    .setMessage("To schedule messages, please enable the 'Alarms & reminders' permission "
                            + "for Messages. Otherwise Android may send them a few minutes late.")
                    .setPositiveButton("Go to Settings", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.fromParts("package", getPackageName(), null))))
                    .setNegativeButton("Not now", null)
                    .show();
        }
    }

    private void showScheduledActions(Message m) {
        Scheduler.Item item = scheduled.get(m.id);
        if (item == null) return;
        Context app = getApplicationContext();
        new AlertDialog.Builder(this)
                .setTitle("Sends " + Format.messageTime(this, item.time))
                .setItems(new String[]{"Send now", "Change time", "Edit", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        if (!requireDefault()) return;
                        SmsWriter.run(() -> Scheduler.fire(app, item.id));
                    } else if (which == 1) {
                        SchedulePicker.show(this, time -> {
                            List<Attachment> files = item.attachments();
                            Scheduler.cancel(this, item.id);
                            Scheduler.add(this, time, item.threadId, item.recipients, item.text, files);
                            load();
                        });
                        return;
                    } else if (which == 2) {
                        List<Attachment> files = item.attachments();
                        Scheduler.cancel(this, item.id);
                        input.setText(item.text);
                        input.setSelection(item.text.length());
                        pending.addAll(files);
                        showPending();
                    } else {
                        Scheduler.cancel(this, item.id);
                    }
                    load();
                })
                .show();
    }

    // ------------------------------------------------------------ menus

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (keep) {
            menu.add(0, MENU_EMAIL, 0, "Share as email");
            return true;
        }
        if (recipients.isEmpty()) return true;
        if (!isGroup()) {
            menu.add(0, MENU_CALL, 0, R.string.call)
                    .setIcon(R.drawable.ic_call)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (threadId > 0) {
            menu.add(0, MENU_CUSTOMIZE, 1, R.string.customize)
                    .setIcon(R.drawable.ic_settings)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, MENU_MEDIA, 2, "Media");
            menu.add(0, MENU_PIN, 3, Prefs.isPinned(this, threadId) ? R.string.unpin : R.string.pin);
            menu.add(0, MENU_MUTE, 4, ChatPrefs.muted(this, threadId) ? R.string.unmute : R.string.mute);
            menu.add(0, MENU_UNREAD, 5, R.string.mark_unread);
            menu.add(0, MENU_HOME, 6, "Add to home screen");
        }
        if (!isGroup() && !knownContact) menu.add(0, MENU_ADD_CONTACT, 7, "Add contact");
        menu.add(0, MENU_BLOCK, 8, Prefs.isBlocked(this, recipients) ? "Un-blocklist" : "Blocklist");
        menu.add(0, MENU_EMAIL, 9, "Share as email");
        if (threadId > 0) menu.add(0, MENU_DELETE, 10, R.string.delete);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CALL:
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipients.get(0), null)));
                return true;
            case MENU_CUSTOMIZE:
                startActivity(new Intent(this, ChatSettingsActivity.class).putExtra(EXTRA_THREAD_ID, threadId));
                return true;
            case MENU_MEDIA:
                startActivity(new Intent(this, MediaActivity.class).putExtra(EXTRA_THREAD_ID, threadId));
                return true;
            case MENU_PIN: {
                boolean pin = !Prefs.isPinned(this, threadId);
                Prefs.setPinned(this, threadId, pin);
                Toast.makeText(this, pin ? "Pinned to top of chat list." : "Un-pinned.", Toast.LENGTH_SHORT).show();
                invalidateOptionsMenu();
                return true;
            }
            case MENU_MUTE: {
                boolean mute = !ChatPrefs.muted(this, threadId);
                ChatPrefs.put(this, threadId, Prefs.NOTIFY, mute ? Boolean.FALSE : null);
                Toast.makeText(this, mute ? "Muted." : "Un-muted.", Toast.LENGTH_SHORT).show();
                invalidateOptionsMenu();
                return true;
            }
            case MENU_UNREAD: {
                if (!requireDefault()) return true;
                long id = threadId;
                Context app = getApplicationContext();
                SmsWriter.run(() -> SmsWriter.markUnread(app, id));
                finish();
                return true;
            }
            case MENU_HOME:
                pinToHome();
                return true;
            case MENU_ADD_CONTACT:
                try {
                    startActivity(VCard.addNumberIntent(recipients.get(0)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "To add a contact, you need to install a suitable app", Toast.LENGTH_SHORT).show();
                }
                return true;
            case MENU_BLOCK:
                toggleBlock();
                return true;
            case MENU_EMAIL:
                shareChatAsEmail();
                return true;
            case MENU_DELETE:
                confirmDeleteThread();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void pinToHome() {
        long id = threadId;
        List<String> people = recipients;
        String name = getTitle().toString();
        executor.execute(() -> {
            String key = people.size() == 1 ? people.get(0) : name;
            Bitmap avatar = Avatars.get(this, key, name, (int) Format.dp(this, 96), Look.avatarColor(key));
            boolean ok = Shortcuts.pin(this, id, name, people, avatar);
            if (!ok) onUi(() -> Toast.makeText(this, "Your home screen doesn't support this", Toast.LENGTH_SHORT).show());
        });
    }

    private void toggleBlock() {
        boolean block = !Prefs.isBlocked(this, recipients);
        if (block) {
            new AlertDialog.Builder(this)
                    .setTitle("Blocklist this chat?")
                    .setMessage("A blocklisted chat is not shown in the chat list, and you are not notified "
                            + "when receiving messages. Nothing gets deleted.")
                    .setPositiveButton("Blocklist", (d, w) -> {
                        for (String r : recipients) Prefs.setBlocked(this, r, true);
                        Notifier.cancel(this, threadId);
                        Toast.makeText(this, "Blocklisted. See Settings > Blocklist to un-blocklist.", Toast.LENGTH_LONG).show();
                        finish();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            for (String r : recipients) Prefs.setBlocked(this, r, false);
            Toast.makeText(this, "Un-blocklisted.", Toast.LENGTH_SHORT).show();
            invalidateOptionsMenu();
        }
    }

    private void confirmDeleteThread() {
        if (!requireDefault()) return;
        long id = threadId;
        new AlertDialog.Builder(this)
                .setTitle("Delete conversation?")
                .setMessage("All messages in this conversation will be deleted. Locked messages are kept.")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    List<String> keys = Collections.singletonList("thread:" + id);
                    Deleter.schedule(this, keys);
                    MainActivity.offerUndo(keys, "Chat deleted");
                    Notifier.cancel(this, id);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Share as email": the whole chat as text, pictures optional (Textra asks first). */
    private void shareChatAsEmail() {
        List<Message> copy = new ArrayList<>(messages);
        boolean hasMedia = false;
        for (Message m : copy) if (!m.parts.isEmpty()) hasMedia = true;
        if (!hasMedia) {
            emailChat(copy, false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Include media in email?")
                .setMessage("Including media files such as photos and videos may not work because the email "
                        + "may become too large.")
                .setPositiveButton("Include", (d, w) -> emailChat(copy, true))
                .setNegativeButton("Text only", (d, w) -> emailChat(copy, false))
                .show();
    }

    private void emailChat(List<Message> copy, boolean media) {
        String title = getTitle().toString();
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            ArrayList<Uri> files = new ArrayList<>();
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT);
            for (Message m : copy) {
                if (m.notSentYet()) continue;
                String who = m.incoming() ? (m.senderName != null ? m.senderName : title) : "Me";
                sb.append(df.format(new java.util.Date(m.date))).append(" — ").append(who).append(":\n");
                if (!m.body.isEmpty()) sb.append(m.body).append('\n');
                for (Part p : m.parts) {
                    sb.append("[").append(p.label()).append("]\n");
                    if (media) {
                        Uri u = copyToShare(p);
                        if (u != null) files.add(u);
                    }
                }
                sb.append('\n');
            }
            onUi(() -> {
                Intent send = new Intent(files.isEmpty() ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                        .setType(files.isEmpty() ? "text/plain" : "*/*")
                        .putExtra(Intent.EXTRA_SUBJECT, keep ? "Keep" : "Chat with " + title)
                        .putExtra(Intent.EXTRA_TEXT, sb.toString());
                if (!files.isEmpty()) {
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                try {
                    startActivity(Intent.createChooser(send, "Share as email"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "To send an email, you need to install an email app", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ------------------------------------------------------------ message actions

    /** Tap on a bubble: Textra's quick options. */
    private void showMessageActions(Message m) {
        if (m.scheduled()) {
            showScheduledActions(m);
            return;
        }
        if (m.delayed()) {
            cancelDelayed(m.id);
            return;
        }
        // Detected things (addresses, dates, numbers) need the text classifier: off the main thread.
        executor.execute(() -> {
            List<Object[]> smart = detectActions(m.body);
            onUi(() -> showMessageMenu(m, smart));
        });
    }

    private void showMessageMenu(Message m, List<Object[]> smart) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Context app = getApplicationContext();

        if (m.failed() && !keep) {
            labels.add("Retry");
            actions.add(() -> {
                if (requireDefault()) SmsWriter.run(() -> Sender.resend(app, m));
            });
        }
        String code = Otp.find(m.body);
        if (code != null) {
            labels.add("Copy code " + code);
            actions.add(() -> copy(code));
        }
        if (!m.body.isEmpty()) {
            labels.add("Copy");
            actions.add(() -> copy(m.body));
        }
        if (!m.body.isEmpty() && !m.failed() && !m.sending() && !keep) {
            labels.add("React");
            actions.add(() -> showReactionPicker(m));
        }
        for (Object[] s : smart) {
            labels.add((String) s[0]);
            actions.add((Runnable) s[1]);
        }
        labels.add(getString(R.string.forward));
        actions.add(() -> forward(m));
        labels.add("Share");
        actions.add(() -> share(m));
        boolean media = false;
        for (Part p : m.parts) if (p.isImage() || p.isVideo()) media = true;
        if (media) {
            labels.add("Save to gallery");
            actions.add(() -> executor.execute(() -> {
                int n = Gallery.saveAll(this, m);
                onUi(() -> Toast.makeText(this, n > 0 ? "Saved to gallery" : "Can't save this kind of file to the gallery.",
                        Toast.LENGTH_SHORT).show());
            }));
        }
        if (!m.body.isEmpty()) {
            labels.add("Select text");
            actions.add(() -> showSelectableText(m.body));
        }
        if (!keep && !m.failed()) {
            labels.add("Send as new");
            actions.add(() -> {
                if (requireDefault()) SmsWriter.run(() -> Sender.sendAgain(app, m, recipients));
            });
        }
        if (!keep) {
            labels.add(m.locked ? getString(R.string.unlock) : getString(R.string.lock));
            actions.add(() -> {
                if (!requireDefault()) return;
                SmsWriter.run(() -> SmsWriter.setLocked(app, m, !m.locked));
                Toast.makeText(this, m.locked ? "Unlocked." : "Locked.", Toast.LENGTH_SHORT).show();
            });
            labels.add("Show info");
            actions.add(() -> showDetails(m));
        }
        if (m.incoming() && !keep) {
            labels.add("Report as spam");
            actions.add(() -> reportSpam(m));
        }
        labels.add("Multi-select");
        actions.add(() -> startSelection(m));
        labels.add(getString(R.string.delete));
        actions.add(() -> deleteMessages(Collections.singletonList(m)));

        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Things found in the text, each with an action: {label, Runnable}. Call off the main thread. */
    private List<Object[]> detectActions(String text) {
        List<Object[]> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        Set<String> seen = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                TextClassificationManager tcm = getSystemService(TextClassificationManager.class);
                TextClassifier tc = tcm == null ? null : tcm.getTextClassifier();
                if (tc != null) {
                    TextLinks links = tc.generateLinks(new TextLinks.Request.Builder(text).build());
                    for (TextLinks.TextLink link : links.getLinks()) {
                        if (result.size() >= 4) break;
                        String span = text.substring(link.getStart(), link.getEnd());
                        TextClassification tcls = tc.classifyText(new TextClassification.Request.Builder(
                                text, link.getStart(), link.getEnd()).build());
                        for (RemoteAction ra : tcls.getActions()) {
                            String label = ra.getTitle() + ": " + shorten(span);
                            if (!seen.add(label)) continue;
                            PendingIntent pi = ra.getActionIntent();
                            result.add(new Object[]{label, (Runnable) () -> {
                                try {
                                    pi.send();
                                } catch (PendingIntent.CanceledException ignored) {
                                    // Gone.
                                }
                            }});
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall back to the plain patterns below.
            }
        }
        if (!result.isEmpty()) return result;
        Matcher phone = Patterns.PHONE.matcher(text);
        while (phone.find() && result.size() < 3) {
            String n = phone.group();
            if (n.replaceAll("\\D", "").length() < 6 || !seen.add(n)) continue;
            result.add(new Object[]{"Call " + n, (Runnable) () ->
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)))});
            result.add(new Object[]{"Message " + n, (Runnable) () ->
                    startActivity(new Intent(this, ThreadActivity.class).putExtra(EXTRA_ADDRESS, n))});
        }
        Matcher email = Patterns.EMAIL_ADDRESS.matcher(text);
        while (email.find() && result.size() < 5) {
            String e = email.group();
            if (!seen.add(e)) continue;
            result.add(new Object[]{"Email " + e, (Runnable) () -> {
                try {
                    startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + e)));
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(this, "To send an email, you need to install an email app", Toast.LENGTH_SHORT).show();
                }
            }});
        }
        return result;
    }

    private static String shorten(String s) {
        return s.length() > 28 ? s.substring(0, 27) + "…" : s;
    }

    private void copy(String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void forward(Message m) {
        Intent intent = new Intent(this, NewMessageActivity.class).putExtra(EXTRA_BODY, m.body);
        if (keep) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (Part p : m.parts) uris.add(p.uri);
            if (!uris.isEmpty()) intent.putParcelableArrayListExtra(EXTRA_STREAMS, uris);
        } else {
            intent.putExtra(EXTRA_FORWARD, m.key()).removeExtra(EXTRA_BODY);
        }
        startActivity(intent);
    }

    private void forwardMany(List<Message> picked) {
        StringBuilder text = new StringBuilder();
        ArrayList<Uri> uris = new ArrayList<>();
        for (Message m : picked) {
            if (!m.body.isEmpty()) {
                if (text.length() > 0) text.append("\n\n");
                text.append(m.body);
            }
            for (Part p : m.parts) if (!Uri.EMPTY.equals(p.uri)) uris.add(p.uri);
        }
        Intent intent = new Intent(this, NewMessageActivity.class).putExtra(EXTRA_BODY, text.toString());
        if (!uris.isEmpty()) intent.putParcelableArrayListExtra(EXTRA_STREAMS, uris);
        startActivity(intent);
    }

    private void showDetails(Message m) {
        List<String> people = recipients;
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            boolean bulk = people.size() > 1 && !ChatPrefs.bool(this, threadId, Prefs.GROUP_MMS);
            sb.append("Type: ").append(m.mms ? "MMS" : "SMS")
                    .append(people.size() > 1 ? (bulk ? " (Bulk)" : " (Chat)") : "").append('\n');
            if (m.incoming()) {
                String name = reader.lookupName(m.address);
                sb.append("From: ").append(name);
                if (!name.equals(m.address)) sb.append(" (").append(m.address).append(')');
                sb.append('\n');
                if (m.dateSent > 0) sb.append("Sent: ").append(fullTime(m.dateSent)).append('\n');
                sb.append("Received: ").append(fullTime(m.date)).append('\n');
            } else {
                sb.append("To: ").append(TextUtils.join(", ", people)).append('\n');
                sb.append(m.failed() ? "Failed: " : m.sending() ? "Sending since: " : "Sent: ")
                        .append(fullTime(m.date)).append('\n');
                if (m.status == Message.STATUS_DELIVERED) sb.append("Delivered: yes\n");
                else if (m.status == Message.STATUS_PENDING) sb.append("Delivered: waiting for report\n");
                else if (m.status == Message.STATUS_FAILED) sb.append("Delivered: no\n");
            }
            if (m.subId >= 0) {
                String sim = Sims.labelFor(this, m.subId);
                sb.append("SIM: ").append(sim != null ? sim : "SIM missing").append('\n');
            }
            if (m.mms && m.size > 0) {
                sb.append("Size: ").append(android.text.format.Formatter.formatShortFileSize(this, m.size)).append('\n');
            }
            if (!m.mms && !m.body.isEmpty()) {
                sb.append("Parts: ").append(SmsMessage.calculateLength(m.body, false)[0]).append('\n');
            }
            if (m.locked) sb.append("Locked: yes\n");
            for (Part p : m.parts) sb.append("Attachment: ").append(p.label()).append(" (").append(p.contentType).append(")\n");
            onUi(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.details)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        });
    }

    private String fullTime(long millis) {
        return DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
    }

    /** Forwards the text to 7726 ("SPAM"), the number carriers use for spam reports. */
    private void reportSpam(Message m) {
        new AlertDialog.Builder(this)
                .setTitle("Report as spam?")
                .setMessage("This forwards the message to 7726 (SPAM), the number most carriers use for spam "
                        + "reports. Your carrier may reply asking for the sender's number. Standard rates may apply.")
                .setPositiveButton("Report", (d, w) -> {
                    if (!requireDefault()) return;
                    Context app = getApplicationContext();
                    String body = m.body.isEmpty() ? m.preview() : m.body;
                    SmsWriter.run(() -> SmsWriter.send(app, "7726", body));
                    Toast.makeText(this, "Report sent to 7726", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteMessages(List<Message> picked) {
        if (!keep && !requireDefault()) return;
        List<String> keys = new ArrayList<>();
        int skipped = 0;
        for (Message m : picked) {
            if (m.scheduled()) {
                Scheduler.cancel(this, m.id);
            } else if (m.delayed()) {
                Delayed d = delayed.remove(m.id);
                if (d != null) handler.removeCallbacks(d.fire);
            } else if (m.locked) {
                skipped++;
            } else {
                keys.add(keyOf(m));
            }
        }
        if (skipped > 0) Toast.makeText(this, "To delete locked messages, unlock them first.", Toast.LENGTH_LONG).show();
        if (keys.isEmpty()) {
            load();
            return;
        }
        Deleter.schedule(this, keys);
        load();
        String label = keys.size() == 1 ? "Message deleted" : keys.size() + " messages deleted";
        UndoBar.show(this, look, label, () -> {
            Deleter.undo(this, keys);
            load();
        });
    }

    /** A row of tapback emoji. Tapping the one already chosen takes it back. */
    private void showReactionPicker(Message m) {
        if (!requireDefault()) return;
        String current = Reactions.mine(m);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        int pad = (int) Format.dp(this, 12);
        row.setPadding(pad, pad * 2, pad, pad);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(row).create();
        for (String[] kind : Reactions.KINDS) {
            String emoji = kind[0];
            TextView option = new TextView(this);
            option.setText(emoji);
            option.setTextSize(28);
            option.setGravity(Gravity.CENTER);
            option.setContentDescription(kind[1]);
            if (emoji.equals(current)) option.setBackground(Look.circle((look.accent & 0x00FFFFFF) | 0x55000000));
            option.setOnClickListener(v -> {
                dialog.dismiss();
                String text = Reactions.compose(emoji, m.body, emoji.equals(current));
                List<String> to = recipients;
                Context app = getApplicationContext();
                SmsWriter.run(() -> Sender.send(app, to, text));
            });
            int size = (int) Format.dp(this, 48);
            row.addView(option, new LinearLayout.LayoutParams(size, size));
        }
        dialog.show();
    }

    /** Who reacted with what, newest first. */
    private void showReactionDetails(Message m) {
        List<String> lines = new ArrayList<>();
        for (int i = m.reactions.size() - 1; i >= 0; i--) {
            Reactions.Entry e = m.reactions.get(i);
            lines.add(e.emoji + "  " + (e.mine ? "You" : reader.lookupName(e.from)));
        }
        new AlertDialog.Builder(this)
                .setTitle("Reactions")
                .setMessage(TextUtils.join("\n", lines) + "\n\nThe newest reaction is at the top.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void share(Message m) {
        if (m.parts.isEmpty()) {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.body), null));
            return;
        }
        Part part = m.parts.get(0);
        executor.execute(() -> {
            Uri uri = copyToShare(part);
            onUi(() -> {
                if (uri == null) return;
                Intent send = new Intent(Intent.ACTION_SEND).setType(part.contentType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!m.body.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, m.body);
                send.setClipData(ClipData.newRawUri("file", uri));
                startActivity(Intent.createChooser(send, null));
            });
        });
    }

    /** Other apps can't read the MMS provider, so hand them a copy through our file provider. */
    private Uri copyToShare(Part part) {
        if (keep || Uri.EMPTY.equals(part.uri)) return Uri.EMPTY.equals(part.uri) ? null : part.uri;
        byte[] data = Mms.read(this, part.uri);
        if (data == null) return null;
        String name = part.name != null ? part.name.replaceAll("[^A-Za-z0-9._-]", "_") : "file";
        if (!name.contains(".")) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(part.contentType);
            if (ext != null) name = name + "." + ext;
        }
        File file = new File(MmsFileProvider.dir(this, MmsFileProvider.DIR_SHARE), part.id + "_" + name);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        } catch (IOException e) {
            return null;
        }
        return MmsFileProvider.uriFor(this, file);
    }

    private void openExternally(Part part) {
        if (part.isVideo() && Prefs.bool(this, Prefs.VIDEO_WARNING)) {
            new AlertDialog.Builder(this)
                    .setTitle("Open video?")
                    .setMessage("Video messages may contain a harmful exploit that can leave your phone "
                            + "vulnerable to attacks. Would you like to continue?")
                    .setPositiveButton("Open", (d, w) -> openNow(part))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        openNow(part);
    }

    private void openNow(Part part) {
        executor.execute(() -> {
            Uri uri = copyToShare(part);
            onUi(() -> {
                if (uri == null) return;
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, part.contentType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(view);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "To view this, you need to install a suitable app", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** Contact cards: show the person, offer to save them. Events: offer to add to the calendar. */
    private void openCard(Part part) {
        executor.execute(() -> {
            byte[] data = Mms.read(this, part.uri);
            if (data == null) return;
            onUi(() -> {
                if (part.isContact()) {
                    VCard.Card card = VCard.parse(data);
                    new AlertDialog.Builder(this)
                            .setTitle(card.name != null ? card.name : "Contact card")
                            .setMessage(card.summary())
                            .setPositiveButton("Add contact", (d, w) -> {
                                try {
                                    startActivity(VCard.addContactIntent(card));
                                } catch (ActivityNotFoundException e) {
                                    Toast.makeText(this, "To add a contact, you need to install a suitable app",
                                            Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNeutralButton("Message", (d, w) -> {
                                if (!card.phones.isEmpty()) {
                                    startActivity(new Intent(this, ThreadActivity.class).putExtra(EXTRA_ADDRESS, card.phones.get(0)));
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    Ics.Event e = Ics.parse(data);
                    String when = e.start == 0 ? "" : DateUtils.formatDateRange(this, e.start, e.end,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                                    | (e.allDay ? 0 : DateUtils.FORMAT_SHOW_TIME));
                    new AlertDialog.Builder(this)
                            .setTitle(e.title != null ? e.title : "Calendar event")
                            .setMessage(when + (e.location != null ? "\n" + e.location : ""))
                            .setPositiveButton("Add to calendar", (d, w) -> {
                                try {
                                    startActivity(Ics.insertIntent(e));
                                } catch (ActivityNotFoundException ex) {
                                    Toast.makeText(this, "To add to calendar, you need to install a suitable app",
                                            Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
        });
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

    // ------------------------------------------------------------ multi-select

    private void startSelection(Message m) {
        int index = messages.indexOf(m);
        if (index >= 0) list.setItemChecked(index, true);
    }

    private List<Message> checkedMessages() {
        List<Message> picked = new ArrayList<>();
        android.util.SparseBooleanArray checked = list.getCheckedItemPositions();
        for (int i = 0; i < checked.size(); i++) {
            if (checked.valueAt(i) && checked.keyAt(i) < messages.size()) picked.add(messages.get(checked.keyAt(i)));
        }
        return picked;
    }

    private class SelectionMode implements AbsListView.MultiChoiceModeListener {
        private static final int COPY = 1, FORWARD = 2, SHARE = 3, LOCK = 4, UNLOCK = 5, ALL = 6, DELETE = 7, INFO = 8;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            actionMode = mode;
            menu.add(0, DELETE, 0, R.string.delete).setIcon(R.drawable.ic_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, COPY, 1, "Copy").setIcon(R.drawable.ic_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, FORWARD, 2, R.string.forward).setIcon(R.drawable.ic_forward).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, ALL, 3, "Select all").setIcon(R.drawable.ic_select_all);
            menu.add(0, SHARE, 4, "Share");
            if (!keep) {
                menu.add(0, LOCK, 5, R.string.lock);
                menu.add(0, UNLOCK, 6, R.string.unlock);
                menu.add(0, INFO, 7, "Show info");
            }
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            MenuItem info = menu.findItem(INFO);
            if (info != null) info.setVisible(list.getCheckedItemCount() == 1);
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
            List<Message> picked = checkedMessages();
            Collections.sort(picked, (a, b) -> Long.compare(a.date, b.date));
            Context app = getApplicationContext();
            switch (item.getItemId()) {
                case COPY: {
                    StringBuilder sb = new StringBuilder();
                    for (Message m : picked) {
                        if (m.body.isEmpty()) continue;
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(m.body);
                    }
                    copy(sb.toString());
                    break;
                }
                case FORWARD:
                    forwardMany(picked);
                    break;
                case SHARE: {
                    StringBuilder sb = new StringBuilder();
                    for (Message m : picked) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(m.preview());
                    }
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, sb.toString()), null));
                    break;
                }
                case LOCK:
                case UNLOCK: {
                    if (!requireDefault()) return true;
                    boolean lock = item.getItemId() == LOCK;
                    SmsWriter.run(() -> {
                        for (Message m : picked) if (!m.notSentYet()) SmsWriter.setLocked(app, m, lock);
                    });
                    Toast.makeText(ThreadActivity.this, lock ? "Locked." : "Unlocked.", Toast.LENGTH_SHORT).show();
                    break;
                }
                case ALL:
                    for (int i = 0; i < messages.size(); i++) list.setItemChecked(i, true);
                    return true;
                case INFO:
                    if (!picked.isEmpty()) showDetails(picked.get(0));
                    break;
                case DELETE:
                    deleteMessages(picked);
                    break;
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

    // ------------------------------------------------------------ drag for times

    /** Dragging the chat to the left reveals every bubble's time on the right (Textra's tip). */
    private void setUpTimeReveal() {
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float max = Format.dp(this, 72);
        list.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        dragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX;
                        if (!dragging && dx < -slop && Math.abs(dx) > Math.abs(e.getY() - downY) * 1.5f) {
                            dragging = true;
                            // Stop the list from treating this as a tap or a long-press.
                            MotionEvent cancel = MotionEvent.obtain(e);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            list.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (dragging) {
                            revealOffset = Math.max(-max, Math.min(0, dx));
                            applyReveal();
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            dragging = false;
                            revealOffset = 0;
                            applyReveal();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    private void applyReveal() {
        float max = Format.dp(this, 72);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            View row = child.findViewById(R.id.row);
            View side = child.findViewById(R.id.side_time);
            if (row == null || side == null) continue;
            if (revealOffset == 0) {
                row.animate().translationX(0).setDuration(150).start();
                side.animate().alpha(0).setDuration(150).start();
            } else {
                row.setTranslationX(revealOffset);
                side.setAlpha(-revealOffset / max);
            }
        }
    }

    // ------------------------------------------------------------ list

    private class MessageAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Message getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_message, parent, false);
            Message m = getItem(position);
            Message prev = position > 0 ? messages.get(position - 1) : null;
            Message next = position + 1 < messages.size() ? messages.get(position + 1) : null;
            boolean endOfRun = next == null || next.incoming() != m.incoming()
                    || next.date - m.date > TIME_GAP_MS || next.notSentYet() != m.notSentYet();

            View row = view.findViewById(R.id.row);
            row.setTranslationX(revealOffset);
            boolean checked = list.isItemChecked(position);
            view.setBackgroundColor(checked ? (look.accent & 0x00FFFFFF) | 0x33000000 : 0);

            TextView side = view.findViewById(R.id.side_time);
            side.setText(DateUtils.formatDateTime(parent.getContext(), m.date, DateUtils.FORMAT_SHOW_TIME));
            side.setAlpha(revealOffset == 0 ? 0 : -revealOffset / Format.dp(parent.getContext(), 72));

            // A day header when the date changes.
            TextView day = view.findViewById(R.id.day);
            if (prev == null || !sameDay(prev.date, m.date)) {
                day.setText(dayLabel(m.date));
                day.setVisibility(View.VISIBLE);
            } else {
                day.setVisibility(View.GONE);
            }

            bindBubble(view, look, m.incoming(), m.body, endOfRun);
            TextView body = view.findViewById(R.id.body);
            body.setAlpha(m.notSentYet() ? 0.6f : 1f);

            // In groups, name the sender above the first bubble of each run.
            TextView sender = view.findViewById(R.id.sender);
            boolean newSender = prev == null || !prev.incoming() || !prev.address.equals(m.address);
            if (m.incoming() && m.senderName != null && newSender) {
                sender.setText(m.senderName);
                sender.setTextColor(Look.avatarColor(m.address));
                sender.setVisibility(View.VISIBLE);
            } else {
                sender.setVisibility(View.GONE);
            }

            bindAttachments(view.findViewById(R.id.attachments), m, position);
            bindLinkPreview(view, m);

            TextView reactions = view.findViewById(R.id.reactions);
            if (m.reactions.isEmpty()) {
                reactions.setVisibility(View.GONE);
            } else {
                reactions.setText(Reactions.summary(m.reactions));
                reactions.setBackground(Look.rounded(look.dark ? 0xFF3C4043 : 0xFFFFFFFF, Format.dp(view.getContext(), 12)));
                reactions.setVisibility(View.VISIBLE);
                reactions.setOnClickListener(v -> showReactionDetails(m));
            }

            bindTime(view.findViewById(R.id.time), m, endOfRun, position);

            // Links in the text take taps, so the bubble handles its own clicks.
            body.setOnLongClickListener(v -> {
                list.setItemChecked(position, !list.isItemChecked(position));
                return true;
            });
            body.setOnClickListener(v -> {
                // A tap on a link also lands here; the link moved the selection, so skip it.
                CharSequence t = body.getText();
                if (t instanceof Spannable && Selection.getSelectionStart(t) >= 0
                        && Selection.getSelectionStart(t) != Selection.getSelectionEnd(t)) {
                    Selection.removeSelection((Spannable) t);
                    return;
                }
                if (actionMode != null) list.setItemChecked(position, !list.isItemChecked(position));
                else showMessageActions(m);
            });
            return view;
        }
    }

    private void bindTime(TextView time, Message m, boolean endOfRun, int position) {
        Context c = time.getContext();
        boolean lastOutgoing = !m.incoming() && !m.notSentYet() && isLastOutgoing(position);
        String status = "";
        if (m.status == Message.STATUS_DELIVERED) status = " · Delivered";
        else if (m.status == Message.STATUS_PENDING && !m.sending() && !m.failed()) status = " · Sent";
        else if (m.status == Message.STATUS_FAILED) status = " · Not delivered";
        String sim = "";
        if (m.subId >= 0 && Sims.multi(this)) {
            Sims.Sim s = Sims.byId(this, m.subId);
            if (s != null) sim = " · SIM " + (s.slot + 1);
        }
        String lock = m.locked ? "🔒 " : "";

        if (m.scheduled()) {
            time.setText("🕒 Sends " + Format.messageTime(c, m.date) + (m.parts.isEmpty() ? "" : " · 📎 " + m.parts.size()));
            time.setTextColor(look.accent);
            time.setVisibility(View.VISIBLE);
        } else if (m.delayed()) {
            long secs = Math.max(0, (m.date - System.currentTimeMillis() + 999) / 1000);
            time.setText("Sending in " + secs + "… Tap to cancel");
            time.setTextColor(look.accent);
            time.setVisibility(View.VISIBLE);
        } else if (m.failed()) {
            time.setText(lock + "Not sent. Tap for options");
            time.setTextColor(getColor(R.color.failed));
            time.setVisibility(View.VISIBLE);
        } else if (m.sending()) {
            time.setText("Sending…");
            time.setTextColor(emptyText.getTextColors());
            time.setVisibility(View.VISIBLE);
        } else if (endOfRun || lastOutgoing || m.locked) {
            time.setText(lock + Format.messageTime(c, m.date) + status + sim);
            time.setTextColor(emptyText.getTextColors());
            time.setVisibility(View.VISIBLE);
        } else {
            time.setVisibility(View.GONE);
        }
    }

    private boolean isLastOutgoing(int position) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message x = messages.get(i);
            if (!x.incoming() && !x.notSentYet()) return i == position;
        }
        return false;
    }

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private String dayLabel(long millis) {
        if (DateUtils.isToday(millis)) return "Today";
        if (DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS)) return "Yesterday";
        return DateUtils.formatDateTime(this, millis, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                | (System.currentTimeMillis() - millis > DateUtils.YEAR_IN_MILLIS ? DateUtils.FORMAT_SHOW_YEAR : 0));
    }

    private void bindLinkPreview(View view, Message m) {
        LinearLayout box = view.findViewById(R.id.link_preview);
        String url = LinkPreview.wanted(this, m) ? LinkPreview.firstUrl(m.body) : null;
        if (url == null) {
            box.setVisibility(View.GONE);
            return;
        }
        if (!previews.containsKey(url)) {
            box.setVisibility(View.GONE);
            if (previewsLoading.add(url)) {
                Context app = getApplicationContext();
                new Thread(() -> {
                    LinkPreview.Info info = LinkPreview.get(app, url);
                    onUi(() -> {
                        previews.put(url, info);
                        previewsLoading.remove(url);
                        if (info != null) adapter.notifyDataSetChanged();
                    });
                }).start();
            }
            return;
        }
        LinkPreview.Info info = previews.get(url);
        if (info == null) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.gravity = m.incoming() ? Gravity.START : Gravity.END;
        box.setLayoutParams(lp);
        box.setBackground(Look.rounded(m.incoming() ? look.bubbleIn : look.bubbleOut, Format.dp(this, 16)));
        box.setClipToOutline(true);
        ImageView image = view.findViewById(R.id.link_image);
        if (info.image != null) {
            image.setVisibility(View.VISIBLE);
            ImageLoader.load(image, Uri.fromFile(new File(info.image)), (int) Format.dp(this, 240), false);
        } else {
            image.setVisibility(View.GONE);
        }
        TextView title = view.findViewById(R.id.link_title);
        TextView site = view.findViewById(R.id.link_site);
        int fg = m.incoming() ? look.bubbleInText : look.bubbleOutText;
        title.setText(info.title != null ? info.title : url);
        title.setTextColor(fg);
        site.setText(info.site != null ? info.site : "");
        site.setTextColor((fg & 0x00FFFFFF) | 0xAA000000);
        box.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No browser app found", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void bindAttachments(LinearLayout box, Message m, int position) {
        box.removeAllViews();
        if (m.parts.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams boxLp = (LinearLayout.LayoutParams) box.getLayoutParams();
        boxLp.gravity = m.incoming() ? Gravity.START : Gravity.END;
        box.setLayoutParams(boxLp);
        Context c = box.getContext();
        int gap = (int) Format.dp(c, 4);
        float radius = Format.dp(c, 16);
        for (Part p : m.parts) {
            View item;
            if (m.delayed() || Uri.EMPTY.equals(p.uri)) {
                TextView file = new TextView(c);
                file.setText("📎  " + p.label());
                file.setTextColor(look.bubbleOutText);
                file.setBackground(Look.rounded(look.bubbleOut, radius));
                int pad = (int) Format.dp(c, 12);
                file.setPadding(pad, pad, pad, pad);
                file.setAlpha(0.6f);
                item = file;
            } else if (p.isImage() || p.isVideo()) {
                item = mediaView(c, p, radius);
                item.setOnClickListener(v -> {
                    if (actionMode != null) {
                        list.setItemChecked(position, !list.isItemChecked(position));
                    } else if (p.isImage()) {
                        startActivity(new Intent(this, ImageViewerActivity.class)
                                .setData(p.uri)
                                .putExtra(ImageViewerActivity.EXTRA_TYPE, p.contentType)
                                .putExtra(EXTRA_THREAD_ID, keep || m.scheduled() ? -1 : threadId));
                    } else {
                        openExternally(p);
                    }
                });
            } else if (p.isAudio()) {
                item = audioView(c, p, m.incoming());
            } else {
                TextView file = new TextView(c);
                file.setText((p.isContact() ? "👤  " : p.isCalendar() ? "📅  " : "📎  ") + p.label());
                file.setTextColor(m.incoming() ? look.bubbleInText : look.bubbleOutText);
                file.setBackground(Look.rounded(m.incoming() ? look.bubbleIn : look.bubbleOut, radius));
                int pad = (int) Format.dp(c, 12);
                file.setPadding(pad, pad, pad, pad);
                file.setOnClickListener(v -> {
                    if (actionMode != null) list.setItemChecked(position, !list.isItemChecked(position));
                    else if (p.isContact() || p.isCalendar()) openCard(p);
                    else openExternally(p);
                });
                item = file;
            }
            item.setOnLongClickListener(v -> {
                list.setItemChecked(position, !list.isItemChecked(position));
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            box.addView(item, lp);
        }
    }

    /** A picture, GIF or video frame with rounded corners. Video gets a play badge. */
    private View mediaView(Context c, Part p, float radius) {
        ImageView image = new ImageView(c);
        image.setAdjustViewBounds(true);
        image.setMaxWidth((int) Format.dp(c, 240));
        image.setMaxHeight((int) Format.dp(c, 320));
        image.setMinimumWidth((int) Format.dp(c, 120));
        image.setMinimumHeight((int) Format.dp(c, 90));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(Look.rounded(look.bubbleIn, radius));
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        image.setContentDescription(p.label());
        ImageLoader.load(image, p.uri, (int) Format.dp(c, 320), p.isVideo());
        if (!p.isVideo()) return image;

        FrameLayout frame = new FrameLayout(c);
        frame.addView(image);
        ImageView play = new ImageView(c);
        play.setImageResource(R.drawable.ic_play);
        play.setBackground(Look.circle(0x88000000));
        int size = (int) Format.dp(c, 48);
        int pad = (int) Format.dp(c, 10);
        play.setPadding(pad, pad, pad, pad);
        frame.addView(play, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        return frame;
    }

    private View audioView(Context c, Part p, boolean incoming) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Look.rounded(incoming ? look.bubbleIn : look.bubbleOut, Format.dp(c, 18)));
        int pad = (int) Format.dp(c, 6);
        row.setPadding(pad, pad, pad * 2, pad);
        int fg = incoming ? look.bubbleInText : look.bubbleOutText;

        boolean playing = player != null && playingPartId == p.id && player.isPlaying();
        ImageButton button = new ImageButton(c);
        button.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        button.setImageTintList(ColorStateList.valueOf(fg));
        button.setBackground(null);
        button.setContentDescription(playing ? "Pause" : "Play voice message");
        button.setOnClickListener(v -> togglePlay(p));
        int size = (int) Format.dp(c, 40);
        row.addView(button, new LinearLayout.LayoutParams(size, size));

        ProgressBar progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setTag("progress:" + p.id);
        progress.setProgressTintList(ColorStateList.valueOf(fg));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(fg));
        if (player != null && playingPartId == p.id) {
            progress.setMax(Math.max(1, player.getDuration()));
            progress.setProgress(player.getCurrentPosition());
        }
        row.addView(progress, new LinearLayout.LayoutParams((int) Format.dp(c, 140),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Styles a message row: received on the left, sent on the right, in the chosen bubble style. */
    static void bindBubble(View row, Look look, boolean incoming, CharSequence text, boolean lastInRun) {
        LinearLayout layout = row.findViewById(R.id.row);
        TextView body = row.findViewById(R.id.body);
        layout.setGravity(incoming ? Gravity.START : Gravity.END);
        body.setBackground(look.bubble(row.getContext(), incoming ? look.bubbleIn : look.bubbleOut, incoming, lastInRun));
        body.setTextColor(incoming ? look.bubbleInText : look.bubbleOutText);
        body.setLinkTextColor(incoming ? look.accent : look.bubbleOutText);
        body.setTextSize(look.messageTextSize());
        body.setTypeface(look.typeface());
        body.setText(text);
        body.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    static void bindBubble(View row, Look look, boolean incoming, CharSequence text) {
        bindBubble(row, look, incoming, text, true);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Recipients of an sms:/smsto: link, without any "?body=" part. */
    static List<String> addressesFromUri(Uri uri) {
        List<String> result = new ArrayList<>();
        if (uri == null || uri.getSchemeSpecificPart() == null) return result;
        String part = uri.getSchemeSpecificPart();
        int query = part.indexOf('?');
        if (query >= 0) part = part.substring(0, query);
        for (String a : part.split("[,;]")) {
            if (!a.trim().isEmpty()) result.add(a.trim());
        }
        return result;
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
