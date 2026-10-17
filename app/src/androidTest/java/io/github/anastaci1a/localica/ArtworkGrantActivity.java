package io.github.anastaci1a.localica;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

/** Grants only synthetic fixture documents to the debug app. */
public class ArtworkGrantActivity extends Activity {
    public static final String AUTHORITY = "io.github.anastaci1a.localica.test.artwork.grants";
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            for (int i = 0; i < 160; i++) {
                Uri uri = Uri.parse("content://" + AUTHORITY + "/document/" + i);
                grantUriPermission("io.github.anastaci1a.localica.debug", uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            }
            Uri root = Uri.parse("content://" + AUTHORITY + "/tree/root");
            grantUriPermission("io.github.anastaci1a.localica.debug", root,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            ResultReceiver completion = getIntent().getParcelableExtra("completion");
            if (completion != null) completion.send(0, Bundle.EMPTY);
        } finally { finish(); }
    }
}
