package co.hkapps.messages;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/** Picks SMS for plain 1-to-1 texts and MMS for groups and attachments. Call off the main thread. */
public final class Sender {

    private Sender() {}

    public static void send(Context context, List<String> recipients, String text, List<Attachment> files) {
        if (recipients.isEmpty()) return;
        if (recipients.size() == 1 && files.isEmpty()) {
            SmsWriter.send(context, recipients.get(0), text);
        } else {
            Mms.send(context, recipients, text, files);
        }
    }

    public static void send(Context context, List<String> recipients, String text) {
        send(context, recipients, text, Collections.<Attachment>emptyList());
    }

    /** Deletes a failed message and sends it again. */
    public static void resend(Context context, Message message) {
        if (message.mms) {
            Mms.resend(context, message);
        } else {
            SmsWriter.deleteMessage(context, message);
            SmsWriter.send(context, message.address, message.body);
        }
    }
}
