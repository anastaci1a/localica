package io.github.anastaci1a.localica;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;

/** Only grants/revokes fixed synthetic trees to the debug app. */
public class IncompleteScanGrantActivity extends Activity {
    public static final String AUTHORITY = "io.github.anastaci1a.localica.test.incomplete.scan";
    public static final String TARGET = "io.github.anastaci1a.localica.debug";
    public static final String GRANT = "io.github.anastaci1a.localica.test.GRANT_INCOMPLETE_SCAN";
    public static final String REVOKE = "io.github.anastaci1a.localica.test.REVOKE_INCOMPLETE_SCAN";
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        trace("onCreate", getIntent());
        try {
            for (String id : new String[]{"root", "other", "new-root"}) {
                android.net.Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id);
                if (GRANT.equals(getIntent().getAction())) {
                    grantUriPermission(TARGET, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                } else if (REVOKE.equals(getIntent().getAction())) {
                    revokeUriPermission(TARGET, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
            ResultReceiver completion = getIntent().getParcelableExtra("completion");
            if (completion != null) {
                trace("callback-send", getIntent());
                completion.send(0, Bundle.EMPTY);
            }
        } finally { trace("finish-entry", getIntent()); finish(); }
    }
    @Override protected void onNewIntent(Intent intent) {
        trace("onNewIntent", intent);
        super.onNewIntent(intent);
    }
    @Override protected void onDestroy() {
        trace("onDestroy", getIntent());
        super.onDestroy();
    }
    private void trace(String stage, Intent intent) {
        android.util.Log.i("ScanGrantFixture", "stage=" + stage + " instance=" + System.identityHashCode(this)
            + " id=" + intent.getStringExtra("request-id") + " action=" + intent.getAction()
            + " phase=" + intent.getStringExtra("phase"));
    }
}
