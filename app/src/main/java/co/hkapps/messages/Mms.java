package co.hkapps.messages;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Sends and receives MMS (pictures, audio, group texts). The radio work happens in Android's
 * own MMS service through SmsManager; this class builds and reads the message data and saves
 * messages to the provider, which the default SMS app has to do itself.
 */
public final class Mms {

    static final String EXTRA_FILE = "file";
    static final String EXTRA_LOCATION = "location";
    static final String EXTRA_TRANSACTION = "transaction";
    static final String EXTRA_FROM = "from";
    static final String EXTRA_SUB = "sub";

    private static final int ADDR_FROM = 137;
    private static final int ADDR_TO = 151;
    private static final int ADDR_CC = 130;
    private static final int CHARSET_UTF8 = 106;
    private static final int DEFAULT_MAX_SIZE = 300 * 1024;
    private static final String PHONE_PACKAGE = "com.android.phone";

    private Mms() {}

    // ------------------------------------------------------------ sending

    /** Saves the message as "sending" and hands it to the phone's MMS service. */
    public static void send(Context context, List<String> recipients, String text, List<Attachment> files) {
        long threadId = Telephony.Threads.getOrCreateThreadId(context, new HashSet<>(recipients));
        List<MmsPdu.Part> parts = buildParts(text, files);
        String transactionId = "T" + Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString(new Random().nextInt(0xFFFF));

        Uri row = saveOutgoing(context, threadId, recipients, parts, transactionId);
        byte[] pdu = MmsPdu.encodeSendReq(transactionId, recipients, null, parts,
                System.currentTimeMillis() / 1000);

        File file = new File(MmsFileProvider.dir(context, MmsFileProvider.DIR_MMS),
                "send-" + System.nanoTime() + ".pdu");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(pdu);
        } catch (IOException e) {
            markSent(context, row, false);
            return;
        }
        Uri contentUri = MmsFileProvider.uriFor(context, file);
        grant(context, contentUri);

