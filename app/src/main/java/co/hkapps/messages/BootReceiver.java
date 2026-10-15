package co.hkapps.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a reboot or an app update: sets scheduled-text alarms again, brings back Quick Compose,
 * and finishes deletes whose undo time ran out.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Context app = context.getApplicationContext();
            Scheduler.armAll(app);
            Notifier.updateQuickCompose(app);
            Cleanup.schedule(app);
            SmsWriter.run(() -> Deleter.purgeDue(app));
        }
    }
}
