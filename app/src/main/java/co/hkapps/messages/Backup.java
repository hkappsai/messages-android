package co.hkapps.messages;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backup to a file the user picks, and restore from it: settings, per-chat settings, signatures,
 * and (optionally) every SMS and MMS. Streams JSON so big message stores don't run out of memory.
 */
final class Backup {

    interface Progress {
        void update(String text);
    }

    private static final int FORMAT = 1;

    private Backup() {}

    // ---------------------------------------------------------------- writing

    static int write(Context c, OutputStream out, boolean messages, Progress progress) throws IOException {
        int count = 0;
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.beginObject();
            w.name("format").value(FORMAT);
            w.name("app").value("co.hkapps.messages");
            w.name("created").value(System.currentTimeMillis());

            w.name("settings");
            writePrefs(w, Prefs.sp(c).getAll());
            w.name("chats");
            writePrefs(w, ChatPrefs.all(c));

            w.name("signatures").beginArray();
            for (AppDb.Item s : AppDb.get(c).signatures()) w.value(s.value);
            w.endArray();

            if (messages) {
                progress.update("Backing up texts…");
                w.name("sms").beginArray();
                try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{
                        Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
                        Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.LOCKED, Telephony.Sms.STATUS},
                        Telephony.Sms.TYPE + " != " + Telephony.Sms.MESSAGE_TYPE_DRAFT, null, Telephony.Sms.DATE)) {
                    while (cur != null && cur.moveToNext()) {
                        w.beginObject();
                        w.name("a").value(cur.getString(0));
                        w.name("b").value(cur.getString(1));
                        w.name("d").value(cur.getLong(2));
                        w.name("ds").value(cur.getLong(3));
                        w.name("t").value(cur.getInt(4));
                        w.name("r").value(cur.getInt(5));
                        w.name("l").value(cur.getInt(6));
                        w.name("s").value(cur.getInt(7));
                        w.endObject();
                        if (++count % 500 == 0) progress.update(count + " messages…");
                    }
                }
                w.endArray();

