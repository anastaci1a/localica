package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkOccurrenceTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameDecodedCoverStillBlursForANewVisitAndLoadingDoesNotStartItEarly() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) for (x in 0 until 128)
            bitmap.setPixel(x, y, if ((x / 16 + y / 16) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val image = bitmap.asImageBitmap()
        val soft = runBlocking { blurGlassSnapshot(image, 6f) }
        val original = PlayerVisual(image, softArtwork = soft, contentKey = "shared-pixels")
        var visual by mutableStateOf(original)
        var visit by mutableStateOf("first")
        compose.setContent { LocalicaTheme {
            ArtworkBlend(visual, Modifier.size(192.dp).testTag("blend"), 600, occurrence = visit) { frame ->
                Image(requireNotNull(frame.artwork), null, Modifier.fillMaxSize())
            }
        } }
        fun pixels() = compose.onNodeWithTag("blend").captureToImage().toPixelMap()
        val before = pixels()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visit = "second"; visual = original.copy(loading = true) }
        compose.mainClock.advanceTimeBy(300)
        val waiting = pixels()
        for (y in 2 until before.height - 2 step 7) for (x in 2 until before.width - 2 step 7)
            assertEquals("Loading retains every sampled outgoing pixel", before[x, y], waiting[x, y])
        compose.runOnIdle { visual = original }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300)
        val middle = pixels()
        var changed = 0
        var sum = 0f
        for (y in 2 until middle.height - 2 step 3) for (x in 2 until middle.width - 2 step 3) {
            val value = middle[x, y]
            assertEquals("An opaque transition never exposes backing", 1f, value.alpha, .001f)
            if (kotlin.math.abs(value.red - before[x, y].red) > .08f) changed++
            sum += value.red
        }
        assertTrue("Identical bitmap visits must visibly blur, not snap", changed > 50)
        val samples = ((2 until middle.height - 2 step 3).count() * (2 until middle.width - 2 step 3).count())
        assertTrue("Transition cannot flash white", sum / samples < .65f)
        compose.mainClock.advanceTimeBy(700)
        val settled = pixels()
        for (y in 2 until before.height - 2 step 7) for (x in 2 until before.width - 2 step 7)
            assertEquals("The shared sharp pixels return", before[x, y], settled[x, y])
        compose.mainClock.autoAdvance = true
    }
}
