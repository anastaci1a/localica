package io.github.anastaci1a.localica;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** No corpus, grants or preparation: checks the standalone test APK's runtime linkage. */
public final class NavigationStandaloneRuntimeActivity extends Activity {
    static final String TAG = "LocalicaStandaloneRuntime";
    static final String ACTION = "io.github.anastaci1a.localica.test.PROBE_RUNTIME";
    static final String TEST_PACKAGE = "io.github.anastaci1a.localica.debug.test";
    static final String TARGET_PACKAGE = "io.github.anastaci1a.localica.debug";
    static final String[] CLASSES = {
        "kotlin.text.Regex", "kotlin.jvm.internal.Intrinsics", "kotlin.collections.CollectionsKt",
        "kotlin.io.CloseableKt", "kotlin.text.Charsets",
        "io.github.anastaci1a.localica.NavigationRandomCorpusOwnedRoot",
        "io.github.anastaci1a.localica.NavigationRandomCorpusJson",
        "io.github.anastaci1a.localica.NavigationRandomCorpusFiles",
        "io.github.anastaci1a.localica.NavigationRandomCorpusDocumentsProvider",
        "io.github.anastaci1a.localica.NavigationScaleCatalog",
        "io.github.anastaci1a.localica.NavigationRandomCorpusBinding"
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra("probeId");
        try {
            require(ACTION.equals(getIntent().getAction()), "Wrong action");
            require(getIntent().getExtras() != null && getIntent().getExtras().size() == 1,
                    "Only probeId is accepted");
            require(id != null && id.length() == 36 && UUID.fromString(id).toString().equals(id),
                    "Invalid probe ID");
            require(TEST_PACKAGE.equals(getPackageName()), "Wrong package");
            require(Process.myUid() == getApplicationInfo().uid, "Wrong provider UID");
            android.content.pm.ApplicationInfo target = getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0);
            require(Process.myUid() != target.uid, "Target UID cannot certify standalone linkage");
            String process;
            try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
                byte[] bytes = new byte[256];
                int count = input.read(bytes);
                require(count > 0 && count < bytes.length, "Unbounded process name");
                int end = 0;
                while (end < count && bytes[end] != 0) end++;
                process = new String(bytes, 0, end, StandardCharsets.UTF_8);
            }
            require((TEST_PACKAGE + ":navigation_random").equals(process), "Wrong process");
            ClassLoader loader = getClassLoader();
            String loaderDescription = loader.toString();
            require(loaderDescription.contains(getApplicationInfo().sourceDir), "Own APK not in loader");
            require(!loaderDescription.contains(target.sourceDir), "Target APK present in loader");
            JSONArray loaded = new JSONArray();
            for (String name : CLASSES) {
                Class.forName(name, true, loader);
                loaded.put(name);
            }
            JSONObject receipt = new JSONObject().put("probeId", id).put("status", "loaded")
                    .put("uid", Process.myUid()).put("pid", Process.myPid()).put("process", process)
                    .put("targetUid", target.uid).put("ownApk", getApplicationInfo().sourceDir)
                    .put("loader", loaderDescription).put("classes", loaded);
            Log.i(TAG, receipt.toString());
        } catch (Throwable failure) {
            Log.e(TAG, "Standalone linkage failed, probeId=" + id, failure);
        } finally {
            finish();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
