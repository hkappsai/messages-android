package co.hkapps.messages;

import android.content.Context;
import android.telephony.SmsMessage;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns what the user typed into SMS or MMS, following the chat's sending settings:
 * SIM, delivery reports, group as one MMS or one text each, long texts as MMS, split long texts,
 * stripped accents. Call off the main thread.
 */
public final class Sender {

    private Sender() {}

    /** Sends in the chat with these people, using that chat's settings. */
    public static void send(Context context, List<String> recipients, String text, List<Attachment> files) {
        send(context, recipients, text, files, -2);
    }

    public static void send(Context context, List<String> recipients, String text) {
        send(context, recipients, text, Collections.<Attachment>emptyList());
    }

    /** subId -2 picks the chat's SIM; -1 lets Android pick. */
    public static void send(Context context, List<String> recipients, String text, List<Attachment> files, int subId) {
        if (recipients.isEmpty()) return;
        long threadId = threadIdFor(context, recipients);
        if (subId == -2) subId = Sims.forChat(context, threadId, -1);
        text = prepareText(context, threadId, text, files.isEmpty() && recipients.size() == 1);
        DebugLog.log(context, "send to " + recipients.size() + " people, " + files.size() + " files, sim " + subId);

        if (recipients.size() > 1 && !ChatPrefs.bool(context, threadId, Prefs.GROUP_MMS)) {
            // Bulk: everyone gets their own copy, and replies come back only to us.
            for (String r : recipients) sendOne(context, r, text, files, subId);
            return;
        }
        if (recipients.size() == 1) {
            sendOne(context, recipients.get(0), text, files, subId);
        } else {
            Mms.send(context, recipients, text, files, subId,
                    ChatPrefs.bool(context, threadId, Prefs.DELIVERY_MMS));
        }
    }

    private static void sendOne(Context context, String address, String text, List<Attachment> files, int subId) {
        long threadId = threadIdFor(context, Collections.singletonList(address));
        if (files.isEmpty() && !tooLongForSms(context, threadId, text)) {
            SmsWriter.send(context, address, text, subId,
                    ChatPrefs.bool(context, threadId, Prefs.DELIVERY_SMS),
                    ChatPrefs.bool(context, threadId, Prefs.SPLIT_SMS));
        } else {
            Mms.send(context, Collections.singletonList(address), text, files, subId,
                    ChatPrefs.bool(context, threadId, Prefs.DELIVERY_MMS));
        }
    }

    /** "Convert long SMS to MMS": more parts than the chosen limit go as one MMS. */
    private static boolean tooLongForSms(Context context, long threadId, String text) {
        int limit = ChatPrefs.integer(context, threadId, Prefs.LONG_SMS_AS_MMS);
        if (limit <= 0) return false;
        return SmsMessage.calculateLength(text, false)[0] > limit;
    }

    /** Strips accents when asked to. Signatures are added by the compose box, not here. */
    static String prepareText(Context context, long threadId, String text, boolean sms) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        int accents = ChatPrefs.integer(context, threadId, Prefs.STRIP_ACCENTS);
        if (accents == Prefs.ACCENTS_ALWAYS) return stripAccents(text);
        if (accents == Prefs.ACCENTS_WHEN_CHEAPER && sms) {
            String plain = stripAccents(text);
            if (SmsMessage.calculateLength(plain, false)[0] < SmsMessage.calculateLength(text, false)[0]) return plain;
        }
        return text;
    }

    /** "Café" becomes "Cafe". Emoji and other symbols stay. */
    static String stripAccents(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{Mn}+", "");
    }

    static long threadIdFor(Context context, List<String> recipients) {
        try {
            return android.provider.Telephony.Threads.getOrCreateThreadId(context, new java.util.HashSet<>(recipients));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Deletes a failed message and sends it again. */
    public static void resend(Context context, Message message) {
        if (message.mms) {
            Mms.resend(context, message);
        } else {
            SmsWriter.deleteMessage(context, message);
            send(context, Collections.singletonList(message.address), message.body,
                    Collections.<Attachment>emptyList(), message.subId >= 0 ? message.subId : -2);
        }
    }

    /** Sends a copy of an old message as a new one ("Send as new"). */
    public static void sendAgain(Context context, Message message, List<String> recipients) {
        List<Attachment> files = new ArrayList<>();
        for (Part p : message.parts) {
            byte[] data = Mms.read(context, p.uri);
            if (data != null) files.add(new Attachment(p.contentType, p.name != null ? p.name : "file", data));
        }
        send(context, recipients, message.body, files);
    }
}
