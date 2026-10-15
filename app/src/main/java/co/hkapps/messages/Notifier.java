package co.hkapps.messages;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.List;

/**
 * New-message notifications, built from the chat's unread messages and its notification
 * settings: sound, vibration, LED, privacy, buttons, heads-up style, repeats, bubbles.
 */
public final class Notifier {

    private static final String GROUP_INCOMING = "incoming";
    private static final String CHANNEL_SILENT = "unknown_silent";
    private static final String CHANNEL_OTHER = "other";
    private static final String CHANNEL_COMPOSE = "quick_compose";
    private static final int MMS_FAILED_ID = -2;
    private static final int COMPOSE_ID = -3;
    private static final int FAILED_BASE = 1_000_000;
    private static final int MAX_LINES = 8;
    static final long REPEAT_INTERVAL = 2 * 60 * 1000;

    private Notifier() {}

    // ---------------------------------------------------------------- new messages

    /**
     * Shows (or refreshes) the notification for a chat's unread messages.
     * alert: make sound and vibrate. silent: an unknown sender set to "Show silently".
     */
    public static void notifyThread(Context context, long threadId, boolean alert, boolean silent) {
        if (!canNotify(context) || threadId < 0) return;
        MessageReader reader = new MessageReader(context);
        List<Message> unread = reader.unreadIn(threadId, MAX_LINES);
        if (unread.isEmpty()) {
            cancel(context, threadId);
            return;
        }
        List<String> recipients = reader.recipientsOf(threadId);
        boolean group = recipients.size() > 1;
        String chatName = ChatPrefs.name(context, threadId);
        if (chatName == null) chatName = reader.names(recipients);
        Message latest = unread.get(unread.size() - 1);
        int id = (int) threadId;

        boolean showContact = ChatPrefs.bool(context, threadId, Prefs.SHOW_CONTACT);
        boolean showText = ChatPrefs.bool(context, threadId, Prefs.SHOW_TEXT);
        String title = showContact ? chatName : "New message";
        int count = countUnread(reader, threadId, unread.size());
        String summary = count == 1 ? "1 new message" : count + " new messages";

        int color = Look.chatColor(context, threadId, recipients);
        int size = (int) Format.dp(context, 64);
        Bitmap avatar = showContact ? Avatars.get(context, group ? chatName : recipients.isEmpty() ? "" : recipients.get(0),
                chatName, size, Look.avatarColor(group ? chatName : latest.address)) : null;

        Notification.Builder b = new Notification.Builder(context, channelFor(context, threadId, silent))
                .setSmallIcon(R.drawable.ic_message)
                .setColor(color)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .setWhen(latest.date)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setGroup("thread:" + threadId)
                .setVisibility(showText && showContact ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PRIVATE)
                .setContentIntent(tapIntent(context, threadId))
                .setDeleteIntent(receiverIntent(context, NotifyReceiver.ACTION_DISMISSED, threadId, null));
        if (avatar != null) b.setLargeIcon(avatar);
        if (Prefs.bool(context, Prefs.BADGE)) b.setNumber(count);

        if (!showText) {
            b.setContentTitle(title).setContentText(summary);
        } else if (Build.VERSION.SDK_INT >= 28) {
            Person me = new Person.Builder().setName("You").build();
            Notification.MessagingStyle style = new Notification.MessagingStyle(me);
            if (group) {
                style.setConversationTitle(showContact ? chatName : null);
                style.setGroupConversation(true);
            }
            for (Message m : unread) {
                Person.Builder who = new Person.Builder()
                        .setName(showContact ? (m.senderName != null ? m.senderName : chatName) : "Someone")
                        .setKey(m.address);
                if (showContact && !group && avatar != null) who.setIcon(Icon.createWithBitmap(avatar));
                style.addMessage(new Notification.MessagingStyle.Message(text(m), m.date, who.build()));
            }
            b.setStyle(style);
            b.setContentTitle(title).setContentText(text(latest));
        } else {
            StringBuilder lines = new StringBuilder();
            for (Message m : unread) {
                if (lines.length() > 0) lines.append('\n');
                if (group && m.senderName != null) lines.append(MessageReader.firstName(m.senderName)).append(": ");
                lines.append(text(m));
            }
            b.setContentTitle(title).setContentText(text(latest))
                    .setStyle(new Notification.BigTextStyle().bigText(lines));
        }

        // What the lock screen shows when the phone hides private content.
        b.setPublicVersion(new Notification.Builder(context, channelFor(context, threadId, silent))
                .setSmallIcon(R.drawable.ic_message)
                .setColor(color)
                .setContentTitle(summary)
                .build());

        // Buttons, as set in "Define notification actions".
        for (String key : new String[]{Prefs.BUTTON_1, Prefs.BUTTON_2, Prefs.BUTTON_3}) {
            Notification.Action action = action(context, threadId, recipients, ChatPrefs.string(context, threadId, key), group);
            if (action != null) b.addAction(action);
        }
        // A one-time code gets its own copy button, like Textra's "Copy 123456".
        String code = Otp.find(latest.body);
        if (code != null) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_copy),
                    "Copy " + code, receiverIntent(context, NotifyReceiver.ACTION_COPY, threadId, code)).build());
        }

        // Pop-up over the lock screen.
        if (alert && Screen.locked(context)
                && ChatPrefs.integer(context, threadId, Prefs.HEADSUP_LOCKED) == Prefs.HEADSUP_POPUP
                && canUseFullScreen(context)) {
            b.setFullScreenIntent(PendingIntent.getActivity(context, id,
                    QuickReplyActivity.intent(context, threadId, false),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE), true);
        }

        // Conversation shortcut: lets Android rank it as a conversation and show it as a bubble.
        if (Build.VERSION.SDK_INT >= 30) {
            String shortcut = Shortcuts.pushConversation(context, threadId, chatName, recipients, avatar);
            if (shortcut != null) {
                b.setShortcutId(shortcut);
                Notification.BubbleMetadata bubble = Shortcuts.bubble(context, threadId, avatar);
                if (bubble != null) b.setBubbleMetadata(bubble);
            }
        }

        manager(context).notify(id, b.build());
        if (alert) {
            Screen.wakeIfWanted(context, threadId);
            scheduleRepeat(context, threadId, ChatPrefs.integer(context, threadId, Prefs.REPEAT));
        }
    }

    private static int countUnread(MessageReader reader, long threadId, int shown) {
        if (shown < MAX_LINES) return shown;
        return Math.max(shown, reader.unreadIn(threadId, 999).size());
    }

    private static String text(Message m) {
        return m.preview().isEmpty() ? "New message" : m.preview();
    }

    private static PendingIntent tapIntent(Context context, long threadId) {
        String tap = ChatPrefs.string(context, threadId, Prefs.TAP_OPENS);
        int id = (int) threadId;
        if (Prefs.TAP_QUICK_REPLY.equals(tap) || Prefs.TAP_QUICK_REPLY_NO_KEYBOARD.equals(tap)) {
            return PendingIntent.getActivity(context, id,
                    QuickReplyActivity.intent(context, threadId, Prefs.TAP_QUICK_REPLY.equals(tap)),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return openIntent(context, threadId);
    }

    static PendingIntent openIntent(Context context, long threadId) {
        Intent open = new Intent(context, ThreadActivity.class)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        return TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(open)
                .getPendingIntent((int) threadId, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action action(Context context, long threadId, List<String> recipients,
                                              String kind, boolean group) {
        if (kind == null) return null;
        switch (kind) {
            case NotifyActions.REPLY: {
                // The reply text gets filled in by the system, so this PendingIntent must be mutable.
                int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                Intent reply = new Intent(context, NotifyReceiver.class)
                        .setAction(NotifyReceiver.ACTION_REPLY)
                        .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                        .putExtra(ThreadActivity.EXTRA_ADDRESSES, recipients.toArray(new String[0]));
                PendingIntent pi = PendingIntent.getBroadcast(context, (int) threadId, reply,
                        PendingIntent.FLAG_UPDATE_CURRENT | mutable);
                RemoteInput input = new RemoteInput.Builder(NotifyReceiver.KEY_TEXT)
                        .setLabel(context.getString(R.string.reply))
                        .setChoices(SmartReplies.cached(threadId))
                        .build();
                Notification.Action.Builder b = new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_send), context.getString(R.string.reply), pi)
                        .addRemoteInput(input)
                        .setAllowGeneratedReplies(true);
                if (Build.VERSION.SDK_INT >= 28) b.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY);
                return b.build();
            }
            case NotifyActions.QUICK_REPLY: {
                PendingIntent pi = PendingIntent.getActivity(context, (int) threadId,
                        QuickReplyActivity.intent(context, threadId, true),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                return new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_send),
                        NotifyActions.label(kind), pi).build();
            }
            case NotifyActions.MARK_READ: {
                Notification.Action.Builder b = new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_message), NotifyActions.label(kind),
                        receiverIntent(context, NotifyReceiver.ACTION_MARK_READ, threadId, null));
                if (Build.VERSION.SDK_INT >= 28) b.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);
                if (Build.VERSION.SDK_INT >= 29) b.setContextual(false);
                return b.build();
            }
            case NotifyActions.MARK_UNREAD: {
                Notification.Action.Builder b = new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_message), NotifyActions.label(kind),
                        receiverIntent(context, NotifyReceiver.ACTION_LEAVE_UNREAD, threadId, null));
                if (Build.VERSION.SDK_INT >= 28) b.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD);
                return b.build();
            }
            case NotifyActions.CALL:
                if (group || recipients.isEmpty()) return null;
                return new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_call),
                        NotifyActions.label(kind),
                        receiverIntent(context, NotifyReceiver.ACTION_CALL, threadId, recipients.get(0))).build();
            case NotifyActions.DELETE_LAST: {
                Notification.Action.Builder b = new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_delete), NotifyActions.label(kind),
                        receiverIntent(context, NotifyReceiver.ACTION_DELETE_LAST, threadId, null));
                if (Build.VERSION.SDK_INT >= 28) b.setSemanticAction(Notification.Action.SEMANTIC_ACTION_DELETE);
                return b.build();
            }
            case NotifyActions.BLOCK:
                if (group || recipients.isEmpty()) return null;
                return new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_block),
                        NotifyActions.label(kind),
                        receiverIntent(context, NotifyReceiver.ACTION_BLOCK, threadId, recipients.get(0))).build();
            case NotifyActions.OPEN:
                return new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_message),
                        NotifyActions.label(kind), openIntent(context, threadId)).build();
            default:
                return null;
        }
    }

    static PendingIntent receiverIntent(Context context, String action, long threadId, String extra) {
        Intent intent = new Intent(context, NotifyReceiver.class)
                .setAction(action)
                .setData(Uri.parse("thread:" + threadId + "/" + action))
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        if (extra != null) intent.putExtra(NotifyReceiver.EXTRA_VALUE, extra);
        return PendingIntent.getBroadcast(context, (int) threadId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------------------- repeats

    /** Alerts again every 2 minutes, count times, until the chat is read or the notice cleared. */
    static void scheduleRepeat(Context context, long threadId, int count) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent pi = repeatIntent(context, threadId, count);
        alarms.cancel(pi);
        if (count <= 0) return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + REPEAT_INTERVAL, pi);
    }

    private static PendingIntent repeatIntent(Context context, long threadId, int remaining) {
        Intent intent = new Intent(context, NotifyReceiver.class)
                .setAction(NotifyReceiver.ACTION_REPEAT)
                .setData(Uri.parse("repeat:" + threadId))
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(NotifyReceiver.EXTRA_REMAINING, remaining);
        return PendingIntent.getBroadcast(context, (int) threadId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelRepeat(Context context, long threadId) {
        context.getSystemService(AlarmManager.class).cancel(repeatIntent(context, threadId, 0));
    }

    // ---------------------------------------------------------------- channels

    /**
     * Android fixes a channel's sound, vibration and light once it exists, so each combination of
     * settings gets its own channel. Chats with default settings share the main one.
     */
    static String channelFor(Context context, long threadId, boolean silent) {
        NotificationManager nm = manager(context);
        ensureGroup(context);
        if (silent) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_SILENT, "Unknown senders (silent)",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setGroup(GROUP_INCOMING);
            ch.setShowBadge(Prefs.bool(context, Prefs.BADGE));
            nm.createNotificationChannel(ch);
            return CHANNEL_SILENT;
        }
        String sound = ChatPrefs.string(context, threadId, Prefs.SOUND);
        String vibrate = ChatPrefs.string(context, threadId, Prefs.VIBRATE);
        int led = ChatPrefs.integer(context, threadId, Prefs.LED);
        int headsup = ChatPrefs.integer(context, threadId, Prefs.HEADSUP);
        boolean badge = Prefs.bool(context, Prefs.BADGE);
        long[] pattern = Vibes.pattern(context, vibrate);
        String patternKey = pattern == null ? "d" : java.util.Arrays.toString(pattern);
        String key = sound + "|" + patternKey + "|" + led + "|" + headsup + "|" + badge;
        boolean isDefault = "default".equals(sound) && pattern == null && led == 0xFFFFFFFF
                && headsup == Prefs.HEADSUP_ANDROID && badge;
        String id = isDefault ? "messages" : "m." + Integer.toHexString(key.hashCode());

        if (nm.getNotificationChannel(id) == null) {
            // Our pop-up replaces Android's heads-up, so that channel must not peek.
            int importance = headsup == Prefs.HEADSUP_ANDROID ? NotificationManager.IMPORTANCE_HIGH
                    : NotificationManager.IMPORTANCE_DEFAULT;
            String name = isDefault ? "Messages" : "Messages (" + describe(context, sound, vibrate, led, headsup) + ")";
            NotificationChannel ch = new NotificationChannel(id, name, importance);
            ch.setGroup(GROUP_INCOMING);
            ch.setShowBadge(badge);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            if ("".equals(sound)) ch.setSound(null, null);
            else if (!"default".equals(sound)) ch.setSound(Uri.parse(sound), attrs);
            else ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs);
            if (pattern != null) {
                if (pattern.length == 0) {
                    ch.enableVibration(false);
                } else {
                    ch.enableVibration(true);
                    ch.setVibrationPattern(pattern);
                }
            } else {
                ch.enableVibration(true);
            }
            if (led == 0) {
                ch.enableLights(false);
            } else {
                ch.enableLights(true);
                ch.setLightColor(led);
            }
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    private static String describe(Context context, String sound, String vibrate, int led, int headsup) {
        StringBuilder sb = new StringBuilder();
        if ("".equals(sound)) sb.append("silent");
        else if (!"default".equals(sound)) sb.append(Sounds.title(context, sound));
        if (!Vibes.DEFAULT.equals(vibrate)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Vibes.label(context, vibrate).toLowerCase());
        }
        if (headsup != Prefs.HEADSUP_ANDROID) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("no heads-up");
        }
        if (sb.length() == 0) sb.append("custom");
        return sb.toString();
    }

    private static void ensureGroup(Context context) {
        manager(context).createNotificationChannelGroup(
                new NotificationChannelGroup(GROUP_INCOMING, context.getString(R.string.incoming_messages)));
    }

    private static String otherChannel(Context context) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_OTHER, "Failed and other",
                NotificationManager.IMPORTANCE_DEFAULT);
        manager(context).createNotificationChannel(ch);
        return CHANNEL_OTHER;
    }

    // ---------------------------------------------------------------- failures

    /** A text didn't go out and the chat isn't open. Tapping opens the chat to resend. */
    static void showSendFailed(Context context, long threadId, String address) {
        if (!canNotify(context)) return;
        String name = new MessageReader(context).lookupName(address);
        Notification n = new Notification.Builder(context, otherChannel(context))
                .setSmallIcon(R.drawable.ic_message)
                .setColor(context.getColor(R.color.failed))
                .setContentTitle("Failed: " + name)
                .setContentText("Tap to open the chat and send it again")
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, threadId))
                .build();
        manager(context).notify(FAILED_BASE + (int) threadId, n);
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
        Notification notification = new Notification.Builder(context, otherChannel(context))
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

    // ---------------------------------------------------------------- Quick Compose

    /** The always-there "Tap to compose a new message" notification, when turned on. */
    static void updateQuickCompose(Context context) {
        NotificationManager nm = manager(context);
        if (!Prefs.bool(context, Prefs.QUICK_COMPOSE) || !canNotify(context)) {
            nm.cancel(COMPOSE_ID);
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_COMPOSE, "Quick Compose",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent compose = PendingIntent.getActivity(context, COMPOSE_ID,
                new Intent(context, NewMessageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL_COMPOSE)
                .setSmallIcon(R.drawable.ic_add)
                .setColor(Prefs.accent(context))
                .setContentTitle("Quick Compose")
                .setContentText("Tap to compose a new message")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(compose)
                .build();
        nm.notify(COMPOSE_ID, n);
    }

    // ---------------------------------------------------------------- helpers

    public static void cancel(Context context, long threadId) {
        manager(context).cancel((int) threadId);
        manager(context).cancel(FAILED_BASE + (int) threadId);
        cancelRepeat(context, threadId);
        Popup.dismiss(threadId);
    }

    /** Clears every chat's notification, after "Mark all as read". */
    static void cancelAllMessages(Context context) {
        for (android.service.notification.StatusBarNotification n : manager(context).getActiveNotifications()) {
            if (n.getId() >= 0 && n.getId() < FAILED_BASE) {
                manager(context).cancel(n.getId());
                cancelRepeat(context, n.getId());
            }
        }
    }

    static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean canUseFullScreen(Context context) {
        return Build.VERSION.SDK_INT < 34 || manager(context).canUseFullScreenIntent();
    }

    static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    static boolean isEmpty(String s) {
        return TextUtils.isEmpty(s);
    }
}
