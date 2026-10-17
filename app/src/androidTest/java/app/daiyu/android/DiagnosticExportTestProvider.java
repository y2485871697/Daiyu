package app.daiyu.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** AndroidTest-only document target; never serves application or user files. */
public final class DiagnosticExportTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File target(Uri uri) {
        if (uri == null || uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Expected one UUID path segment");
        }
        String id = uri.getLastPathSegment();
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Expected a canonical UUID");
        }
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        File root = new File(context.getCacheDir(), "diagnostic-export-test");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Cannot create test export directory");
        }
        return new File(root, id + ".json");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(target(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return target(uri).delete() ? 1 : 0;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
