package co.hkapps.messages;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chat shortcuts. One shortcut per chat serves three things: the person shows up in other apps'
 * share sheets (direct share), Android treats the notification as a conversation (and can show
 * it as a bubble), and "Add to home screen" pins it.
 */
final class Shortcuts {

    static final String SHARE_CATEGORY = "co.hkapps.messages.SHARE_TARGET";

    private Shortcuts() {}

    static String idFor(long threadId) {
        return "chat_" + threadId;
    }

    private static ShortcutInfo build(Context c, long threadId, String name, List<String> recipients, Bitmap avatar) {
        Intent open = new Intent(c, ThreadActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId);
        Set<String> categories = new HashSet<>();
        categories.add(SHARE_CATEGORY);
        ShortcutInfo.Builder b = new ShortcutInfo.Builder(c, idFor(threadId))
                .setShortLabel(name == null || name.isEmpty() ? "Chat" : name)
                .setLongLabel(name == null || name.isEmpty() ? "Chat" : name)
                .setIntent(open)
                .setCategories(categories);
        if (avatar != null) b.setIcon(Icon.createWithAdaptiveBitmap(padForAdaptive(avatar)));
        else b.setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher));
        if (Build.VERSION.SDK_INT >= 29) {
            b.setLongLived(true);
            if (recipients.size() == 1) {
                b.setPerson(new Person.Builder().setName(name).setKey(recipients.get(0)).build());
            }
        }
        return b.build();
    }

    /** Adds or refreshes a chat's shortcut. Returns its id, or null when Android refuses. */
    static String pushConversation(Context c, long threadId, String name, List<String> recipients, Bitmap avatar) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return null;
            ShortcutInfo info = build(c, threadId, name, recipients, avatar);
            if (Build.VERSION.SDK_INT >= 30) sm.pushDynamicShortcut(info);
            else sm.addDynamicShortcuts(Collections.singletonList(info));
            return info.getId();
        } catch (RuntimeException e) {
            // Rate limited or too many shortcuts.
            return null;
        }
    }

    static Notification.BubbleMetadata bubble(Context c, long threadId, Bitmap avatar) {
        if (Build.VERSION.SDK_INT < 30) return null;
        Intent intent = new Intent(c, ThreadActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(ThreadActivity.EXTRA_BUBBLE, true);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(c, (int) threadId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        Icon icon = avatar != null ? Icon.createWithAdaptiveBitmap(padForAdaptive(avatar))
                : Icon.createWithResource(c, R.mipmap.ic_launcher);
        return new Notification.BubbleMetadata.Builder(pi, icon)
                .setDesiredHeight((int) Format.dp(c, 600))
                .setSuppressNotification(false)
                .build();
    }

    /** "Add to home screen" for a chat. */
    static boolean pin(Context c, long threadId, String name, List<String> recipients, Bitmap avatar) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        return sm.requestPinShortcut(build(c, threadId, name, recipients, avatar), null);
    }

    static void remove(Context c, long threadId) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            sm.removeDynamicShortcuts(Collections.singletonList(idFor(threadId)));
            if (Build.VERSION.SDK_INT >= 30) sm.removeLongLivedShortcuts(Collections.singletonList(idFor(threadId)));
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    /** Adaptive icons crop the outer third, so shrink the round avatar into the safe zone. */
    private static Bitmap padForAdaptive(Bitmap avatar) {
        int size = avatar.getWidth();
        int full = (int) (size * 1.5f);
        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(Look.avatarColor(String.valueOf(size)) & 0x00FFFFFF);
        canvas.drawBitmap(avatar, (full - size) / 2f, (full - size) / 2f, null);
        return out;
    }
}
