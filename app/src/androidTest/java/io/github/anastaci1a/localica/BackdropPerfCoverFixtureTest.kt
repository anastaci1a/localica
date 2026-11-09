package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Export reusable generated inputs; no operator library or reference artwork is used. */
class BackdropPerfCoverFixtureTest {
    @Test fun exportsVersionedRealisticAndAdversarialCovers() {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val directory = File(output, "cover-fixtures/v${BackdropPerfCovers.VERSION}-api${Build.VERSION.SDK_INT}").also { it.mkdirs() }
        val realistic = BackdropPerfCovers.realistic()
        val adversarial = BackdropPerfCovers.adversarial()
        assertEquals(12, realistic.size); assertEquals(6, adversarial.size)
        val entries = JSONArray()
        for (cover in realistic + adversarial) {
            val target = File(directory, "${cover.classification.lowercase()}-${cover.name}.png")
            target.outputStream().use { check(cover.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
            entries.put(JSONObject().put("name", cover.name).put("classification", cover.classification).put("seed", cover.seed)
                .put("width", cover.bitmap.width).put("height", cover.bitmap.height).put("file", target.name).put("sha256", digest))
            cover.bitmap.recycle()
        }
        File(directory, "manifest.json").writeText(JSONObject().put("version", BackdropPerfCovers.VERSION)
            .put("api", Build.VERSION.SDK_INT).put("generated", true).put("operatorArtwork", false).put("covers", entries).toString(2))
    }
}
