package co.hkapps.messages;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Texts set to go out later. Stored in preferences, woken by AlarmManager. */
public final class Scheduler {

    private static final String PREFS = "scheduled";
    private static final String KEY = "items";

    public static final class Item {
        public final long id;
        public final long time;
        public final long threadId;
        public final List<String> recipients;
        public final String text;

        Item(long id, long time, long threadId, List<String> recipients, String text) {
            this.id = id;
            this.time = time;
            this.threadId = threadId;
            this.recipients = recipients;
            this.text = text;
        }
    }

    private Scheduler() {}

    public static synchronized void add(Context context, long time, long threadId, List<String> recipients, String text) {
        List<Item> items = load(context);
        Item item = new Item(System.currentTimeMillis(), time, threadId, recipients, text);
        items.add(item);
        save(context, items);
        arm(context, item);
    }

    public static synchronized void cancel(Context context, long id) {
        List<Item> items = load(context);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                items.remove(i);
                break;
            }
        }
        save(context, items);
        alarms(context).cancel(pendingIntent(context, id));
    }

    public static synchronized List<Item> forThread(Context context, long threadId) {
        List<Item> result = new ArrayList<>();
        for (Item item : load(context)) {
            if (item.threadId == threadId) result.add(item);
        }
        return result;
    }

    /** Removes the item and returns it, so it can't go out twice. */
    static synchronized Item take(Context context, long id) {
        List<Item> items = load(context);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                Item item = items.remove(i);
                save(context, items);
                return item;
            }
        }
        return null;
    }

    /** Sends the item right away. Call off the main thread. */
    static void fire(Context context, long id) {
        Item item = take(context, id);
        if (item != null) Sender.send(context, item.recipients, item.text);
    }

    /** Alarms don't survive a reboot; set them all again. Missed ones fire right away. */
    static synchronized void armAll(Context context) {
        for (Item item : load(context)) arm(context, item);
    }

    /** True when Android lets us wake at the exact minute. Otherwise texts may go a few minutes late. */
    static boolean canBeExact(Context context) {
        return Build.VERSION.SDK_INT < 31 || alarms(context).canScheduleExactAlarms();
    }

    private static void arm(Context context, Item item) {
        PendingIntent pi = pendingIntent(context, item.id);
        AlarmManager alarms = alarms(context);
        if (canBeExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.time, pi);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.time, pi);
        }
    }

    private static PendingIntent pendingIntent(Context context, long id) {
        Intent intent = new Intent(context, ScheduleReceiver.class).setData(Uri.parse("scheduled:" + id));
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static AlarmManager alarms(Context context) {
        return context.getSystemService(AlarmManager.class);
    }

    private static List<Item> load(Context context) {
        List<Item> items = new ArrayList<>();
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                JSONArray to = o.getJSONArray("to");
                List<String> recipients = new ArrayList<>();
                for (int j = 0; j < to.length(); j++) recipients.add(to.getString(j));
                items.add(new Item(o.getLong("id"), o.getLong("time"), o.getLong("thread"),
                        recipients, o.getString("text")));
            }
        } catch (JSONException e) {
            // Corrupt data: start over rather than crash.
        }
        Collections.sort(items, (a, b) -> Long.compare(a.time, b.time));
        return items;
    }

    private static void save(Context context, List<Item> items) {
        JSONArray array = new JSONArray();
        try {
            for (Item item : items) {
                JSONArray to = new JSONArray();
                for (String r : item.recipients) to.put(r);
                array.put(new JSONObject()
                        .put("id", item.id)
                        .put("time", item.time)
                        .put("thread", item.threadId)
                        .put("to", to)
                        .put("text", item.text));
            }
        } catch (JSONException ignored) {
            // put() only throws for NaN numbers, which never happen here.
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit();
    }
}
