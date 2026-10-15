package co.hkapps.messages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

/** Results from the phone's MMS service: sent, downloaded, or "retry" from a notification. */
public class MmsResultReceiver extends BroadcastReceiver {

    static final String ACTION_SENT = "co.hkapps.messages.MMS_SENT";
    static final String ACTION_DOWNLOADED = "co.hkapps.messages.MMS_DOWNLOADED";
    static final String ACTION_RETRY = "co.hkapps.messages.MMS_RETRY";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int resultCode = getResultCode();
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (ACTION_SENT.equals(action)) {
                    Mms.onSent(app, intent.getData(), resultCode == Activity.RESULT_OK,
                            intent.getByteArrayExtra(android.telephony.SmsManager.EXTRA_MMS_DATA));
                    String path = intent.getStringExtra(Mms.EXTRA_FILE);
                    if (path != null) new File(path).delete();
                } else if (ACTION_DOWNLOADED.equals(action)) {
                    Mms.onDownloaded(app, intent, resultCode);
                } else if (ACTION_RETRY.equals(action)) {
                    Notifier.cancelMmsFailed(app);
                    Mms.download(app, intent.getStringExtra(Mms.EXTRA_LOCATION),
                            intent.getStringExtra(Mms.EXTRA_TRANSACTION),
                            intent.getStringExtra(Mms.EXTRA_FROM),
                            intent.getIntExtra(Mms.EXTRA_SUB, -1));
                }
            } finally {
                result.finish();
            }
        }).start();
    }
}
