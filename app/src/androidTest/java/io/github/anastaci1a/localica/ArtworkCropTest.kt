package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.editing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkCropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rectangularImageRequiresAnExplicitSquareCropBeforeItCanBeApplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = java.io.File(context.cacheDir, "crop-source.png")
        Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            .let { bitmap -> input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        var chosen: String? = null
        compose.setContent { LocalicaTheme { ArtworkCrop(android.net.Uri.fromFile(input).toString(), {}, { chosen = it }) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("artwork-crop").fetchSemanticsNodes().isNotEmpty() }
        assertNull(chosen)
        compose.onNodeWithText("Use square crop").performClick()
        compose.waitUntil(10000) { chosen != null }
        val output = context.contentResolver.openInputStream(android.net.Uri.parse(chosen))!!.use(BitmapFactory::decodeStream)
        assertNotNull(output)
        assertEquals(300, output!!.width)
        assertEquals(output.width, output.height)
        assertEquals(android.graphics.Color.RED, output.getPixel(150, 150))
        output.recycle(); input.delete()
        java.io.File(android.net.Uri.parse(chosen).path!!).delete()
    }

    @Test fun restartingMarksOnlyUnfinishedJobsInterruptedAndPreservesFileOutcomes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("tag-jobs", 0)
        val original = preferences.getString("jobs", null)
        try {
            val history = TagJobHistory(context)
            history.write(listOf(TagJob("pending", 3, outcomes = listOf("first.mp3: Saved and verified")),
                TagJob("complete", 1, "Complete", listOf("other.mp3: Saved and verified"))))
            val restored = TagJobHistory(context).read()
            assertTrue(restored[0].status.startsWith("Interrupted"))
            assertEquals(listOf("first.mp3: Saved and verified"), restored[0].outcomes)
            assertEquals("Complete", restored[1].status)
        } finally { preferences.edit().putString("jobs", original).commit() }
    }
}
