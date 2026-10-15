package co.hkapps.messages;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.Arrays;

/** Buttons and events from new-message notifications. */
public class NotifyReceiver extends BroadcastReceiver {

    static final String ACTION_REPLY = "co.hkapps.messages.REPLY";
    static final String ACTION_MARK_READ = "co.hkapps.messages.MARK_READ";
    static final String ACTION_LEAVE_UNREAD = "co.hkapps.messages.LEAVE_UNREAD";
    static final String ACTION_CALL = "co.hkapps.messages.CALL";
    static final String ACTION_DELETE_LAST = "co.hkapps.messages.DELETE_LAST";
    static final String ACTION_BLOCK = "co.hkapps.messages.BLOCK";
    static final String ACTION_COPY = "co.hkapps.messages.COPY";
    static final String ACTION_DISMISSED = "co.hkapps.messages.DISMISSED";
    static final String ACTION_REPEAT = "co.hkapps.messages.REPEAT";
    static final String KEY_TEXT = "text";
    static final String EXTRA_VALUE = "value";
    static final String EXTRA_REMAINING = "remaining";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        long threadId = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        String value = intent.getStringExtra(EXTRA_VALUE);
        Context app = context.getApplicationContext();

        // Things that must happen on the main thread right away.
        if (ACTION_COPY.equals(action)) {
            ClipboardManager cm = app.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("code", value));
            Toast.makeText(app, "Copied " + value, Toast.LENGTH_SHORT).show();
            return;
        }
        if (ACTION_CALL.equals(action)) {
            Notifier.cancel(app, threadId);
            app.startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }

        String[] addresses = intent.getStringArrayExtra(ThreadActivity.EXTRA_ADDRESSES);
        CharSequence reply = null;
        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) reply = results.getCharSequence(KEY_TEXT);
        }
        String text = reply == null ? null : reply.toString().trim();
        int remaining = intent.getIntExtra(EXTRA_REMAINING, 0);

        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                switch (action) {
                    case ACTION_REPLY:
                        if (!TextUtils.isEmpty(text) && addresses != null && addresses.length > 0) {
                            Sender.send(app, Arrays.asList(addresses), text);
                        }
                        if (threadId != -1) SmsWriter.markRead(app, threadId);
                        // Removing the notification also stops the reply spinner.
                        Notifier.cancel(app, threadId);
                        break;
                    case ACTION_MARK_READ:
                        SmsWriter.markRead(app, threadId);
                        Notifier.cancel(app, threadId);
                        break;
                    case ACTION_LEAVE_UNREAD:
                        Notifier.cancel(app, threadId);
                        break;
                    case ACTION_DELETE_LAST:
                        SmsWriter.deleteLatestIncoming(app, threadId);
                        Notifier.cancel(app, threadId);
                        break;
                    case ACTION_BLOCK:
                        if (value != null) Prefs.setBlocked(app, value, true);
                        Notifier.cancel(app, threadId);
                        break;
                    case ACTION_DISMISSED:
                        Notifier.cancelRepeat(app, threadId);
                        if (ChatPrefs.bool(app, threadId, Prefs.CLEAR_MARKS_READ)) SmsWriter.markRead(app, threadId);
                        break;
                    case ACTION_REPEAT:
                        // Alert again while there is still something unread.
                        if (!new MessageReader(app).unreadIn(threadId, 1).isEmpty()) {
                            Notifier.notifyThread(app, threadId, true, false);
                            Notifier.scheduleRepeat(app, threadId, remaining - 1);
                        }
                        break;
                    default:
                        break;
                }
            } finally {
                result.finish();
            }
        }).start();
    }
}
