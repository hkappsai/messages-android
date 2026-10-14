package co.hkapps.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

/** Gets "you have an MMS" notices (WAP push) and starts the download. */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) return;
        byte[] data = intent.getByteArrayExtra("data");
        int subId = intent.getIntExtra("subscription", -1);
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Mms.onWapPush(app, data, subId);
            } finally {
                result.finish();
            }
        }).start();
    }
}
