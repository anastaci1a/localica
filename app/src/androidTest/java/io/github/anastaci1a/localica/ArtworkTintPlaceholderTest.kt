package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Loading artwork placeholders take their item's average colour; text skeletons stay gray (#525). */
class ArtworkTintPlaceholderTest {
    @get:Rule val compose = createComposeRule()
    private val uri = "content://test.tint/song-525"
    private val stored = 0xFF2A6FB0.toInt()
    private val pending = CoverProvider { _, _, _ -> awaitCancellation() }
    private var dark = false
    private var neutral = Color.Unspecified

    @After fun clearIndex() { ArtworkColors.publish(emptyMap()) }

    private fun pixel(tag: String) = compose.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
    private fun assertColor(expected: Int, actual: Color, tolerance: Float = .025f, message: String = "") {
        val e = Color(expected)
        assertTrue("$message expected $e, was $actual",
            abs(e.red - actual.red) < tolerance && abs(e.green - actual.green) < tolerance && abs(e.blue - actual.blue) < tolerance)
    }

    /** Reduced motion removes the sweep so the base is sampled exactly. */
    private fun content(reduced: Boolean, body: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalReducedMotion provides reduced) {
            LocalicaTheme {
                dark = MaterialTheme.colorScheme.surface.run { (red + green + blue) / 3f < .5f }
                neutral = MaterialTheme.colorScheme.surfaceContainer
                body()
            }
        }
    }

    @Test fun cacheMissFirstFrameIsTintedWithoutTheNoteAndTextSkeletonsStayGray() {
        ArtworkColors.publish(mapOf(uri to stored))
        compose.mainClock.autoAdvance = false
        content(reduced = true) {
            Column {
                Box(Modifier.size(48.dp).testTag("art")) { MediaArtwork(listOf(uri), Modifier.size(48.dp), pending) }
                BrowseLoadingShape(Modifier.size(48.dp).testTag("title-skeleton"), { 0f })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag("artwork-missing", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).assertCountEquals(2)
        assertColor(placeholderTint(stored, dark), pixel("art"), message = "First-frame artwork placeholder")
        assertColor(neutral.toArgb(), pixel("title-skeleton"), message = "Text skeleton")
    }

    @Test fun storedMissingArtShowsTheNoteImmediately() {
        ArtworkColors.publish(mapOf(uri to ArtworkColors.MISSING))
        compose.mainClock.autoAdvance = false
        content(reduced = true) { MediaArtwork(listOf(uri), Modifier.size(48.dp), io.github.anastaci1a.localica.library.EmbeddedCoverProvider) }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("artwork-missing", useUnmergedTree = true).assertCountEquals(1)
    }

    /** Startup trace: gray while the index is unknown, an eased cascade to the tint, then the art reveal. */
    @Test fun colourArrivingLaterEasesFromGrayToTintThenArtRevealsOverIt() {
        ArtworkColors.publish(emptyMap())
        val art = CompletableDeferred<Bitmap?>()
        val provider = CoverProvider { _, _, _ -> art.await() }
        compose.mainClock.autoAdvance = false
        content(reduced = false) {
            Box(Modifier.size(48.dp).testTag("art")) { MediaArtwork(listOf(uri), Modifier.size(48.dp), provider) }
        }
        compose.mainClock.advanceTimeBy(64)
        val trace = mutableListOf<Color>()
        val tint = Color(placeholderTint(stored, dark))
        fun distance(c: Color) = abs(c.red - tint.red) + abs(c.green - tint.green) + abs(c.blue - tint.blue)
        trace += pixel("art")
        assertTrue("Gray before the colour is known: ${trace[0]}", distance(trace[0]) > .15f)
        compose.runOnIdle { ArtworkColors.publish(mapOf(uri to stored)) }
        repeat(30) { compose.mainClock.advanceTimeBy(16); trace += pixel("art") }
        val distances = trace.map(::distance)
        assertTrue("Intermediate eased frames: $distances", distances.count { it > .05f && it < distances[0] - .05f } >= 3)
        assertTrue("Ends on the tint; the light sweep only adds a wash: $distances", distances.drop(1).min() < .1f)
        compose.onAllNodesWithTag("artwork-missing", useUnmergedTree = true).assertCountEquals(0)
        // The art then reveals over the tinted shimmer, still without a note frame.
        art.complete(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) })
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("artwork-missing", useUnmergedTree = true).assertCountEquals(0)
        val end = pixel("art")
        assertEquals(1f, end.red, .02f); assertEquals(0f, end.green, .02f)
    }

    @Test fun aViewOpenedAfterTheIndexIsReadyIsTintedFromItsFirstFrame() {
        ArtworkColors.publish(mapOf(uri to stored, "$uri-2" to 0xFFB08020.toInt()))
        compose.mainClock.autoAdvance = false
        var opened by mutableStateOf(false)
        content(reduced = false) {
            if (opened) Column {
                Box(Modifier.size(48.dp).testTag("a")) { MediaArtwork(listOf(uri), Modifier.size(48.dp), pending) }
                Box(Modifier.size(48.dp).testTag("b")) { MediaArtwork(listOf("$uri-2"), Modifier.size(48.dp), pending) }
            }
        }
        compose.runOnIdle { opened = true }
        compose.mainClock.advanceTimeByFrame()
        // The light sweep may cross the sample; allow its small wash.
        assertColor(placeholderTint(stored, dark), pixel("a"), .09f, "First frame of a")
        assertColor(placeholderTint(0xFFB08020.toInt(), dark), pixel("b"), .09f, "First frame of b")
    }

    /** #705: an artwork page's loading material is loading-only; its tinted missing-art fallback is unchanged. */
    @Test fun artworkPageLoadingMaterialNeverReachesTheTintedMissingFallback() {
        ArtworkColors.publish(mapOf(uri to stored))
        val page = Color(0xFF312A25)
        content(reduced = true) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White, surface = page)) {
                Column {
                    for (loading in listOf(false, true)) for (collection in listOf(false, true)) {
                        CompositionLocalProvider(LocalLoadingMaterialColor provides Color.White.copy(alpha = .04f).takeIf { collection }) {
                            Box(Modifier.size(48.dp).background(page).testTag("fallback-$loading-$collection")) {
                                // The fallback keeps its stored tint input; the loading control is untinted.
                                ArtworkPlaceholder(loading, Modifier.size(48.dp), tintUris = if (loading) null else listOf(uri))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun pixels(tag: String) = compose.onNodeWithTag(tag).captureToImage().toPixelMap().let { map ->
            (0 until map.height).flatMap { y -> (0 until map.width).map { x -> map[x, y] } } }
        assertEquals("Final missing-art fallback pixels are unchanged on an artwork page",
            pixels("fallback-false-false"), pixels("fallback-false-true"))
        assertNotEquals("The loading material itself does change while loading",
            pixels("fallback-true-false"), pixels("fallback-true-true"))
    }
}