        Intent intent = new Intent(context, MmsResultReceiver.class)
                .setAction(MmsResultReceiver.ACTION_SENT)
                .setData(row != null ? row : Uri.parse("mms-sent:" + file.getName()))
                .putExtra(EXTRA_FILE, file.getPath());
        PendingIntent sent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            SmsWriter.smsManager(context, -1).sendMultimediaMessage(context, contentUri, null, null, sent);
        } catch (RuntimeException e) {
            markSent(context, row, false);
            file.delete();
        }
    }

    /** Sends a failed MMS again with the same text and files. */
    public static void resend(Context context, Message message) {
        List<Attachment> files = new ArrayList<>();
        for (Part p : message.parts) {
            byte[] data = read(context, p.uri);
            if (data != null) files.add(new Attachment(p.contentType, p.name != null ? p.name : "file", data));
        }
        List<String> recipients = new MessageReader(context).recipientsOf(message.threadId);
        if (recipients.isEmpty()) return;
        SmsWriter.deleteMessage(context, message);
        send(context, recipients, message.body, files);
    }

    /** The carrier's MMS size limit in bytes. */
    public static int maxMessageSize(Context context) {
        try {
            Bundle config = SmsWriter.smsManager(context, -1).getCarrierConfigValues();
            int max = config == null ? 0 : config.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0);
            return max > 0 ? max : DEFAULT_MAX_SIZE;
        } catch (RuntimeException e) {
            return DEFAULT_MAX_SIZE;
        }
    }

    /** SMIL layout first (pictures above text, like every phone expects), then files, then text. */
    static List<MmsPdu.Part> buildParts(String text, List<Attachment> files) {
        List<MmsPdu.Part> parts = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int index = 0;
        for (Attachment a : files) {
            String name = fileName(index++, a);
            parts.add(new MmsPdu.Part(a.contentType, name, a.data));
            String tag = a.isImage() ? "img" : a.isAudio() ? "audio" : a.isVideo() ? "video" : "ref";
            String region = a.isImage() || a.isVideo() ? " region=\"Image\"" : "";
            body.append("<par dur=\"5000ms\"><").append(tag).append(" src=\"").append(name).append('"')
                    .append(region).append("/></par>");
        }
        if (text != null && !text.isEmpty()) {
            parts.add(new MmsPdu.Part("text/plain", "text.txt", text.getBytes(StandardCharsets.UTF_8)));
            body.append("<par dur=\"5000ms\"><text src=\"text.txt\" region=\"Text\"/></par>");
        }
        String smil = "<smil><head><layout><root-layout/>"
                + "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>"
                + "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>"
                + "</layout></head><body>" + body + "</body></smil>";
        MmsPdu.Part layout = new MmsPdu.Part("application/smil", "smil.xml", smil.getBytes(StandardCharsets.UTF_8));
        layout.contentId = "<smil>";
        parts.add(0, layout);
        return parts;
    }

    /** A safe, unique file name with an extension that matches the type. */
    private static String fileName(int index, Attachment a) {
        String base = a.name == null ? "file" : a.name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "file" + base;
        if (!base.contains(".")) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(a.contentType);
            if (ext != null) base = base + "." + ext;
        }
        return index + "_" + base;
    }

    static void markSent(Context context, Uri row, boolean ok) {
        if (row == null || !"content".equals(row.getScheme())) return;
        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.MESSAGE_BOX, ok ? Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED);
        try {
            context.getContentResolver().update(row, values, null, null);
        } catch (RuntimeException ignored) {
            // Message deleted meanwhile.
        }
    }

    // ------------------------------------------------------------ receiving

    /** A "you have an MMS" notice arrived: fetch the actual message. */
    public static void onWapPush(Context context, byte[] data, int subId) {
        if (data == null) return;
        MmsPdu.Message notice;
        try {
            notice = MmsPdu.parse(data);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (notice.type != MmsPdu.TYPE_NOTIFICATION_IND || TextUtils.isEmpty(notice.contentLocation)) return;
        if (alreadySaved(context, notice.transactionId)) return;  // Carriers sometimes send it twice.
        download(context, notice.contentLocation, notice.transactionId, notice.from, subId);
    }

    public static void download(Context context, String location, String transactionId, String from, int subId) {
        File file = new File(MmsFileProvider.dir(context, MmsFileProvider.DIR_MMS),
                "download-" + System.nanoTime() + ".pdu");
        try {
            file.createNewFile();
        } catch (IOException e) {
            return;
        }
        Uri contentUri = MmsFileProvider.uriFor(context, file);
        grant(context, contentUri);

        Intent intent = new Intent(context, MmsResultReceiver.class)
                .setAction(MmsResultReceiver.ACTION_DOWNLOADED)
                .setData(Uri.parse("mms-download:" + file.getName()))
                .putExtra(EXTRA_FILE, file.getPath())
                .putExtra(EXTRA_LOCATION, location)
                .putExtra(EXTRA_TRANSACTION, transactionId)
                .putExtra(EXTRA_FROM, from)
                .putExtra(EXTRA_SUB, subId);
        PendingIntent done = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            SmsWriter.smsManager(context, subId).downloadMultimediaMessage(context, location, contentUri, null, done);
        } catch (RuntimeException e) {
            file.delete();
            Notifier.showMmsFailed(context, from, location, transactionId, subId);
        }
    }

    /** The phone's MMS service finished downloading: save the message and notify. */
    static void onDownloaded(Context context, Intent intent, int resultCode) {
        File file = new File(intent.getStringExtra(EXTRA_FILE));
        String location = intent.getStringExtra(EXTRA_LOCATION);
        String transactionId = intent.getStringExtra(EXTRA_TRANSACTION);
        String noticeFrom = intent.getStringExtra(EXTRA_FROM);
        int subId = intent.getIntExtra(EXTRA_SUB, -1);

        byte[] data = readFile(file);
        file.delete();
        MmsPdu.Message m = null;
        if (resultCode == Activity.RESULT_OK && data != null && data.length > 0) {
            try {
                m = MmsPdu.parse(data);
            } catch (IllegalArgumentException ignored) {
                // Falls through to the failure notice.
            }
        }
        if (m == null || m.type != MmsPdu.TYPE_RETRIEVE_CONF) {
            Notifier.showMmsFailed(context, noticeFrom, location, transactionId, subId);
            return;
        }
        if (m.from == null) m.from = noticeFrom;

        List<String> recipients = recipientsOf(context, m);
        Uri row = saveIncoming(context, m, recipients, location, transactionId, subId);
        if (row == null) return;

        acknowledge(context, transactionId, subId);

        MessageReader reader = new MessageReader(context);
        long threadId = reader.threadIdOf(row);
        String from = m.from == null ? "" : m.from;
        if (Prefs.isBlocked(context, from)) return;
        if (threadId != -1 && threadId == ThreadActivity.visibleThreadId) {
            SmsWriter.markRead(context, threadId);
            return;
        }

        String text = "";
        for (MmsPdu.Part p : m.parts) {
            if (p.contentType.equals("text/plain")) text = text.isEmpty() ? p.text() : text + "\n" + p.text();
        }
        if (text.isEmpty()) {
            for (MmsPdu.Part p : m.parts) {
                if (!p.contentType.equals("application/smil")) {
                    text = new Part(0, p.contentType, p.name).label();
                    break;
                }
            }
        }
        if (text.isEmpty() && m.subject != null) text = m.subject;

        boolean group = recipients.size() > 1;
        String title = group ? reader.names(recipients) : reader.lookupName(from);
        if (group) text = MessageReader.firstName(reader.lookupName(from)) + ": " + text;
        Notifier.showMessage(context, threadId, recipients, title, text);
    }

    /** Some carriers want to hear that the message arrived, or they send it again. */
    private static void acknowledge(Context context, String transactionId, int subId) {
        try {
            SmsManager manager = SmsWriter.smsManager(context, subId);
            Bundle config = manager.getCarrierConfigValues();
            if (config == null || !config.getBoolean(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED, false)) return;
            File file = new File(MmsFileProvider.dir(context, MmsFileProvider.DIR_MMS),
                    "ack-" + System.nanoTime() + ".pdu");
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(MmsPdu.encodeNotifyResp(transactionId));
            }
            Uri uri = MmsFileProvider.uriFor(context, file);
            grant(context, uri);
            manager.sendMultimediaMessage(context, uri, null, null, null);
        } catch (IOException | RuntimeException ignored) {
            // Best effort.
        }
    }

    /** Everyone in the chat except us: sender, plus other To and Cc addresses. */
    static List<String> recipientsOf(Context context, MmsPdu.Message m) {
        List<String> self = selfNumbers(context);
        List<String> others = new ArrayList<>();
        if (!TextUtils.isEmpty(m.from)) others.add(m.from);
        List<String> rest = new ArrayList<>(m.to);
        rest.addAll(m.cc);
        if (self.isEmpty() && rest.size() == 1) return others;  // Just us on the To line.
        for (String a : rest) {
            if (TextUtils.isEmpty(a) || isSelf(a, self) || contains(others, a)) continue;
            others.add(a);
        }
        return others;
    }

    private static boolean isSelf(String address, List<String> self) {
        for (String s : self) {
            if (PhoneNumberUtils.compare(s, address)) return true;
        }
        return false;
    }

    private static boolean contains(List<String> list, String address) {
        for (String a : list) {
            if (Prefs.sameAddress(a, address)) return true;
        }
        return false;
    }

    /** Our own phone numbers, so we can leave ourselves out of group chats. */
    @SuppressWarnings("deprecation")
    static List<String> selfNumbers(Context context) {
        List<String> numbers = new ArrayList<>();
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            String n = tm == null ? null : tm.getLine1Number();
            if (!TextUtils.isEmpty(n)) numbers.add(n);
        } catch (SecurityException ignored) {
            // No permission to read our number.
        }
        try {
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> subs = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs != null) {
                for (SubscriptionInfo info : subs) {
                    if (!TextUtils.isEmpty(info.getNumber())) numbers.add(info.getNumber());
                }
            }
        } catch (SecurityException ignored) {
            // No permission to read SIM details.
        }
        return numbers;
    }

    // ------------------------------------------------------------ provider

    private static boolean alreadySaved(Context context, String transactionId) {
        if (transactionId == null) return false;
        try (Cursor c = context.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                new String[]{Telephony.Mms._ID}, Telephony.Mms.TRANSACTION_ID + " = ?",
                new String[]{transactionId}, null)) {
            return c != null && c.getCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Uri saveIncoming(Context context, MmsPdu.Message m, List<String> recipients,
                                    String location, String transactionId, int subId) {
        long threadId = Telephony.Threads.getOrCreateThreadId(context, new HashSet<>(recipients));
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.THREAD_ID, threadId);
        v.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000);
        v.put(Telephony.Mms.DATE_SENT, m.date);
        v.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX);
        v.put(Telephony.Mms.READ, 0);
        v.put(Telephony.Mms.SEEN, 0);
        v.put(Telephony.Mms.MESSAGE_TYPE, MmsPdu.TYPE_RETRIEVE_CONF);
        v.put(Telephony.Mms.MMS_VERSION, 0x12);
        v.put(Telephony.Mms.CONTENT_TYPE, m.contentType);
        v.put(Telephony.Mms.MESSAGE_ID, m.messageId);
        v.put(Telephony.Mms.TRANSACTION_ID, transactionId);
        v.put(Telephony.Mms.CONTENT_LOCATION, location);
        if (m.subject != null) {
            v.put(Telephony.Mms.SUBJECT, m.subject);
            v.put(Telephony.Mms.SUBJECT_CHARSET, CHARSET_UTF8);
        }
        if (subId >= 0) v.put(Telephony.Mms.SUBSCRIPTION_ID, subId);

        ContentResolver resolver = context.getContentResolver();
        Uri row = resolver.insert(Telephony.Mms.Inbox.CONTENT_URI, v);
        if (row == null) return null;
        long id = ContentUris.parseId(row);
        if (m.from != null) saveAddress(resolver, id, m.from, ADDR_FROM);
        for (String to : m.to) saveAddress(resolver, id, to, ADDR_TO);
        for (String cc : m.cc) saveAddress(resolver, id, cc, ADDR_CC);
        saveParts(resolver, id, m.parts);
        return row;
    }

    private static Uri saveOutgoing(Context context, long threadId, List<String> recipients,
                                    List<MmsPdu.Part> parts, String transactionId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.THREAD_ID, threadId);
        v.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000);
        v.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX);
        v.put(Telephony.Mms.READ, 1);
        v.put(Telephony.Mms.SEEN, 1);
        v.put(Telephony.Mms.MESSAGE_TYPE, MmsPdu.TYPE_SEND_REQ);
        v.put(Telephony.Mms.MMS_VERSION, 0x12);
        v.put(Telephony.Mms.CONTENT_TYPE, MmsPdu.MULTIPART_RELATED);
        v.put(Telephony.Mms.TRANSACTION_ID, transactionId);
        v.put(Telephony.Mms.MESSAGE_CLASS, "personal");
        v.put(Telephony.Mms.PRIORITY, 0x81);
        v.put(Telephony.Mms.READ_REPORT, 0x81);
        v.put(Telephony.Mms.DELIVERY_REPORT, 0x81);

        ContentResolver resolver = context.getContentResolver();
        Uri row;
        try {
            row = resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, v);
        } catch (RuntimeException e) {
            return null;  // Not the default SMS app.
        }
        if (row == null) return null;
        long id = ContentUris.parseId(row);
        saveAddress(resolver, id, "insert-address-token", ADDR_FROM);
        for (String to : recipients) saveAddress(resolver, id, to, ADDR_TO);
        saveParts(resolver, id, parts);
        return row;
    }

    private static void saveAddress(ContentResolver resolver, long id, String address, int type) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.Addr.MSG_ID, id);
        v.put(Telephony.Mms.Addr.ADDRESS, address);
        v.put(Telephony.Mms.Addr.TYPE, type);
        v.put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8);
        resolver.insert(Uri.parse("content://mms/" + id + "/addr"), v);
    }

    private static void saveParts(ContentResolver resolver, long id, List<MmsPdu.Part> parts) {
        Uri partsUri = Uri.parse("content://mms/" + id + "/part");
        for (int i = 0; i < parts.size(); i++) {
            MmsPdu.Part p = parts.get(i);
            boolean smil = "application/smil".equals(p.contentType);
            boolean text = smil || "text/plain".equals(p.contentType);
            ContentValues v = new ContentValues();
            v.put(Telephony.Mms.Part.MSG_ID, id);
            v.put(Telephony.Mms.Part.SEQ, smil ? -1 : i);
            v.put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType);
            if (p.name != null) {
                v.put(Telephony.Mms.Part.NAME, p.name);
                v.put(Telephony.Mms.Part.FILENAME, p.name);
            }
            if (p.contentId != null) v.put(Telephony.Mms.Part.CONTENT_ID, p.contentId);
            if (p.contentLocation != null) v.put(Telephony.Mms.Part.CONTENT_LOCATION, p.contentLocation);
            if (text) {
                v.put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8);
                v.put(Telephony.Mms.Part.TEXT, p.text());
            }
            Uri partUri = resolver.insert(partsUri, v);
            if (!text && partUri != null) {
                // Binary data goes into the part's file, which the provider creates for us.
                try (OutputStream out = resolver.openOutputStream(partUri)) {
                    if (out != null) out.write(p.data);
                } catch (IOException | RuntimeException ignored) {
                    // Keep the rest of the message even if one file fails.
                }
            }
        }
    }

    // ------------------------------------------------------------ files

    /** Lets the phone's MMS service read or write our file. */
    private static void grant(Context context, Uri uri) {
        try {
            context.grantUriPermission(PHONE_PACKAGE, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Android grants it on our behalf when SmsManager is called.
        }
    }

    static byte[] readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] read(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in == null ? null : readAll(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
