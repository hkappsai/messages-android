package co.hkapps.messages;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads SMS and MMS from the Android message providers. Call off the main thread. */
public class MessageReader {

    private static final String[] SMS_PROJECTION = {
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.STATUS,
            Telephony.Sms.LOCKED,
            Telephony.Sms.DATE_SENT,
    };
    private static final String SMS_NOT_DRAFT =
            Telephony.Sms.TYPE + " != " + Telephony.Sms.MESSAGE_TYPE_DRAFT;

    private static final String[] MMS_PROJECTION = {
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.LOCKED,
            Telephony.Mms.STATUS,
            Telephony.Mms.MESSAGE_SIZE,
            Telephony.Mms.DATE_SENT,
            Telephony.Mms.DELIVERY_REPORT,
    };
    // 128 = sent message, 132 = received message. Leaves out drafts and download notices.
    private static final String MMS_REAL = Telephony.Mms.MESSAGE_TYPE + " IN (128, 132) AND "
            + Telephony.Mms.MESSAGE_BOX + " != " + Telephony.Mms.MESSAGE_BOX_DRAFTS;

    static final Uri MMS_PART_URI = Uri.parse("content://mms/part");
    private static final Uri CANONICAL_ADDRESSES = Uri.parse("content://mms-sms/canonical-addresses");
    private static final int ADDR_FROM = 137;
    private static final int SEARCH_LIMIT = 300;

    private final Context context;
    private final ContentResolver resolver;
    private final Map<String, String> nameCache = new HashMap<>();

