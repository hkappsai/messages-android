package co.hkapps.messages;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.List;

/** New-message notifications with inline reply and "Mark as read". */
public final class Notifier {

    private static final String CHANNEL = "messages";
    private static final int MMS_FAILED_ID = -2;

    private Notifier() {}

    /** recipients: everyone in the chat, so a reply reaches the whole group. */
    public static void showMessage(Context context, long threadId, List<String> recipients, String title, String body) {
        if (!canNotify(context)) return;
        int id = (int) threadId;

        Intent open = new Intent(context, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        PendingIntent content = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(open)
                .getPendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The reply text gets filled in by the system, so this PendingIntent must be mutable.
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent reply = new Intent(context, ReplyReceiver.class)
                .setAction(ReplyReceiver.ACTION_REPLY)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(ThreadActivity.EXTRA_ADDRESSES, recipients.toArray(new String[0]));
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, id, reply,
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        RemoteInput input = new RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
                .setLabel(context.getString(R.string.reply))
                .build();
        Notification.Action replyAction = new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_send),
                context.getString(R.string.reply), replyIntent)
                .addRemoteInput(input)
                .build();

        Intent markRead = new Intent(context, ReplyReceiver.class)
                .setAction(ReplyReceiver.ACTION_MARK_READ)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        PendingIntent markReadIntent = PendingIntent.getBroadcast(context, id, markRead,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action markReadAction = new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_message),
                context.getString(R.string.mark_read), markReadIntent)
                .build();

        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_message)
                .setColor(Prefs.accent(context))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(content)
                .addAction(replyAction)
                .addAction(markReadAction)
                .build();
        manager(context).notify(id, notification);
    }

    /** A picture message couldn't be fetched (no mobile data, say). Offers a retry. */
    public static void showMmsFailed(Context context, String from, String location, String transactionId, int subId) {
        if (!canNotify(context)) return;
        Intent retry = new Intent(context, MmsResultReceiver.class)
                .setAction(MmsResultReceiver.ACTION_RETRY)
                .putExtra(Mms.EXTRA_LOCATION, location)
                .putExtra(Mms.EXTRA_TRANSACTION, transactionId)
                .putExtra(Mms.EXTRA_FROM, from)
                .putExtra(Mms.EXTRA_SUB, subId);
        PendingIntent retryIntent = PendingIntent.getBroadcast(context, MMS_FAILED_ID, retry,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String who = from == null ? "someone" : new MessageReader(context).lookupName(from);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_message)
                .setColor(Prefs.accent(context))
                .setContentTitle("Couldn't download a picture message")
                .setContentText("From " + who + ". Check that mobile data is on.")
                .setAutoCancel(true)
                .setContentIntent(retryIntent)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_message), "Try again", retryIntent).build())
                .build();
        manager(context).notify(MMS_FAILED_ID, notification);
    }

    static void cancelMmsFailed(Context context) {
        manager(context).cancel(MMS_FAILED_ID);
    }

    public static void cancel(Context context, long threadId) {
        manager(context).cancel((int) threadId);
    }

    private static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        manager(context).createNotificationChannel(channel);
        return true;
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }
}
