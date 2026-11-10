package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Preserve the bitmap already acquired by a correctness check. No extra capture,
 * invalidation, frame advancement or claim that later state identifies its pixels. */
internal class HomeNativeEvidence(private val method: () -> String) {
    private val rows = JSONArray()
    fun save(bitmap: Bitmap, state: JSONObject) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val output = File(root, "home-native-receipts/${method()}").apply { check(isDirectory || mkdirs()) }
        val name = "frame-${rows.length().toString().padStart(3, '0')}.png"
        File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        rows.put(state.put("png", name).put("exportNs", SystemClock.elapsedRealtimeNanos()))
        File(output, "frames.json").writeText(JSONObject()
            .put("scope", "Original native bitmap and diagnostic state only. Overlay synchronization is not a retained-field recording receipt; state sampled later is not presented-pixel identity. Original assertions unchanged.")
            .put("frames", rows).toString(2))
    }
}
