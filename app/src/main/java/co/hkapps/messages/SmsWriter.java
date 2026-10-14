package co.hkapps.messages;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends texts and changes the SMS provider. Android only accepts these writes from the
 * default SMS app; for other apps they silently do nothing. Call off the main thread.
 */
public final class SmsWriter {

    // One queue for every write. It belongs to no screen, so a text still goes out
    // when the user leaves the conversation right after tapping Send.
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();

    private SmsWriter() {}

    /** Runs a write in the background. */
    public static void run(Runnable task) {
        WRITES.execute(task);
    }

    /** Saves a received message to the inbox and returns its row. */
    public static Uri insertIncoming(Context context, String address, String body, long sentAt, int subId) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, address);
        values.put(Telephony.Sms.BODY, body);
        values.put(Telephony.Sms.DATE, System.currentTimeMillis());
        values.put(Telephony.Sms.DATE_SENT, sentAt);
        values.put(Telephony.Sms.READ, 0);
        values.put(Telephony.Sms.SEEN, 0);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        if (subId >= 0) values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        return context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
    }

    /**
     * Saves the message as "sending", hands it to the radio, and lets SentReceiver mark it
     * sent or failed once the network answers.
     */
    public static void send(Context context, String address, String body) {
        long threadId = Telephony.Threads.getOrCreateThreadId(context, address);

        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.THREAD_ID, threadId);
        values.put(Telephony.Sms.ADDRESS, address);
        values.put(Telephony.Sms.BODY, body);
        values.put(Telephony.Sms.DATE, System.currentTimeMillis());
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        Uri row = context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);

        // The row URI makes each PendingIntent unique, so results never mix between messages.
        Intent intent = new Intent(context, SentReceiver.class)
                .setData(row != null ? row : Uri.parse("sent:" + System.nanoTime()));
        PendingIntent sentIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        SmsManager manager = smsManager(context);
        ArrayList<String> parts = manager.divideMessage(body);
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) sentIntents.add(sentIntent);
        try {
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null);
        } catch (RuntimeException e) {
            // No SIM, airplane mode, bad number and so on: show it as failed right away.
            if (row != null) setType(context, row, Telephony.Sms.MESSAGE_TYPE_FAILED, null);
        }
    }

    /** Deletes a failed message and sends its text again. */
    public static void resend(Context context, Sms sms) {
        deleteMessage(context, sms.id);
        send(context, sms.address, sms.body);
    }

    /** Changes a message's type. With onlyIfType set, rows of another type stay as they are. */
    static void setType(Context context, Uri row, int type, Integer onlyIfType) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.TYPE, type);
        String where = onlyIfType == null ? null : Telephony.Sms.TYPE + " = " + onlyIfType;
        context.getContentResolver().update(row, values, where, null);
    }

    public static void markRead(Context context, long threadId) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        safeUpdate(context.getContentResolver(), values,
                Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.READ + " = 0",
                new String[]{String.valueOf(threadId)});
    }

    public static void markAllRead(Context context) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        safeUpdate(context.getContentResolver(), values, Telephony.Sms.READ + " = 0", null);
    }

    public static void deleteThread(Context context, long threadId) {
        try {
            context.getContentResolver().delete(Telephony.Sms.CONTENT_URI,
                    Telephony.Sms.THREAD_ID + " = ?", new String[]{String.valueOf(threadId)});
        } catch (RuntimeException ignored) {
            // Not the default SMS app: nothing to delete.
        }
    }

    public static void deleteMessage(Context context, long id) {
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null);
        } catch (RuntimeException ignored) {
            // Not the default SMS app.
        }
    }

    private static void safeUpdate(ContentResolver resolver, ContentValues values, String where, String[] args) {
        try {
            resolver.update(Telephony.Sms.CONTENT_URI, values, where, args);
        } catch (RuntimeException ignored) {
            // Not the default SMS app.
        }
    }

    @SuppressWarnings("deprecation")
    private static SmsManager smsManager(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }
}
