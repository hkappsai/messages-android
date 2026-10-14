package co.hkapps.messages;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Shares files in the app's cache with the system and other apps:
 * MMS data for the phone service, attachments to open, and camera photos.
 * URIs look like content://co.hkapps.messages.files/{folder}/{file}.
 */
public class MmsFileProvider extends ContentProvider {

    static final String DIR_MMS = "mms";
    static final String DIR_SHARE = "share";
    static final String DIR_CAMERA = "camera";

    static String authority(Context context) {
        return context.getPackageName() + ".files";
    }

    static File dir(Context context, String name) {
        File dir = new File(context.getCacheDir(), name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(file.getParentFile().getName()).appendPath(file.getName()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) throw new FileNotFoundException(uri.toString());
        String folder = segments.get(0);
        String name = segments.get(1);
        boolean knownFolder = folder.equals(DIR_MMS) || folder.equals(DIR_SHARE) || folder.equals(DIR_CAMERA);
        // Only plain file names inside our own folders, never paths that climb out.
        if (!knownFolder || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException(uri.toString());
        }
        return new File(dir(getContext(), folder), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        // Apps receiving a shared file ask for its name and size.
        File file;
        try {
            file = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return type != null ? type : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
