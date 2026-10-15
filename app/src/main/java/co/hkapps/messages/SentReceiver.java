package co.hkapps.messages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

/** Marks an outgoing message sent or failed once the network replies. */
public class SentReceiver extends BroadcastReceiver {

    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_THREAD = "thread";

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri row = intent.getData();
        if (row == null || !"content".equals(row.getScheme())) return;
        if (getResultCode() == Activity.RESULT_OK) {
            // A long text reports once per part. Only "sending" rows turn into "sent",
            // so one failed part keeps the whole message marked as failed.
            SmsWriter.setType(context, row, Telephony.Sms.MESSAGE_TYPE_SENT,
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        } else {
            DebugLog.log(context, "SMS failed with result " + getResultCode());
            android.util.Log.w("Messages", "SMS failed with result " + getResultCode()
                    + " error " + intent.getIntExtra("errorCode", 0));
            SmsWriter.setType(context, row, Telephony.Sms.MESSAGE_TYPE_FAILED, null);
            long threadId = intent.getLongExtra(EXTRA_THREAD, -1);
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            // Nobody sees the failure if the chat isn't open, so say so in a notification.
            if (address != null && threadId != ThreadActivity.visibleThreadId) {
                Notifier.showSendFailed(context, threadId, address);
            }
        }
    }
}
