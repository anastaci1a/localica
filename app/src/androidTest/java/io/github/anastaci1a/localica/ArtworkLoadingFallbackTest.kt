package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test

/** The music note marks only known-missing art; pending art and its reveal stay on the shimmer (#524). */
class ArtworkLoadingFallbackTest {
    @get:Rule val compose = createComposeRule()
    private val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }

    private fun count(tag: String, expected: Int) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(expected)

    @Test fun revealFramesNeverDropFromTheShimmerToTheMissingArtNote() {
        var image by mutableStateOf<Bitmap?>(null)
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            ArtworkImage(image?.asImageBitmap(), loading, Modifier.size(64.dp).testTag("reveal"), RectangleShape)
        } }
        compose.mainClock.advanceTimeBy(64)
        count("artwork-loading", 1); count("artwork-missing", 0)
        // The bitmap and the end of loading land in one composition, as in MediaArtwork.
        compose.runOnIdle { image = red; loading = false }
        repeat(24) { frame ->
            compose.mainClock.advanceTimeBy(16)
            count("artwork-missing", 0)
            if (frame < 12) count("artwork-loading", 1)
        }
        compose.mainClock.advanceTimeBy(400)
        count("artwork-missing", 0); count("artwork-loading", 0)
    }

    @Test fun cacheMissFirstFrameIsTheShimmerWithoutTheNote() {
        compose.mainClock.autoAdvance = false
        val pending = CoverProvider { _, _, _ -> awaitCancellation() }
        compose.setContent { LocalicaTheme {
            MediaArtwork(listOf("content://test.missing/never-cached-524"), Modifier.size(64.dp), pending)
        } }
        compose.mainClock.advanceTimeByFrame()
        count("artwork-loading", 1); count("artwork-missing", 0)
        repeat(10) { compose.mainClock.advanceTimeBy(16); count("artwork-missing", 0) }
    }

    @Test fun deferredFarJourneyArtworkStaysOnTheShimmer() {
        compose.mainClock.autoAdvance = false
        val transit = ArtworkTransit()
        val pending = CoverProvider { _, _, _ -> awaitCancellation() }
        compose.setContent { LocalicaTheme {
            LaunchedEffect(Unit) { transit.during(true) { awaitCancellation() } }
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                MediaArtwork(listOf("content://test.missing/deferred-524"), Modifier.size(64.dp), pending)
            }
        } }
        repeat(6) { compose.mainClock.advanceTimeBy(16); count("artwork-missing", 0); count("artwork-loading", 1) }
    }

    @Test fun confirmedMissingArtKeepsTheNote() {
        val none = CoverProvider { _, _, _ -> null }
        compose.setContent { LocalicaTheme {
            MediaArtwork(listOf("content://test.missing/none-524"), Modifier.size(64.dp), none)
            MediaArtwork(emptyList(), Modifier.size(64.dp), none)
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artwork-missing", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        count("artwork-loading", 0)
    }
}
