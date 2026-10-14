package co.hkapps.messages;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

/** Sends the quick text picked when declining a call ("Can't talk now…"). */
public class RespondService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String address = ThreadActivity.addressFromUri(intent.getData());
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        new Thread(() -> {
            if (!TextUtils.isEmpty(address) && !TextUtils.isEmpty(text)) {
                SmsWriter.send(getApplicationContext(), address, text);
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
