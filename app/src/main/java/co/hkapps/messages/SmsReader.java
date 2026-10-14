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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads messages straight from the Android SMS content provider. Call off the main thread. */
public class SmsReader {

    private static final String[] PROJECTION = {
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
    };

    // Drafts have no real message in them, so every query leaves them out.
    private static final String NOT_DRAFT =
            Telephony.Sms.TYPE + " != " + Telephony.Sms.MESSAGE_TYPE_DRAFT;

    private static final int SEARCH_LIMIT = 300;

    private final Context context;
    private final ContentResolver resolver;
    private final Map<String, String> nameCache = new HashMap<>();

    public SmsReader(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    /** Latest message of every conversation, newest first, with its unread count. */
    public List<Sms> loadConversations() {
        Map<Long, Sms> latest = new LinkedHashMap<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, NOT_DRAFT, null,
                Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    Sms sms = fromCursor(c);
                    Sms top = latest.get(sms.threadId);
                    if (top == null) {
                        top = sms;
                        latest.put(sms.threadId, sms);
                    }
                    if (sms.incoming() && !sms.read) top.unreadCount++;
                }
            } finally {
                c.close();
            }
        }
        List<Sms> result = new ArrayList<>(latest.values());
        for (Sms sms : result) {
            sms.displayName = lookupName(sms.address);
        }
        return result;
    }

    /** All messages in one conversation, oldest first. */
    public List<Sms> loadThread(long threadId) {
        return query(Telephony.Sms.THREAD_ID + " = ? AND " + NOT_DRAFT,
                new String[]{String.valueOf(threadId)}, Telephony.Sms.DATE + " ASC", Integer.MAX_VALUE);
    }

    /** Messages whose text contains the query, newest first. */
    public List<Sms> search(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Sms> result = query(Telephony.Sms.BODY + " LIKE ? ESCAPE '\\' AND " + NOT_DRAFT,
                new String[]{"%" + escaped + "%"}, Telephony.Sms.DATE + " DESC", SEARCH_LIMIT);
        for (Sms sms : result) {
            sms.displayName = lookupName(sms.address);
        }
        return result;
    }

    /** Phone number of a conversation, taken from its newest message. */
    public String addressOfThread(long threadId) {
        List<Sms> rows = query(Telephony.Sms.THREAD_ID + " = ?",
                new String[]{String.valueOf(threadId)}, Telephony.Sms.DATE + " DESC", 1);
        return rows.isEmpty() ? null : rows.get(0).address;
    }

    /** Thread id of one message row, or -1. */
    public long threadIdOf(Uri messageUri) {
        if (messageUri == null) return -1;
        Cursor c = resolver.query(messageUri, new String[]{Telephony.Sms.THREAD_ID}, null, null, null);
        if (c == null) return -1;
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }

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

    private List<Sms> query(String selection, String[] args, String order, int limit) {
        List<Sms> result = new ArrayList<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, selection, args, order);
        if (c != null) {
            try {
                while (result.size() < limit && c.moveToNext()) {
                    result.add(fromCursor(c));
                }
            } finally {
                c.close();
            }
        }
        return result;
    }

    private static Sms fromCursor(Cursor c) {
        return new Sms(
                c.getLong(0),
                c.getLong(1),
                c.getString(2),
                c.getString(3),
                c.getLong(4),
                c.getInt(5),
                c.getInt(6) != 0);
    }
}