    public MessageReader(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    // ------------------------------------------------------------ conversations

    /** Every conversation with its newest message, newest first. */
    public List<Conversation> loadConversations() {
        Map<Long, Message> latest = new HashMap<>();
        Map<Long, Integer> unread = new HashMap<>();

        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION, SMS_NOT_DRAFT, null,
                Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    Message m = smsFromCursor(c);
                    if (!latest.containsKey(m.threadId)) latest.put(m.threadId, m);
                    if (m.incoming() && !m.read) unread.merge(m.threadId, 1, Integer::sum);
                }
            } finally {
                c.close();
            }
        }

        List<Message> newestMms = new ArrayList<>();
        c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, MMS_REAL, null, Telephony.Mms.DATE + " DESC");
        if (c != null) {
            Map<Long, Boolean> seen = new HashMap<>();
            try {
                while (c.moveToNext()) {
                    Message m = mmsFromCursor(c, null, "");
                    if (seen.put(m.threadId, true) == null) {
                        Message sms = latest.get(m.threadId);
                        if (sms == null || m.date > sms.date) newestMms.add(m);
                    }
                    if (m.incoming() && !m.read) unread.merge(m.threadId, 1, Integer::sum);
                }
            } finally {
                c.close();
            }
        }
        // MMS rows hold no text themselves; fetch the parts only for the rows on screen.
        for (Message m : fillMms(newestMms)) latest.put(m.threadId, m);

        Map<Long, List<String>> recipients = threadRecipients();
        List<Conversation> result = new ArrayList<>();
        for (Message m : latest.values()) {
            List<String> addresses = recipients.get(m.threadId);
            if (addresses == null || addresses.isEmpty()) {
                addresses = Collections.singletonList(m.address);
            }
            Integer count = unread.get(m.threadId);
            if (addresses.size() > 1 && m.incoming()) m.senderName = firstName(lookupName(m.address));
            result.add(new Conversation(m.threadId, addresses, names(addresses), m, count == null ? 0 : count));
        }
        Collections.sort(result, (a, b) -> Long.compare(b.latest.date, a.latest.date));
        return result;
    }

    /** All messages in one conversation, oldest first. */
    public List<Message> loadThread(long threadId) {
        String[] args = {String.valueOf(threadId)};
        List<Message> result = new ArrayList<>();

        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                Telephony.Sms.THREAD_ID + " = ? AND " + SMS_NOT_DRAFT, args, null);
        if (c != null) {
            try {
                while (c.moveToNext()) result.add(smsFromCursor(c));
            } finally {
                c.close();
            }
        }

        List<Message> mms = new ArrayList<>();
        c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION,
                Telephony.Mms.THREAD_ID + " = ? AND " + MMS_REAL, args, null);
        if (c != null) {
            try {
                while (c.moveToNext()) mms.add(mmsFromCursor(c, null, ""));
            } finally {
                c.close();
            }
        }
        result.addAll(fillMms(mms));

        boolean group = recipientsOf(threadId).size() > 1;
        if (group) {
            for (Message m : result) {
                if (m.incoming()) m.senderName = lookupName(m.address);
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.date, b.date));
        return result;
    }

    /** Messages whose text contains the query, newest first. */
    public List<Message> search(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String[] args = {"%" + escaped + "%"};
        List<Message> result = new ArrayList<>();

        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                Telephony.Sms.BODY + " LIKE ? ESCAPE '\\' AND " + SMS_NOT_DRAFT, args,
                Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext() && result.size() < SEARCH_LIMIT) result.add(smsFromCursor(c));
            } finally {
                c.close();
            }
        }

        // MMS text lives in its parts: find matching parts, then their messages.
        List<String> ids = new ArrayList<>();
        c = query(MMS_PART_URI, new String[]{Telephony.Mms.Part.MSG_ID},
                Telephony.Mms.Part.CONTENT_TYPE + " = 'text/plain' AND "
                        + Telephony.Mms.Part.TEXT + " LIKE ? ESCAPE '\\'", args, null);
        if (c != null) {
            try {
                while (c.moveToNext() && ids.size() < SEARCH_LIMIT) ids.add(String.valueOf(c.getLong(0)));
            } finally {
                c.close();
            }
        }
        if (!ids.isEmpty()) {
            List<Message> mms = new ArrayList<>();
            c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION,
                    Telephony.Mms._ID + " IN (" + TextUtils.join(",", ids) + ") AND " + MMS_REAL, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) mms.add(mmsFromCursor(c, null, ""));
                } finally {
                    c.close();
                }
            }
            result.addAll(fillMms(mms));
        }

        Collections.sort(result, (a, b) -> Long.compare(b.date, a.date));
        for (Message m : result) {
            String who = m.address.isEmpty() ? names(recipientsOf(m.threadId)) : lookupName(m.address);
            m.senderName = who;
        }
        return result;
    }

    /** Locked messages in every chat, newest first. */
    public List<Message> locked() {
        List<Message> result = new ArrayList<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                Telephony.Sms.LOCKED + " = 1 AND " + SMS_NOT_DRAFT, null, Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) result.add(smsFromCursor(c));
            } finally {
                c.close();
            }
        }
        List<Message> mms = new ArrayList<>();
        c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, Telephony.Mms.LOCKED + " = 1 AND " + MMS_REAL,
                null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) mms.add(mmsFromCursor(c, null, ""));
            } finally {
                c.close();
            }
        }
        result.addAll(fillMms(mms));
        Collections.sort(result, (a, b) -> Long.compare(b.date, a.date));
        for (Message m : result) {
            m.senderName = m.address.isEmpty() ? names(recipientsOf(m.threadId)) : lookupName(m.address);
        }
        return result;
    }

    /** Received messages not read yet in one chat, oldest first, at most limit of them. */
    public List<Message> unreadIn(long threadId, int limit) {
        String[] args = {String.valueOf(threadId)};
        List<Message> result = new ArrayList<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.READ + " = 0 AND "
                        + Telephony.Sms.TYPE + " = " + Telephony.Sms.MESSAGE_TYPE_INBOX,
                args, Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext() && result.size() < limit) result.add(smsFromCursor(c));
            } finally {
                c.close();
            }
        }
        List<Message> mms = new ArrayList<>();
        c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, Telephony.Mms.THREAD_ID + " = ? AND "
                + Telephony.Mms.READ + " = 0 AND " + Telephony.Mms.MESSAGE_BOX + " = "
                + Telephony.Mms.MESSAGE_BOX_INBOX + " AND " + MMS_REAL, args, Telephony.Mms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext() && mms.size() < limit) mms.add(mmsFromCursor(c, null, ""));
            } finally {
                c.close();
            }
        }
        result.addAll(fillMms(mms));
        Collections.sort(result, (a, b) -> Long.compare(a.date, b.date));
        while (result.size() > limit) result.remove(0);
        for (Message m : result) m.senderName = lookupName(m.address);
        return result;
    }

    /** Pictures, videos and other files in a chat, newest first. */
    public List<Message> media(long threadId) {
        List<Message> mms = new ArrayList<>();
        Cursor c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION,
                Telephony.Mms.THREAD_ID + " = ? AND " + MMS_REAL, new String[]{String.valueOf(threadId)},
                Telephony.Mms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) mms.add(mmsFromCursor(c, null, ""));
            } finally {
                c.close();
            }
        }
        List<Message> result = new ArrayList<>();
        for (Message m : fillMms(mms)) {
            if (!m.parts.isEmpty()) result.add(m);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.date, a.date));
        return result;
    }

    /** True when the number or sender id belongs to someone in the address book. */
    public boolean isContact(String address) {
        if (TextUtils.isEmpty(address) || context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return false;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        try (Cursor c = resolver.query(uri, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One message by provider id, with its parts. */
    public Message message(boolean mms, long id) {
        if (!mms) {
            try (Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                    Telephony.Sms._ID + " = " + id, null, null)) {
                return c != null && c.moveToFirst() ? smsFromCursor(c) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        List<Message> rows = new ArrayList<>();
        Cursor c = query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, Telephony.Mms._ID + " = " + id, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) rows.add(mmsFromCursor(c, null, ""));
            } finally {
                c.close();
            }
        }
        List<Message> full = fillMms(rows);
        return full.isEmpty() ? null : full.get(0);
    }

    // ------------------------------------------------------------ recipients

    /** Phone numbers in a conversation (more than one for a group). */
    public List<String> recipientsOf(long threadId) {
        List<String> list = threadRecipients().get(threadId);
        if (list != null && !list.isEmpty()) return list;
        // Fallback for providers without the threads table: the newest SMS address.
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, new String[]{Telephony.Sms.ADDRESS},
                Telephony.Sms.THREAD_ID + " = ?", new String[]{String.valueOf(threadId)},
                Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                if (c.moveToFirst() && c.getString(0) != null) return Collections.singletonList(c.getString(0));
            } finally {
                c.close();
            }
        }
        return Collections.emptyList();
    }

    /** thread id -> phone numbers, from the threads table and its address book. */
    private Map<Long, List<String>> threadRecipients() {
        Map<Long, String> canonical = new HashMap<>();
        Cursor c = query(CANONICAL_ADDRESSES, new String[]{"_id", "address"}, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) canonical.put(c.getLong(0), c.getString(1));
            } finally {
                c.close();
            }
        }
        Map<Long, List<String>> result = new HashMap<>();
        Uri threads = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();
        c = query(threads, new String[]{Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS}, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String ids = c.getString(1);
                    if (TextUtils.isEmpty(ids)) continue;
                    List<String> addresses = new ArrayList<>();
                    for (String id : ids.trim().split("\\s+")) {
                        try {
                            String a = canonical.get(Long.parseLong(id));
                            if (!TextUtils.isEmpty(a)) addresses.add(a);
                        } catch (NumberFormatException ignored) {
                            // Skip malformed ids.
                        }
                    }
                    result.put(c.getLong(0), addresses);
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    // ------------------------------------------------------------ MMS details

    /** Adds text, attachments and sender to MMS rows, in a few batched queries. */
    private List<Message> fillMms(List<Message> rows) {
        if (rows.isEmpty()) return rows;
        Map<Long, Message> byId = new LinkedHashMap<>();
        for (Message m : rows) byId.put(m.id, m);

        Map<Long, StringBuilder> text = new HashMap<>();
        Map<Long, List<Part>> parts = new HashMap<>();
        List<Long> ids = new ArrayList<>(byId.keySet());
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            Cursor c = query(MMS_PART_URI, new String[]{
                            Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE,
                            Telephony.Mms.Part.NAME, Telephony.Mms.Part.CONTENT_LOCATION, Telephony.Mms.Part.TEXT},
                    Telephony.Mms.Part.MSG_ID + " IN (" + TextUtils.join(",", chunk) + ")", null,
                    Telephony.Mms.Part.SEQ + " ASC");
            if (c == null) continue;
            try {
                while (c.moveToNext()) {
                    long partId = c.getLong(0);
                    long mid = c.getLong(1);
                    String type = c.getString(2) == null ? "" : c.getString(2).toLowerCase();
                    if (type.equals("application/smil")) continue;
                    if (type.equals("text/plain")) {
                        String t = c.getString(5);
                        if (t == null) t = readText(partId);
                        StringBuilder sb = text.get(mid);
                        if (sb == null) text.put(mid, sb = new StringBuilder());
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(t);
                    } else {
                        String name = c.getString(3) != null ? c.getString(3) : c.getString(4);
                        List<Part> list = parts.get(mid);
                        if (list == null) parts.put(mid, list = new ArrayList<>());
                        list.add(new Part(partId, type, name));
                    }
                }
            } finally {
                c.close();
            }
        }

        List<Message> result = new ArrayList<>();
        for (Message row : byId.values()) {
            StringBuilder sb = text.get(row.id);
            String sender = row.incoming() ? senderOf(row.id) : "";
            Message full = new Message(row.id, true, row.threadId, sender,
                    sb == null ? "" : sb.toString(), row.date, row.type, row.read);
            full.subId = row.subId;
            full.locked = row.locked;
            full.status = row.status;
            full.size = row.size;
            full.dateSent = row.dateSent;
            List<Part> p = parts.get(row.id);
            if (p != null) full.parts.addAll(p);
            result.add(full);
        }
        return result;
    }

    private String senderOf(long mmsId) {
        Uri uri = Uri.parse("content://mms/" + mmsId + "/addr");
        Cursor c = query(uri, new String[]{Telephony.Mms.Addr.ADDRESS},
                Telephony.Mms.Addr.TYPE + " = " + ADDR_FROM, null, null);
        if (c == null) return "";
        try {
            return c.moveToFirst() && c.getString(0) != null ? MmsPdu.fromWireAddress(c.getString(0)) : "";
        } finally {
            c.close();
        }
    }

    /** Some phones keep MMS text in a file instead of the text column. */
    private String readText(long partId) {
        try (InputStream in = resolver.openInputStream(Uri.parse("content://mms/part/" + partId))) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Thread id of one saved message, or -1. */
    public long threadIdOf(Uri messageUri) {
        if (messageUri == null) return -1;
        Cursor c = query(messageUri, new String[]{"thread_id"}, null, null, null);
        if (c == null) return -1;
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------ names

    public String lookupName(String address) {
        if (TextUtils.isEmpty(address)) return "Unknown";
        if (nameCache.containsKey(address)) return nameCache.get(address);

        String name = address;
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address));
            Cursor c = null;
            try {
                c = resolver.query(uri,
                        new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst() && !TextUtils.isEmpty(c.getString(0))) {
                    name = c.getString(0);
                }
            } catch (IllegalArgumentException ignored) {
                // Some sender IDs (like "BANK-ALERT") are not valid phone lookups.
            } finally {
                if (c != null) c.close();
            }
        }
        nameCache.put(address, name);
        return name;
    }

    /** "Alice" for one person, "Alice, Bob and 2 others" for a group. */
    public String names(List<String> addresses) {
        if (addresses.isEmpty()) return "Unknown";
        if (addresses.size() == 1) return lookupName(addresses.get(0));
        List<String> first = new ArrayList<>();
        for (int i = 0; i < Math.min(3, addresses.size()); i++) first.add(firstName(lookupName(addresses.get(i))));
        String joined = TextUtils.join(", ", first);
        int more = addresses.size() - first.size();
        return more > 0 ? joined + " and " + more + (more == 1 ? " other" : " others") : joined;
    }

    static String firstName(String name) {
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) return name;
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    // ------------------------------------------------------------ cursors

    private Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            return resolver.query(uri, projection, selection, args, order);
        } catch (RuntimeException e) {
            // Some phones lack parts of the MMS provider; treat that as "no rows".
            return null;
        }
    }

    private static Message smsFromCursor(Cursor c) {
        Message m = new Message(c.getLong(0), false, c.getLong(1), c.getString(2), c.getString(3),
                c.getLong(4), c.getInt(5), c.getInt(6) != 0);
        m.subId = c.isNull(7) ? -1 : c.getInt(7);
        // -1 means no report was asked for; 0 delivered; 32 waiting; 64 and up failed.
        int st = c.isNull(8) ? -1 : c.getInt(8);
        m.status = st < 0 ? Message.STATUS_NONE : st >= 64 ? Message.STATUS_FAILED
                : st >= 32 ? Message.STATUS_PENDING : Message.STATUS_DELIVERED;
        m.locked = c.getInt(9) != 0;
        m.dateSent = c.getLong(10);
        return m;
    }

    /** MMS dates are in seconds; everything else in this app uses milliseconds. */
    private static Message mmsFromCursor(Cursor c, String address, String body) {
        Message m = new Message(c.getLong(0), true, c.getLong(1), address, body,
                c.getLong(2) * 1000, c.getInt(3), c.getInt(4) != 0);
        m.subId = c.isNull(5) ? -1 : c.getInt(5);
        m.locked = c.getInt(6) != 0;
        m.size = c.getLong(8);
        m.dateSent = c.getLong(9) * 1000;
        // Delivery reports for sent MMS: asked for (d_rpt = yes), then the report's status.
        boolean asked = !c.isNull(10) && c.getInt(10) == 0x80;
        int st = c.isNull(7) ? 0 : c.getInt(7);
        if (asked && m.type == Telephony.Mms.MESSAGE_BOX_SENT) {
            m.status = st == MmsPdu.STATUS_RETRIEVED ? Message.STATUS_DELIVERED
                    : st == MmsPdu.STATUS_EXPIRED || st == MmsPdu.STATUS_REJECTED ? Message.STATUS_FAILED
                    : Message.STATUS_PENDING;
        }
        return m;
    }
}