                progress.update("Backing up picture messages…");
                w.name("mms").beginArray();
                List<Long> ids = new ArrayList<>();
                try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                        new String[]{Telephony.Mms._ID}, Telephony.Mms.MESSAGE_TYPE + " IN (128, 132)", null,
                        Telephony.Mms.DATE)) {
                    while (cur != null && cur.moveToNext()) ids.add(cur.getLong(0));
                } catch (RuntimeException ignored) {
                    // Phone without MMS.
                }
                MessageReader reader = new MessageReader(c);
                for (long id : ids) {
                    Message m = reader.message(true, id);
                    if (m == null) continue;
                    w.beginObject();
                    w.name("to").beginArray();
                    for (String r : reader.recipientsOf(m.threadId)) w.value(r);
                    w.endArray();
                    w.name("from").value(m.address);
                    w.name("b").value(m.body);
                    w.name("d").value(m.date);
                    w.name("box").value(m.type);
                    w.name("r").value(m.read ? 1 : 0);
                    w.name("l").value(m.locked ? 1 : 0);
                    w.name("parts").beginArray();
                    for (Part p : m.parts) {
                        byte[] data = Mms.read(c, p.uri);
                        if (data == null) continue;
                        w.beginObject();
                        w.name("ct").value(p.contentType);
                        w.name("n").value(p.name);
                        w.name("data").value(Base64.encodeToString(data, Base64.NO_WRAP));
                        w.endObject();
                    }
                    w.endArray();
                    w.endObject();
                    if (++count % 50 == 0) progress.update(count + " messages…");
                }
                w.endArray();
            }
            w.endObject();
        }
        return count;
    }

    private static void writePrefs(JsonWriter w, Map<String, ?> all) throws IOException {
        w.beginObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            w.name(e.getKey()).beginObject();
            if (v instanceof Boolean) w.name("b").value((Boolean) v);
            else if (v instanceof Integer) w.name("i").value((Integer) v);
            else if (v instanceof Long) w.name("l").value((Long) v);
            else if (v instanceof Set) {
                w.name("set").beginArray();
                for (Object o : (Set<?>) v) w.value(String.valueOf(o));
                w.endArray();
            } else if (v != null) w.name("s").value(v.toString());
            w.endObject();
        }
        w.endObject();
    }

    // ---------------------------------------------------------------- reading

    /** Restores settings always; messages only when asked. Returns messages added. */
    static int restore(Context c, InputStream in, boolean messages, Progress progress) throws IOException {
        int added = 0;
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            Set<String> seen = messages ? existingKeys(c) : new HashSet<>();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "settings":
                        restorePrefs(r, Prefs.sp(c));
                        break;
                    case "chats":
                        restorePrefs(r, c.getSharedPreferences("chats", Context.MODE_PRIVATE));
                        break;
                    case "signatures":
                        r.beginArray();
                        Set<String> have = new HashSet<>();
                        for (AppDb.Item s : AppDb.get(c).signatures()) have.add(s.value);
                        while (r.hasNext()) {
                            String s = r.nextString();
                            if (have.add(s)) AppDb.get(c).saveSignature(-1, s);
                        }
                        r.endArray();
                        break;
                    case "sms":
                        if (!messages) {
                            r.skipValue();
                            break;
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            if (restoreSms(c, r, seen)) added++;
                            if (added > 0 && added % 500 == 0) progress.update(added + " messages…");
                        }
                        r.endArray();
                        break;
                    case "mms":
                        if (!messages) {
                            r.skipValue();
                            break;
                        }
                        r.beginArray();
                        while (r.hasNext()) {
                            if (restoreMms(c, r, seen)) added++;
                            if (added > 0 && added % 50 == 0) progress.update(added + " messages…");
                        }
                        r.endArray();
                        break;
                    default:
                        r.skipValue();
                        break;
                }
            }
            r.endObject();
        }
        return added;
    }

    private static void restorePrefs(JsonReader r, SharedPreferences sp) throws IOException {
        SharedPreferences.Editor e = sp.edit();
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            r.beginObject();
            while (r.hasNext()) {
                String type = r.nextName();
                switch (type) {
                    case "b": e.putBoolean(key, r.nextBoolean()); break;
                    case "i": e.putInt(key, r.nextInt()); break;
                    case "l": e.putLong(key, r.nextLong()); break;
                    case "s": e.putString(key, r.nextString()); break;
                    case "set": {
                        Set<String> set = new HashSet<>();
                        r.beginArray();
                        while (r.hasNext()) set.add(r.nextString());
                        r.endArray();
                        e.putStringSet(key, set);
                        break;
                    }
                    default: r.skipValue(); break;
                }
            }
            r.endObject();
        }
        r.endObject();
        e.apply();
    }

    /** "date|body" of every SMS already on the phone, so restoring twice adds nothing twice. */
    private static Set<String> existingKeys(Context c) {
        Set<String> keys = new HashSet<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.DATE, Telephony.Sms.BODY}, null, null, null)) {
            while (cur != null && cur.moveToNext()) keys.add(cur.getLong(0) + "|" + cur.getString(1));
        } catch (RuntimeException ignored) {
            // No access.
        }
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                new String[]{Telephony.Mms.DATE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) keys.add("mms|" + cur.getLong(0));
        } catch (RuntimeException ignored) {
            // No access.
        }
        return keys;
    }

    private static boolean restoreSms(Context c, JsonReader r, Set<String> seen) throws IOException {
        ContentValues v = new ContentValues();
        String address = null, body = null;
        long date = 0;
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "a": address = r.nextString(); break;
                case "b": body = r.nextString(); break;
                case "d": date = r.nextLong(); break;
                case "ds": v.put(Telephony.Sms.DATE_SENT, r.nextLong()); break;
                case "t": v.put(Telephony.Sms.TYPE, r.nextInt()); break;
                case "r": v.put(Telephony.Sms.READ, r.nextInt()); break;
                case "l": v.put(Telephony.Sms.LOCKED, r.nextInt()); break;
                case "s": v.put(Telephony.Sms.STATUS, r.nextInt()); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        if (address == null || !seen.add(date + "|" + body)) return false;
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, date);
        v.put(Telephony.Sms.SEEN, 1);
        try {
            v.put(Telephony.Sms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(c, address));
            return c.getContentResolver().insert(Telephony.Sms.CONTENT_URI, v) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean restoreMms(Context c, JsonReader r, Set<String> seen) throws IOException {
        List<String> to = new ArrayList<>();
        String from = "", body = "";
        long date = 0;
        int box = Telephony.Mms.MESSAGE_BOX_INBOX, read = 1, locked = 0;
        List<Attachment> files = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "to":
                    r.beginArray();
                    while (r.hasNext()) to.add(r.nextString());
                    r.endArray();
                    break;
                case "from": from = r.nextString(); break;
                case "b": body = r.nextString(); break;
                case "d": date = r.nextLong(); break;
                case "box": box = r.nextInt(); break;
                case "r": read = r.nextInt(); break;
                case "l": locked = r.nextInt(); break;
                case "parts":
                    r.beginArray();
                    while (r.hasNext()) {
                        String ct = "application/octet-stream", n = "file";
                        byte[] data = null;
                        r.beginObject();
                        while (r.hasNext()) {
                            switch (r.nextName()) {
                                case "ct": ct = r.nextString(); break;
                                case "n": n = r.peek() == android.util.JsonToken.NULL ? skipNull(r) : r.nextString(); break;
                                case "data": data = Base64.decode(r.nextString(), Base64.NO_WRAP); break;
                                default: r.skipValue(); break;
                            }
                        }
                        r.endObject();
                        if (data != null) files.add(new Attachment(ct, n, data));
                    }
                    r.endArray();
                    break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        if (to.isEmpty() || !seen.add("mms|" + date / 1000)) return false;
        try {
            Uri row = Mms.saveRestored(c, to, from, body, files, date, box, read == 1);
            if (row != null && locked == 1) {
                ContentValues v = new ContentValues();
                v.put(Telephony.Mms.LOCKED, 1);
                c.getContentResolver().update(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI,
                        ContentUris.parseId(row)), v, null, null);
            }
            return row != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String skipNull(JsonReader r) throws IOException {
        r.nextNull();
        return "file";
    }
}
