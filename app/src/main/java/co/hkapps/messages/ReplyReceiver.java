package co.hkapps.messages;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

/** Handles the "Reply" and "Mark as read" buttons on a notification. */
public class ReplyReceiver extends BroadcastReceiver {

    static final String ACTION_REPLY = "co.hkapps.messages.REPLY";
    static final String ACTION_MARK_READ = "co.hkapps.messages.MARK_READ";
    static final String KEY_TEXT = "text";

    @Override
    public void onReceive(Context context, Intent intent) {
        long threadId = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1);
        String address = intent.getStringExtra(ThreadActivity.EXTRA_ADDRESS);
        CharSequence reply = null;
        if (ACTION_REPLY.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) reply = results.getCharSequence(KEY_TEXT);
        }
        String text = reply == null ? null : reply.toString().trim();

        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (!TextUtils.isEmpty(text) && !TextUtils.isEmpty(address)) {
                    SmsWriter.send(app, address, text);
                }
                if (threadId != -1) SmsWriter.markRead(app, threadId);
            } finally {
                // Removing the notification also stops the reply spinner.
                Notifier.cancel(app, threadId);
                result.finish();
            }
        }).start();
    }
}
