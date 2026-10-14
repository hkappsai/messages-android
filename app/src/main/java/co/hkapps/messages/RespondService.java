package co.hkapps.messages;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import java.util.List;

/** Sends the quick text picked when declining a call ("Can't talk now…"). */
public class RespondService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        List<String> addresses = ThreadActivity.addressesFromUri(intent.getData());
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        new Thread(() -> {
            if (!addresses.isEmpty() && !TextUtils.isEmpty(text)) {
                Sender.send(getApplicationContext(), addresses, text);
            }
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
