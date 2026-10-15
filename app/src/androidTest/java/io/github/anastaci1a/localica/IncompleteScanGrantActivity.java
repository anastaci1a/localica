package io.github.anastaci1a.localica;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Only grants/revokes fixed synthetic trees to the debug app. */
public class IncompleteScanGrantActivity extends Activity {
    public static final String AUTHORITY = "io.github.anastaci1a.localica.test.incomplete.scan";
    public static final String TARGET = "io.github.anastaci1a.localica.debug";
    public static final String GRANT = "io.github.anastaci1a.localica.test.GRANT_INCOMPLETE_SCAN";
    public static final String REVOKE = "io.github.anastaci1a.localica.test.REVOKE_INCOMPLETE_SCAN";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        } finally { finish(); }
    }
}
