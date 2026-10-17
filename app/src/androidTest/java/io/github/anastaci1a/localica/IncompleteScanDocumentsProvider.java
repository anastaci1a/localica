package io.github.anastaci1a.localica;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;

/** Synthetic directory status fixtures; no real files or picker entries. Runs in the test APK UID. */
public class IncompleteScanDocumentsProvider extends DocumentsProvider {
    private final java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String mode = "complete";
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED};
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{Root.COLUMN_ROOT_ID} : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = cursor(projection);
        add(cursor, id, true);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) {
        String current = mode;
        boolean nested = parent.equals("nested");
        boolean affected = current.startsWith("nested-") ? nested : !nested;
        String status = current.replace("nested-", "");
        if (affected && status.equals("null")) return null;
        MatrixCursor cursor = cursor(projection);
        if (!current.equals("empty") && !(affected && status.equals("loading-empty"))) {
            if (nested) add(cursor, "nested-song.mp3", false);
            else {
                add(cursor, "song.mp3", false);
                add(cursor, "nested", true);
                if (current.equals("updated")) add(cursor, "new.mp3", false);
            }
        }
        Bundle extras = new Bundle();
        if (affected && status.startsWith("loading")) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
        else extras.putBoolean(DocumentsContract.EXTRA_LOADING, false);
        if (affected && status.equals("error")) extras.putString(DocumentsContract.EXTRA_ERROR, "Fixture unavailable");
        if (affected && status.equals("error-empty")) extras.putString(DocumentsContract.EXTRA_ERROR, "");
        if (current.equals("complete")) {
            extras.putString(DocumentsContract.EXTRA_ERROR, null);
            extras.putString(DocumentsContract.EXTRA_INFO, "Fixture information");
        }
        if (affected && status.equals("loading-malformed")) extras.putString(DocumentsContract.EXTRA_LOADING, "false");
        if (affected && status.equals("error-malformed")) extras.putInt(DocumentsContract.EXTRA_ERROR, 1);
        cursor.setExtras(extras);
        return cursor;
    }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (method.equals("scanFixtureStats")) {
            Bundle result = new Bundle(); result.putInt("opened", opened.get()); result.putInt("closed", closed.get()); return result;
        }
        if (!method.equals("scanFixtureMode")) return super.call(method, argument, extras);
        if (argument == null || !argument.matches("(nested-)?(complete|updated|empty|null|loading-empty|loading-partial|loading-malformed|error|error-empty|error-malformed)"))
            throw new IllegalArgumentException("Unknown fixture mode");
        mode = argument;
        return new Bundle();
    }
    @Override public boolean isChildDocument(String parent, String id) {
        return parent.equals("root") || parent.equals("other") || parent.equals("new-root") || parent.equals("nested");
    }
    // Opaque Song Info fixtures are query-only and never enter scanner child listings.
    @Override public DocumentsContract.Path findDocumentPath(String parent, String child) throws FileNotFoundException {
        if ("root".equals(parent) && ("si-a-90".equals(child) || "si-b-44".equals(child))) {
            String ancestor = "si-a-90".equals(child) ? "si-parent-x7" : "si-parent-y8";
            return new DocumentsContract.Path(null, java.util.Arrays.asList("root", ancestor, child));
        }
        throw new FileNotFoundException("Path unavailable for this fixture");
    }
    private static String songInfoDisplayName(String id) {
        switch (id) {
            case "si-a-90": case "si-b-44": return "Media";
            case "si-parent-x7": return "1";
            case "si-parent-y8": return "2";
            default: return id;
        }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        throw new FileNotFoundException("Metadata is supplied by the scan test");
    }
    private MatrixCursor cursor(String[] projection) {
        opened.incrementAndGet();
        return new MatrixCursor(projection == null ? COLUMNS : projection) {
            @Override public void close() {
                if (!isClosed()) closed.incrementAndGet();
                super.close();
            }
        };
    }
    private void add(MatrixCursor cursor, String id, boolean directory) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(songInfoDisplayName(id)); break;
                case Document.COLUMN_MIME_TYPE: row.add(directory ? Document.MIME_TYPE_DIR : "audio/mpeg"); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(123456L); break;
                default: row.add(null);
            }
        }
    }
}
