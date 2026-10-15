package co.hkapps.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.Collections;

/** Gets new texts when this is the default SMS app, saves them and notifies. */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) return;

        // A long text arrives in several parts; join them back into one message.
        String address = parts[0].getDisplayOriginatingAddress();
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) {
            if (part != null && part.getDisplayMessageBody() != null) {
                body.append(part.getDisplayMessageBody());
            }
        }
        long sentAt = parts[0].getTimestampMillis();
        int subId = intent.getIntExtra("subscription", -1);
        String text = body.toString();

        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Uri row = SmsWriter.insertIncoming(app, address, text, sentAt, subId);
                long threadId = new MessageReader(app).threadIdOf(row);
                Incoming.handle(app, threadId, Collections.singletonList(address), address, text, -1);
            } finally {
                result.finish();
            }
        }).start();
    }
}
