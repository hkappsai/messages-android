package co.hkapps.messages;

import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Keep" chat: notes to yourself, and things shared from other apps to remember. Stored in
 * the app, never sent anywhere.
 */
final class Keep {

    /** Thread id used for the Keep chat in screens and intents. */
    static final long THREAD_ID = -50;
    static final String NAME = "Keep";

    private Keep() {}

    static boolean is(long threadId) {
        return threadId == THREAD_ID;
    }

    /** Notes as messages, oldest first, so the chat screen can show them like any chat. */
    static List<Message> load(Context c) {
        List<Message> list = new ArrayList<>();
        for (AppDb.Note n : AppDb.get(c).notes()) {
            Message m = new Message(n.id, false, THREAD_ID, "", n.body, n.date, Telephony.Sms.MESSAGE_TYPE_SENT, true);
            if (n.file != null) {
                File f = new File(n.file);
                m.parts.add(new Part(n.id, n.type, f.getName(), MmsFileProvider.uriFor(c, f)));
            }
            list.add(m);
        }
        return list;
    }

    /** Saves a note; each file becomes its own note. Call off the main thread. */
    static void add(Context c, String text, List<Attachment> files) {
        AppDb db = AppDb.get(c);
        for (Attachment a : files) {
            File dir = MmsFileProvider.dir(c, MmsFileProvider.DIR_KEEP);
            String safe = a.name == null ? "file" : a.name.replaceAll("[^A-Za-z0-9._-]", "_");
            File f = new File(dir, System.currentTimeMillis() + "_" + safe);
            Attachment keep = a.original != null ? a.original : a;
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(keep.data);
                db.addNote("", f.getPath(), keep.contentType);
            } catch (IOException ignored) {
                // Skip what can't be stored.
            }
        }
        if (text != null && !text.trim().isEmpty()) db.addNote(text, null, null);
    }

    static Uri fileUri(Context c, String path) {
        return MmsFileProvider.uriFor(c, new File(path));
    }

    /** The newest note, for the chat list row. */
    static Message latest(Context c) {
        List<Message> all = load(c);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
