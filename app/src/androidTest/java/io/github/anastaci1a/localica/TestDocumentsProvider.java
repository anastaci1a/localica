package io.github.anastaci1a.localica;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.*;

// Runs in the test APK's own process, which does not contain the app's Kotlin runtime.
public class TestDocumentsProvider extends DocumentsProvider {
    private static final String[] DOCUMENTS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
    private static final String[] ROOTS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOTS : projection);
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "music").add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "Localica test library")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES, "audio/*").add(Root.COLUMN_ICON, android.R.drawable.ic_media_play);
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENTS : projection);
        addDocument(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENTS : projection);
        if (parent.equals("root")) {
            addDocument(cursor, "tone.mp3"); addDocument(cursor, "nested"); addDocument(cursor, "notes.txt");
        } else if (parent.equals("nested")) addDocument(cursor, "tone.m4a");
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String id) {
        return (parent.equals("root") && !id.equals("root")) || (parent.equals("nested") && id.equals("tone.m4a"));
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.equals("r") || !(id.equals("tone.mp3") || id.equals("tone.m4a"))) throw new FileNotFoundException(id);
        File file = new File(getContext().getCacheDir(), id);
        if (!file.exists()) {
            try (InputStream input = getContext().getAssets().open(id); OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void addDocument(MatrixCursor cursor, String id) {
        boolean directory = id.equals("root") || id.equals("nested");
        String mime = directory ? Document.MIME_TYPE_DIR : id.equals("tone.mp3") ? "audio/mpeg" :
            id.equals("tone.m4a") ? "audio/mp4" : "text/plain";
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, id.equals("root") ? "Test tones" : id)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, directory ? null : 1024);
    }
}
