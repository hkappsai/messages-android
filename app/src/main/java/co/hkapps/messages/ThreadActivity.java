package co.hkapps.messages;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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

/** One conversation as chat bubbles, with a box to reply, attach files and record voice. */
public class ThreadActivity extends BaseActivity {

    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_ADDRESSES = "addresses";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_MESSAGE_ID = "message_id";

    /** The conversation on screen right now, so new texts in it don't notify. */
    static volatile long visibleThreadId = -1;

    private static final int MENU_CALL = 1;
    private static final int MENU_PIN = 2;
    private static final int MENU_BLOCK = 3;
    private static final int MENU_DELETE = 4;

    private static final int REQUEST_PICK = 10;
    private static final int REQUEST_CAMERA = 11;
    private static final int REQUEST_AUDIO_PERMISSION = 12;
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

    // Written on the background thread while the conversation gets resolved.
    private volatile long threadId = -1;
    private volatile List<String> recipients = Collections.emptyList();
    private long jumpToMessageId = -1;
    private boolean resumed;
    private boolean observing;
    private MessageReader reader;
    private File cameraFile;

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

    private MessageAdapter adapter;
    private ListView list;
    private EditText input;
    private ImageButton sendButton;
    private TextView counter;
    private TextView emptyText;
    private View composeRow;
    private View recordingBar;
    private TextView recordingTime;
    private View pendingScroll;
    private LinearLayout pendingRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thread);
        showUpButton();
        reader = new MessageReader(this);
        if (savedInstanceState != null && savedInstanceState.getString(STATE_CAMERA_FILE) != null) {
            cameraFile = new File(savedInstanceState.getString(STATE_CAMERA_FILE));
        }

        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        sendButton = findViewById(R.id.send);
        counter = findViewById(R.id.counter);
        emptyText = findViewById(R.id.empty_text);
        composeRow = findViewById(R.id.compose_row);
        recordingBar = findViewById(R.id.recording_bar);
        recordingTime = findViewById(R.id.recording_time);
        pendingScroll = findViewById(R.id.pending_scroll);
        pendingRow = findViewById(R.id.pending);

        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));

        sendButton.setBackground(Look.circle(look.accent));
        sendButton.setImageTintList(ColorStateList.valueOf(look.onAccent));
        sendButton.setOnClickListener(v -> onSendTapped());
        sendButton.setOnLongClickListener(v -> {
            pickScheduleTime();
            return true;
        });
        findViewById(R.id.attach).setOnClickListener(v -> showAttachMenu());

        ImageButton recordingSend = findViewById(R.id.recording_send);
        recordingSend.setBackground(Look.circle(look.accent));
        recordingSend.setImageTintList(ColorStateList.valueOf(look.onAccent));
        recordingSend.setOnClickListener(v -> stopRecording(true));
        findViewById(R.id.recording_cancel).setOnClickListener(v -> stopRecording(false));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateComposeState(); }
        });
        updateComposeState();

        readIntent(getIntent());
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
        if (!observing) {
            getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer);
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
        if (recorder.isRecording()) stopRecording(false);
        stopPlayer();
    }

    // ------------------------------------------------------------ opening

    private void readIntent(Intent intent) {
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1);
        jumpToMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);

        List<String> to = new ArrayList<>();
        String[] many = intent.getStringArrayExtra(EXTRA_ADDRESSES);
        if (many != null) to.addAll(Arrays.asList(many));
        String one = intent.getStringExtra(EXTRA_ADDRESS);
        if (one != null) to.add(one);
        // Links like "smsto:5550100?body=Hi" from other apps.
        Uri data = intent.getData();
        if (data != null) to.addAll(addressesFromUri(data));
        recipients = Collections.unmodifiableList(to);

        String body = intent.getStringExtra(EXTRA_BODY);
        if (body == null) body = intent.getStringExtra("sms_body");
        if (body == null) body = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (body == null) body = bodyFromUri(data);
        if (body != null) {
            input.setText(body);
            input.setSelection(body.length());
        }

        if (threadId == -1 && to.isEmpty()) {
            // Nobody to talk to yet: let the user pick someone.
            Intent pick = new Intent(this, NewMessageActivity.class);
            if (body != null) pick.putExtra(EXTRA_BODY, body);
            startActivity(pick);
            finish();
            return;
        }

        if (!to.isEmpty()) setTitle(TextUtils.join(", ", to));
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
            String title = reader.names(people);
            onUi(() -> {
                setTitle(title);
                if (getActionBar() != null) {
                    getActionBar().setSubtitle(people.size() > 1 ? people.size() + " people" : null);
                }
                invalidateOptionsMenu();
                updateComposeState();
                if (resumed) onConversationVisible();
            });
        });
    }

    private boolean isGroup() {
        return recipients.size() > 1;
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
            List<Message> result = Reactions.apply(reader.loadThread(id));
            List<Scheduler.Item> later = Scheduler.forThread(this, id);
            for (Scheduler.Item item : later) {
                result.add(new Message(item.id, false, id, "", item.text, item.time, Message.TYPE_SCHEDULED, true));
            }
            onUi(() -> {
                messages.clear();
                messages.addAll(result);
                scheduled.clear();
                for (Scheduler.Item item : later) scheduled.put(item.id, item);
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

    // ------------------------------------------------------------ composing

    /** Send with text or files; with nothing typed, the button records a voice memo. */
    private void onSendTapped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) {
            startRecording();
            return;
        }
        if (recipients.isEmpty() || !requireDefault()) return;

        List<Attachment> files = new ArrayList<>(pending);
        List<String> to = recipients;
        Context app = getApplicationContext();
        input.setText("");
        clearPending();
        SmsWriter.run(() -> Sender.send(app, to, text, files));
    }

    /** Mic or send icon, and the SMS counter for plain 1-to-1 texts. */
    private void updateComposeState() {
        CharSequence text = input.getText();
        boolean empty = text.toString().trim().isEmpty() && pending.isEmpty();
        sendButton.setImageResource(empty ? R.drawable.ic_mic : R.drawable.ic_send);
        sendButton.setContentDescription(getString(empty ? R.string.record : R.string.send));

        if (text.length() == 0 || isGroup() || !pending.isEmpty()) {
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

    // ------------------------------------------------------------ attachments

    private void showAttachMenu() {
        if (!requireDefault()) return;
        String[] items = {"Photo or GIF", "Take a photo", "Video", "Voice message"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: pick("image/*"); break;
                        case 1: takePhoto(); break;
                        case 2: pick("video/*"); break;
                        default: startRecording(); break;
                    }
                })
                .show();
    }

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
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
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
        }
    }

    /** Shrinks the file to fit what's left of the MMS size limit, then shows it above the box. */
    private void addAttachment(Uri uri) {
        executor.execute(() -> {
            int used = 0;
            for (Attachment a : pending) used += a.data.length;
            int room = Mms.maxMessageSize(this) - MMS_OVERHEAD - used;
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
                label.setText(a.isAudio() ? "🎤\nVoice" : a.isVideo() ? "🎞\nVideo" : "📎\nFile");
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
        Context app = getApplicationContext();
        SmsWriter.run(() -> {
            byte[] data = Mms.readFile(file);
            file.delete();
            if (data == null) return;
            Attachment memo = new Attachment(VoiceRecorder.CONTENT_TYPE, "voice.amr", data);
            Sender.send(app, to, "", Collections.singletonList(memo));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_AUDIO_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
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
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a message, then hold Send to schedule it", Toast.LENGTH_LONG).show();
            return;
        }
        if (!pending.isEmpty()) {
            Toast.makeText(this, "Scheduling works for text only", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recipients.isEmpty() || threadId == -1 || !requireDefault()) return;

        Calendar now = Calendar.getInstance();
        DatePickerDialog date = new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar at = Calendar.getInstance();
            new TimePickerDialog(this, (tv, hour, minute) -> {
                at.set(year, month, day, hour, minute, 0);
                at.set(Calendar.MILLISECOND, 0);
                schedule(text, at.getTimeInMillis());
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE) + 1,
                    DateFormat.is24HourFormat(this)).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        date.getDatePicker().setMinDate(now.getTimeInMillis() - 1000);
        date.show();
    }

    private void schedule(String text, long time) {
        if (time < System.currentTimeMillis() + 30_000) {
            Toast.makeText(this, "Pick a time in the future", Toast.LENGTH_SHORT).show();
            return;
        }
        Scheduler.add(this, time, threadId, recipients, text);
        input.setText("");
        load();
        Toast.makeText(this, "Scheduled for " + Format.messageTime(this, time), Toast.LENGTH_SHORT).show();
        if (!Scheduler.canBeExact(this) && Build.VERSION.SDK_INT >= 31) {
            new AlertDialog.Builder(this)
                    .setTitle("Send on time?")
                    .setMessage("Android may hold scheduled texts back by a few minutes. "
                            + "Allow exact alarms so they go out right on time.")
                    .setPositiveButton("Allow", (d, w) -> startActivity(new Intent(
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
                .setTitle("Scheduled for " + Format.messageTime(this, item.time))
                .setItems(new String[]{"Send now", "Edit", "Delete"}, (d, which) -> {
                    if (which == 0) {
                        if (!requireDefault()) return;
                        SmsWriter.run(() -> Scheduler.fire(app, item.id));
                    } else if (which == 1) {
                        Scheduler.cancel(this, item.id);
                        input.setText(item.text);
                        input.setSelection(item.text.length());
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
        if (recipients.isEmpty()) return true;
        if (!isGroup()) {
            menu.add(0, MENU_CALL, 0, R.string.call)
                    .setIcon(R.drawable.ic_call)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (threadId != -1) {
            menu.add(0, MENU_PIN, 1, Prefs.isPinned(this, threadId) ? R.string.unpin : R.string.pin);
        }
        if (!isGroup()) {
            menu.add(0, MENU_BLOCK, 2, Prefs.isBlocked(this, recipients.get(0)) ? R.string.unblock : R.string.block);
        }
        if (threadId != -1) menu.add(0, MENU_DELETE, 3, R.string.delete);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CALL:
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipients.get(0), null)));
                return true;
            case MENU_PIN:
                Prefs.setPinned(this, threadId, !Prefs.isPinned(this, threadId));
                invalidateOptionsMenu();
                return true;
            case MENU_BLOCK: {
                String address = recipients.get(0);
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
                    for (Scheduler.Item s : scheduled.values()) Scheduler.cancel(this, s.id);
                    SmsWriter.run(() -> SmsWriter.deleteThread(app, id));
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMessageActions(Message m) {
        if (m.scheduled()) {
            showScheduledActions(m);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Context app = getApplicationContext();

        if (m.failed()) {
            labels.add("Send again");
            actions.add(() -> {
                if (requireDefault()) SmsWriter.run(() -> Sender.resend(app, m));
            });
        }
        if (!m.body.isEmpty() && !m.failed() && !m.sending()) {
            labels.add("React");
            actions.add(() -> showReactionPicker(m));
        }
        if (!m.body.isEmpty()) {
            labels.add("Copy text");
            actions.add(() -> {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                clipboard.setPrimaryClip(ClipData.newPlainText("message", m.body));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            });
            labels.add("Select text");
            actions.add(() -> showSelectableText(m.body));
        }
        labels.add("Share");
        actions.add(() -> share(m));
        labels.add(getString(R.string.delete));
        actions.add(() -> {
            if (!requireDefault()) return;
            new AlertDialog.Builder(this)
                    .setMessage("Delete this message?")
                    .setPositiveButton(R.string.delete, (d, w) ->
                            SmsWriter.run(() -> SmsWriter.deleteMessage(app, m)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
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
        executor.execute(() -> {
            Uri uri = copyToShare(part);
            onUi(() -> {
                if (uri == null) return;
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, part.contentType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(view);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
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

            bindBubble(view, look, m.incoming(), m.body);
            TextView body = view.findViewById(R.id.body);
            body.setAlpha(m.scheduled() ? 0.6f : 1f);

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

            bindAttachments(view.findViewById(R.id.attachments), m);

            TextView reactions = view.findViewById(R.id.reactions);
            if (m.reactions.isEmpty()) {
                reactions.setVisibility(View.GONE);
            } else {
                reactions.setText(Reactions.summary(m.reactions));
                reactions.setBackground(Look.rounded(look.dark ? 0xFF3C4043 : 0xFFFFFFFF, Format.dp(view.getContext(), 12)));
                reactions.setVisibility(View.VISIBLE);
            }

            // Group bubbles: only show the time at the end of a run of messages.
            TextView time = view.findViewById(R.id.time);
            boolean endOfRun = next == null || next.incoming() != m.incoming()
                    || next.date - m.date > TIME_GAP_MS || next.scheduled();
            if (m.scheduled()) {
                time.setText("🕒 Scheduled · " + Format.messageTime(parent.getContext(), m.date));
                time.setTextColor(look.accent);
                time.setVisibility(View.VISIBLE);
            } else if (m.failed()) {
                time.setText("Not sent. Tap to try again");
                time.setTextColor(getColor(R.color.failed));
                time.setVisibility(View.VISIBLE);
            } else if (m.sending()) {
                time.setText("Sending…");
                time.setTextColor(emptyText.getTextColors());
                time.setVisibility(View.VISIBLE);
            } else if (endOfRun) {
                time.setText(Format.messageTime(parent.getContext(), m.date));
                time.setTextColor(emptyText.getTextColors());
                time.setVisibility(View.VISIBLE);
            } else {
                time.setVisibility(View.GONE);
            }

            // Links in the text take taps, so the bubble handles its own clicks.
            body.setOnLongClickListener(v -> {
                showMessageActions(m);
                return true;
            });
            body.setOnClickListener(v -> {
                if (m.failed() || m.scheduled()) showMessageActions(m);
            });
            return view;
        }
    }

    private void bindAttachments(LinearLayout box, Message m) {
        box.removeAllViews();
        if (m.parts.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        box.setVisibility(View.VISIBLE);
        Context c = box.getContext();
        int gap = (int) Format.dp(c, 4);
        float radius = Format.dp(c, 16);
        for (Part p : m.parts) {
            View item;
            if (p.isImage() || p.isVideo()) {
                item = mediaView(c, p, radius);
                item.setOnClickListener(v -> {
                    if (p.isImage()) {
                        startActivity(new Intent(this, ImageViewerActivity.class)
                                .setData(p.uri).putExtra(ImageViewerActivity.EXTRA_TYPE, p.contentType));
                    } else {
                        openExternally(p);
                    }
                });
            } else if (p.isAudio()) {
                item = audioView(c, p, m.incoming());
            } else {
                TextView file = new TextView(c);
                file.setText("📎  " + p.label());
                file.setTextColor(m.incoming() ? look.bubbleInText : look.bubbleOutText);
                file.setBackground(Look.rounded(m.incoming() ? look.bubbleIn : look.bubbleOut, radius));
                int pad = (int) Format.dp(c, 12);
                file.setPadding(pad, pad, pad, pad);
                file.setOnClickListener(v -> openExternally(p));
                item = file;
            }
            item.setOnLongClickListener(v -> {
                showMessageActions(m);
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
        body.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
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
