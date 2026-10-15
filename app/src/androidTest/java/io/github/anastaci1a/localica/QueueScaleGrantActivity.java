package io.github.anastaci1a.localica;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Runs in the test APK's UID; only this fixed synthetic tree can be temporarily granted or revoked. */
public class QueueScaleGrantActivity extends Activity {
    public static final String AUTHORITY = "io.github.anastaci1a.localica.test.queue.scale";
    public static final String TARGET = "io.github.anastaci1a.localica.debug";
    public static final String GRANT = "io.github.anastaci1a.localica.test.GRANT_QUEUE_SCALE";
    public static final String REVOKE = "io.github.anastaci1a.localica.test.REVOKE_QUEUE_SCALE";
    public static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "queue-scale");
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (GRANT.equals(getIntent().getAction())) {
                grantUriPermission(TARGET, TREE, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            } else if (REVOKE.equals(getIntent().getAction())) {
                revokeUriPermission(TARGET, TREE, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } finally { finish(); }
    }
}
