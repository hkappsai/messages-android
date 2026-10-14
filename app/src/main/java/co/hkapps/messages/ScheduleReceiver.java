package co.hkapps.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Alarm for a scheduled text went off: send it. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Uri data = intent.getData();
        if (data == null) return;
        long id;
        try {
            id = Long.parseLong(data.getSchemeSpecificPart());
        } catch (NumberFormatException e) {
            return;
        }
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Scheduler.fire(app, id);
            } finally {
                result.finish();
            }
        }).start();
    }
}
