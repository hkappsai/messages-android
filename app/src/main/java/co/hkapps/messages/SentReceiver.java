package co.hkapps.messages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

/** Marks an outgoing message sent or failed once the network replies. */
public class SentReceiver extends BroadcastReceiver {
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
            SmsWriter.setType(context, row, Telephony.Sms.MESSAGE_TYPE_FAILED, null);
        }
    }
}
