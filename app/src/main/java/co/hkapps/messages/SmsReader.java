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
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
    };

    private final Context context;
    private final ContentResolver resolver;
    private final Map<String, String> nameCache = new HashMap<>();

    public SmsReader(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    /** Latest message of every conversation, newest first. */
    public List<Sms> loadConversations() {
        Map<Long, Sms> latest = new LinkedHashMap<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, null, null,
                Telephony.Sms.DATE + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    Sms sms = fromCursor(c);
                    if (!latest.containsKey(sms.threadId)) {
                        latest.put(sms.threadId, sms);
                    } else if (!sms.read && sms.incoming) {
                        // Older unread message: keep the thread marked as unread.
                        Sms top = latest.get(sms.threadId);
                        if (top.read) {
                            latest.put(sms.threadId, new Sms(top.threadId, top.address, top.body,
                                    top.date, top.incoming, false));
                        }
                    }
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
        List<Sms> result = new ArrayList<>();
        Cursor c = resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION,
                Telephony.Sms.THREAD_ID + " = ?", new String[]{String.valueOf(threadId)},
                Telephony.Sms.DATE + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    result.add(fromCursor(c));
                }
            } finally {
                c.close();
            }
        }
        return result;
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

    private static Sms fromCursor(Cursor c) {
        return new Sms(
                c.getLong(0),
                c.getString(1),
                c.getString(2),
                c.getLong(3),
                c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                c.getInt(5) != 0);
    }
}
