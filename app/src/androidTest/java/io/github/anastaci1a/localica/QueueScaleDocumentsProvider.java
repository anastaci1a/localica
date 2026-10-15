package io.github.anastaci1a.localica;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only, read-only document identities backed by one shared MP3, not a real storage provider benchmark. */
public class QueueScaleDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
    private final AtomicInteger opens = new AtomicInteger();
    private final Set<String> opened = ConcurrentHashMap.newKeySet();
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        // Keep synthetic diagnostics out of the real picker fixtures.
        return new MatrixCursor(projection == null ? new String[]{Root.COLUMN_ROOT_ID} : projection);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        boolean directory = id.equals("queue-scale");
        if (!directory) validate(id);
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: case Document.COLUMN_DISPLAY_NAME: row.add(id); break;
                case Document.COLUMN_MIME_TYPE: row.add(directory ? Document.MIME_TYPE_DIR : "audio/mpeg"); break;
                case Document.COLUMN_FLAGS: row.add(0); break;
                default: row.add(null);
            }
        }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) {
        if (!parent.equals("queue-scale")) return false;
        try { validate(id); return true; } catch (FileNotFoundException invalid) { return false; }
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        return new MatrixCursor(projection == null ? COLUMNS : projection);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        validate(id);
        if (!mode.equals("r")) throw new FileNotFoundException("Read-only fixture");
        if (signal != null) signal.throwIfCanceled();
        ParcelFileDescriptor result = ParcelFileDescriptor.open(fixture(), ParcelFileDescriptor.MODE_READ_ONLY);
        opens.incrementAndGet(); opened.add(id);
        return result;
    }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (method.equals("resetStats")) { opens.set(0); opened.clear(); }
        else if (!method.equals("stats")) return super.call(method, argument, extras);
        Bundle result = new Bundle();
        result.putInt("opens", opens.get()); result.putStringArrayList("documents", new ArrayList<>(opened));
        return result;
    }
    private void validate(String id) throws FileNotFoundException {
        if (!id.matches("scale-(0|[1-9][0-9]{0,4})\\.mp3") || Integer.parseInt(id.substring(6, id.length() - 4)) >= 13000)
            throw new FileNotFoundException(id);
    }
    private synchronized File fixture() throws FileNotFoundException {
        File file = new File(getContext().getCacheDir(), "queue-scale-shared.mp3");
        if (!file.exists()) {
            try (InputStream input = getContext().getAssets().open("queue-tone.mp3"); OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            } catch (IOException failure) { file.delete(); throw new FileNotFoundException(failure.getMessage()); }
        }
        return file;
    }
}
