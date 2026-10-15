package co.hkapps.messages;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.Collection;
import java.util.Set;

/**
 * Deletes with a 20-second undo, like Textra. Items are hidden at once and really deleted when
 * the time is up: by a timer while the app runs, or by an alarm if the app was closed meanwhile.
 * Keys: "sms:12", "mms:7", "thread:3", "note:5".
 */
final class Deleter {

    static final long UNDO_MS = 20_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Deleter() {}

    /** Hides the items now and deletes them after the undo time. */
    static void schedule(Context context, Collection<String> keys) {
        Context app = context.getApplicationContext();
        long due = System.currentTimeMillis() + UNDO_MS;
        AppDb db = AppDb.get(app);
        for (String k : keys) db.addPendingDelete(k, due);
        MAIN.postDelayed(() -> SmsWriter.run(() -> purgeDue(app)), UNDO_MS + 200);
        app.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due + 1000,
                alarm(app));
    }

    static void undo(Context context, Collection<String> keys) {
        AppDb db = AppDb.get(context);
        for (String k : keys) db.removePendingDelete(k);
    }

    /** Keys waiting for their undo time, so lists can hide them. */
    static Set<String> pending(Context context) {
        return AppDb.get(context).allPendingDeletes();
    }

    /** Deletes everything whose undo time is over. Call off the main thread. */
    static void purgeDue(Context context) {
        AppDb db = AppDb.get(context);
        for (String key : db.pendingDeletes(System.currentTimeMillis())) {
            if (key.startsWith("note:")) {
                db.deleteNote(Long.parseLong(key.substring(5)));
            } else {
                SmsWriter.deleteByKey(context, key);
                if (key.startsWith("thread:")) {
                    long id = Long.parseLong(key.substring(7));
                    Prefs.setPinned(context, id, false);
                    for (Scheduler.Item s : Scheduler.forThread(context, id)) Scheduler.cancel(context, s.id);
                    Shortcuts.remove(context, id);
                }
            }
            db.removePendingDelete(key);
        }
    }

    private static PendingIntent alarm(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, PurgeReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Wakes up after the undo time in case the app was closed. */
    public static class PurgeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context app = context.getApplicationContext();
            PendingResult result = goAsync();
            SmsWriter.run(() -> {
                try {
                    purgeDue(app);
                } finally {
                    result.finish();
                }
            });
        }
    }
}
