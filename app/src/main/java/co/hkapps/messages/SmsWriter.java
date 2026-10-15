package co.hkapps.messages;

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

    /** Sends from Android's default SIM, without a delivery report. */
    public static void send(Context context, String address, String body) {
        send(context, address, body, -1, false, false);
    }

    /**
     * Saves the message as "sending", hands it to the radio, and lets SentReceiver mark it
     * sent or failed once the network answers. With deliveryReport, DeliveryReceiver later marks
     * it delivered. With split, a long text goes out as separate texts instead of one joined text,
     * for carriers that fail long messages.
     */
    public static void send(Context context, String address, String body, int subId,
                            boolean deliveryReport, boolean split) {
        long threadId = Telephony.Threads.getOrCreateThreadId(context, address);

        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.THREAD_ID, threadId);
        values.put(Telephony.Sms.ADDRESS, address);
        values.put(Telephony.Sms.BODY, body);
        values.put(Telephony.Sms.DATE, System.currentTimeMillis());
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        if (subId >= 0) values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        if (deliveryReport) values.put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING);
        Uri row = context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
        Uri key = row != null ? row : Uri.parse("sent:" + System.nanoTime());

        // The row URI makes each PendingIntent unique, so results never mix between messages.
        Intent intent = new Intent(context, SentReceiver.class).setData(key)
                .putExtra(SentReceiver.EXTRA_ADDRESS, address)
                .putExtra(SentReceiver.EXTRA_THREAD, threadId);
        PendingIntent sentIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent deliveredIntent = null;
        if (deliveryReport) {
            // The report comes back with the PDU filled in, so this one has to be mutable.
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            deliveredIntent = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, DeliveryReceiver.class).setData(key),
                    PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        }

        SmsManager manager = smsManager(context, subId);
        ArrayList<String> parts = manager.divideMessage(body);
        try {
            if (split && parts.size() > 1) {
                for (int i = 0; i < parts.size(); i++) {
                    manager.sendTextMessage(address, null, parts.get(i), sentIntent,
                            i == parts.size() - 1 ? deliveredIntent : null);
                }
            } else {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                ArrayList<PendingIntent> deliveredIntents = deliveredIntent == null ? null : new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentIntents.add(sentIntent);
                    // One report is enough: ask only on the last part.
                    if (deliveredIntents != null) deliveredIntents.add(i == parts.size() - 1 ? deliveredIntent : null);
                }
                manager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents);
            }
        } catch (RuntimeException e) {
            // No SIM, airplane mode, bad number and so on: show it as failed right away.
            DebugLog.log(context, "SMS send failed: " + e);
            android.util.Log.w("Messages", "SMS send failed", e);
            if (row != null) setType(context, row, Telephony.Sms.MESSAGE_TYPE_FAILED, null);
        }
    }

    /** Changes a message's type. With onlyIfType set, rows of another type stay as they are. */
    static void setType(Context context, Uri row, int type, Integer onlyIfType) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.TYPE, type);
        String where = onlyIfType == null ? null : Telephony.Sms.TYPE + " = " + onlyIfType;
        context.getContentResolver().update(row, values, where, null);
    }

    public static void markRead(Context context, long threadId) {
        String[] args = {String.valueOf(threadId)};
        safeUpdate(context, Telephony.Sms.CONTENT_URI, readValues(),
                Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.READ + " = 0", args);
        safeUpdate(context, Telephony.Mms.CONTENT_URI, readValues(),
                Telephony.Mms.THREAD_ID + " = ? AND " + Telephony.Mms.READ + " = 0", args);
    }

    public static void markAllRead(Context context) {
        safeUpdate(context, Telephony.Sms.CONTENT_URI, readValues(), Telephony.Sms.READ + " = 0", null);
        safeUpdate(context, Telephony.Mms.CONTENT_URI, readValues(), Telephony.Mms.READ + " = 0", null);
    }

    private static ContentValues readValues() {
        ContentValues values = new ContentValues();
        values.put("read", 1);
        values.put("seen", 1);
        return values;
    }

    /** Deletes every SMS and MMS in a conversation. */
    /** Deletes every SMS and MMS in a conversation, except locked ones. */
    public static void deleteThread(Context context, long threadId) {
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId), "locked = 0", null);
        } catch (RuntimeException ignored) {
            // Not the default SMS app: nothing to delete.
        }
    }

    public static void deleteMessage(Context context, Message message) {
        deleteMessage(context, message.mms, message.id);
    }

    public static void deleteMessage(Context context, boolean mms, long id) {
        Uri base = mms ? Telephony.Mms.CONTENT_URI : Telephony.Sms.CONTENT_URI;
        try {
            context.getContentResolver().delete(ContentUris.withAppendedId(base, id), null, null);
        } catch (RuntimeException ignored) {
            // Not the default SMS app.
        }
    }

    /** Deletes by key: "sms:12", "mms:7" or "thread:3". */
    public static void deleteByKey(Context context, String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return;
        long id;
        try {
            id = Long.parseLong(key.substring(colon + 1));
        } catch (NumberFormatException e) {
            return;
        }
        String kind = key.substring(0, colon);
        if (kind.equals("thread")) {
            deleteThread(context, id);
            ChatPrefs.clear(context, id);
            AppDb.get(context).setDraft(id, null);
        } else {
            deleteMessage(context, kind.equals("mms"), id);
        }
    }

    public static void setLocked(Context context, Message message, boolean locked) {
        Uri base = message.mms ? Telephony.Mms.CONTENT_URI : Telephony.Sms.CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put("locked", locked ? 1 : 0);
        safeUpdate(context, ContentUris.withAppendedId(base, message.id), values, null, null);
    }

    /** Marks the newest received message unread, so the chat shows as unread again. */
    public static void markUnread(Context context, long threadId) {
        String[] args = {String.valueOf(threadId)};
        long smsId = -1, smsDate = -1, mmsId = -1, mmsDate = -1;
        try (android.database.Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms._ID, Telephony.Sms.DATE}, Telephony.Sms.THREAD_ID + " = ? AND "
                        + Telephony.Sms.TYPE + " = " + Telephony.Sms.MESSAGE_TYPE_INBOX, args,
                Telephony.Sms.DATE + " DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) {
                smsId = c.getLong(0);
                smsDate = c.getLong(1);
            }
        } catch (RuntimeException ignored) {
            // No SMS access.
        }
        try (android.database.Cursor c = context.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                new String[]{Telephony.Mms._ID, Telephony.Mms.DATE}, Telephony.Mms.THREAD_ID + " = ? AND "
                        + Telephony.Mms.MESSAGE_BOX + " = " + Telephony.Mms.MESSAGE_BOX_INBOX, args,
                Telephony.Mms.DATE + " DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) {
                mmsId = c.getLong(0);
                mmsDate = c.getLong(1) * 1000;
            }
        } catch (RuntimeException ignored) {
            // Phone without MMS.
        }
        ContentValues values = new ContentValues();
        values.put("read", 0);
        if (mmsId >= 0 && mmsDate > smsDate) {
            safeUpdate(context, ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId), values, null, null);
        } else if (smsId >= 0) {
            safeUpdate(context, ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsId), values, null, null);
        }
    }

    /** Deletes the newest received message in a chat ("Delete last" on a notification). */
    public static void deleteLatestIncoming(Context context, long threadId) {
        try (android.database.Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms._ID}, Telephony.Sms.THREAD_ID + " = ? AND "
                        + Telephony.Sms.TYPE + " = " + Telephony.Sms.MESSAGE_TYPE_INBOX,
                new String[]{String.valueOf(threadId)}, Telephony.Sms.DATE + " DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) deleteMessage(context, false, c.getLong(0));
        } catch (RuntimeException ignored) {
            // Not the default SMS app.
        }
    }

    /** Sets the delivery status of a sent SMS row. */
    static void setStatus(Context context, Uri row, int status) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.STATUS, status);
        safeUpdate(context, row, values, null, null);
    }

    private static void safeUpdate(Context context, Uri uri, ContentValues values, String where, String[] args) {
        try {
            context.getContentResolver().update(uri, values, where, args);
        } catch (RuntimeException ignored) {
            // Not the default SMS app.
        }
    }

    /** The SmsManager for a SIM, or the default one when subId is negative. */
    @SuppressWarnings("deprecation")
    static SmsManager smsManager(Context context, int subId) {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager manager = context.getSystemService(SmsManager.class);
            return subId >= 0 ? manager.createForSubscriptionId(subId) : manager;
        }
        return subId >= 0 ? SmsManager.getSmsManagerForSubscriptionId(subId) : SmsManager.getDefault();
    }
}
