package co.hkapps.messages;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's own small database, for things Android's message store has no place for:
 * drafts, signatures, vibrate patterns, the Keep chat, link previews, deletes waiting for undo,
 * GIF recents and the debug log. Messages themselves stay in Android's store.
 */
public final class AppDb extends SQLiteOpenHelper {

    private static AppDb instance;

    public static synchronized AppDb get(Context context) {
        if (instance == null) instance = new AppDb(context.getApplicationContext());
        return instance;
    }

    private AppDb(Context context) {
        super(context, "app.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE drafts (thread_id INTEGER PRIMARY KEY, text TEXT, updated INTEGER)");
        db.execSQL("CREATE TABLE signatures (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT)");
        db.execSQL("CREATE TABLE patterns (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, pattern TEXT)");
        db.execSQL("CREATE TABLE keep (_id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, body TEXT,"
                + " file TEXT, type TEXT)");
        db.execSQL("CREATE TABLE previews (url TEXT PRIMARY KEY, title TEXT, description TEXT,"
                + " image TEXT, site TEXT, fetched INTEGER)");
        db.execSQL("CREATE TABLE pending_deletes (key TEXT PRIMARY KEY, due INTEGER)");
        db.execSQL("CREATE TABLE gif_recents (url TEXT PRIMARY KEY, preview TEXT, used INTEGER)");
        db.execSQL("CREATE TABLE log (_id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER, text TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // First version.
    }

    // ---------------------------------------------------------------- drafts

    public String draft(long threadId) {
        try (Cursor c = getReadableDatabase().query("drafts", new String[]{"text"}, "thread_id = ?",
                new String[]{String.valueOf(threadId)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public java.util.Map<Long, String> drafts() {
        java.util.Map<Long, String> result = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().query("drafts", new String[]{"thread_id", "text"},
                null, null, null, null, null)) {
            while (c.moveToNext()) result.put(c.getLong(0), c.getString(1));
        }
        return result;
    }

    public void setDraft(long threadId, String text) {
        SQLiteDatabase db = getWritableDatabase();
        if (text == null || text.trim().isEmpty()) {
            db.delete("drafts", "thread_id = ?", new String[]{String.valueOf(threadId)});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("thread_id", threadId);
        v.put("text", text);
        v.put("updated", System.currentTimeMillis());
        db.insertWithOnConflict("drafts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------------------------- signatures

    public static final class Item {
        public final long id;
        public final String name;
        public final String value;

        Item(long id, String name, String value) {
            this.id = id;
            this.name = name;
            this.value = value;
        }
    }

    public List<Item> signatures() {
        List<Item> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("signatures", new String[]{"_id", "text"},
                null, null, null, null, "_id")) {
            while (c.moveToNext()) list.add(new Item(c.getLong(0), c.getString(1), c.getString(1)));
        }
        return list;
    }

    public String signature(long id) {
        if (id < 0) return null;
        try (Cursor c = getReadableDatabase().query("signatures", new String[]{"text"}, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public long saveSignature(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        if (id < 0) return getWritableDatabase().insert("signatures", null, v);
        getWritableDatabase().update("signatures", v, "_id = ?", new String[]{String.valueOf(id)});
        return id;
    }

    public void deleteSignature(long id) {
        getWritableDatabase().delete("signatures", "_id = ?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------- vibrate patterns

    public List<Item> patterns() {
        List<Item> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("patterns", new String[]{"_id", "name", "pattern"},
                null, null, null, null, "_id")) {
            while (c.moveToNext()) list.add(new Item(c.getLong(0), c.getString(1), c.getString(2)));
        }
        return list;
    }

    public String pattern(long id) {
        try (Cursor c = getReadableDatabase().query("patterns", new String[]{"pattern"}, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public long savePattern(long id, String name, String pattern) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        if (pattern != null) v.put("pattern", pattern);
        if (id < 0) return getWritableDatabase().insert("patterns", null, v);
        getWritableDatabase().update("patterns", v, "_id = ?", new String[]{String.valueOf(id)});
        return id;
    }

    public void deletePattern(long id) {
        getWritableDatabase().delete("patterns", "_id = ?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------- Keep chat

    public static final class Note {
        public final long id;
        public final long date;
        public final String body;
        public final String file;
        public final String type;

        Note(long id, long date, String body, String file, String type) {
            this.id = id;
            this.date = date;
            this.body = body == null ? "" : body;
            this.file = file;
            this.type = type;
        }
    }

    public List<Note> notes() {
        List<Note> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("keep", new String[]{"_id", "date", "body", "file", "type"},
                null, null, null, null, "date")) {
            while (c.moveToNext()) {
                list.add(new Note(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)));
            }
        }
        return list;
    }

    public long addNote(String body, String file, String type) {
        ContentValues v = new ContentValues();
        v.put("date", System.currentTimeMillis());
        v.put("body", body);
        v.put("file", file);
        v.put("type", type);
        return getWritableDatabase().insert("keep", null, v);
    }

    public void deleteNote(long id) {
        Note note = null;
        for (Note n : notes()) if (n.id == id) note = n;
        if (note != null && note.file != null) new java.io.File(note.file).delete();
        getWritableDatabase().delete("keep", "_id = ?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------- link previews

    public LinkPreview.Info preview(String url) {
        try (Cursor c = getReadableDatabase().query("previews",
                new String[]{"title", "description", "image", "site", "fetched"}, "url = ?",
                new String[]{url}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            return new LinkPreview.Info(url, c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getLong(4));
        }
    }

    public void savePreview(LinkPreview.Info info) {
        ContentValues v = new ContentValues();
        v.put("url", info.url);
        v.put("title", info.title);
        v.put("description", info.description);
        v.put("image", info.image);
        v.put("site", info.site);
        v.put("fetched", info.fetched);
        getWritableDatabase().insertWithOnConflict("previews", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------------------------- deletes waiting for undo

    public void addPendingDelete(String key, long due) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("due", due);
        getWritableDatabase().insertWithOnConflict("pending_deletes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void removePendingDelete(String key) {
        getWritableDatabase().delete("pending_deletes", "key = ?", new String[]{key});
    }

    public List<String> pendingDeletes(long dueBefore) {
        List<String> keys = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("pending_deletes", new String[]{"key"},
                "due <= ?", new String[]{String.valueOf(dueBefore)}, null, null, null)) {
            while (c.moveToNext()) keys.add(c.getString(0));
        }
        return keys;
    }

    public java.util.Set<String> allPendingDeletes() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().query("pending_deletes", new String[]{"key"},
                null, null, null, null, null)) {
            while (c.moveToNext()) keys.add(c.getString(0));
        }
        return keys;
    }

    // ---------------------------------------------------------------- GIF recents

    public List<String[]> gifRecents() {
        List<String[]> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("gif_recents", new String[]{"url", "preview"},
                null, null, null, null, "used DESC", "60")) {
            while (c.moveToNext()) list.add(new String[]{c.getString(0), c.getString(1)});
        }
        return list;
    }

    public void addGifRecent(String url, String preview) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("preview", preview);
        v.put("used", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("gif_recents", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------------------------- debug log

    public void log(String text) {
        ContentValues v = new ContentValues();
        v.put("time", System.currentTimeMillis());
        v.put("text", text);
        SQLiteDatabase db = getWritableDatabase();
        db.insert("log", null, v);
        db.execSQL("DELETE FROM log WHERE _id <= (SELECT MAX(_id) - 2000 FROM log)");
    }

    public String logText() {
        StringBuilder sb = new StringBuilder();
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance();
        try (Cursor c = getReadableDatabase().query("log", new String[]{"time", "text"},
                null, null, null, null, "_id")) {
            while (c.moveToNext()) {
                sb.append(format.format(new java.util.Date(c.getLong(0)))).append("  ").append(c.getString(1)).append('\n');
            }
        }
        return sb.toString();
    }

    public void clearLog() {
        getWritableDatabase().delete("log", null, null);
    }
}
