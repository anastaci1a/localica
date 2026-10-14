package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.ArtworkLayout
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", album = "Album", durationMs = 120000)

    @Test fun scrubbingCanCancelAtTheLivePositionWithoutSeekingOrInterruptingPlayback() {
        var position by mutableStateOf(20000L)
        var seeks = 0
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state.copy(positionMs = position, isPlaying = true), true, {}, InterfaceSettings(), remember { Animatable(1f) },
                {}, { seeks++ }, {}, {}, {}, {}, visual = PlayerVisual())
        } } }
        val seek = compose.onNodeWithContentDescription("Seek")
        val bounds = seek.fetchSemanticsNode().boundsInRoot
        seek.performTouchInput { down(center); moveBy(Offset(60f, 0f), delayMillis = 48) }
        fun previewBrightness(): Float {
            val image = seek.captureToImage().toPixelMap()
            return image[(image.width * .4f).toInt(), image.height / 2].red
        }
        val pendingBrightness = previewBrightness()
        val cancel = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { position = 23000L }
        seek.performTouchInput { moveTo(cancel.center - bounds.topLeft, delayMillis = 160) }
        assertTrue("Hovering X previews the live position before release", previewBrightness() < pendingBrightness - .15f)
        compose.runOnIdle { assertEquals("Previewing cancellation never seeks", 0, seeks) }
        seek.performTouchInput { moveTo(Offset(width * .8f, center.y), delayMillis = 160) }
        assertTrue("Leaving X restores the pending finger position", previewBrightness() > pendingBrightness - .08f)
        seek.performTouchInput { moveTo(cancel.center - bounds.topLeft, delayMillis = 160); up() }
        compose.onNodeWithTag("cancel-seek").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("0:23").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, seeks) }
        seek.performTouchInput { down(center) }
        val secondCancel = compose.onNodeWithTag("cancel-seek").fetchSemanticsNode().boundsInRoot
        seek.performTouchInput { moveTo(secondCancel.center - bounds.topLeft, delayMillis = 160); up() }
        compose.onNodeWithTag("cancel-seek").assertDoesNotExist()
        compose.onNodeWithText("0:23").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, seeks) }
        seek.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(40000f) }
        compose.runOnIdle { assertEquals("An accessible seek still works after cancellation", 1, seeks) }
        seek.performTouchInput { swipe(center, Offset(width * .8f, center.y), durationMillis = 250) }
        compose.runOnIdle { assertEquals(2, seeks) }
    }

    @Test fun lyricsBubbleWidensContinuouslyAndReversesWithPersistentSourceIcon() {
        var lyrics by mutableStateOf(false)
        var collapsed = false
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, { collapsed = !it }, InterfaceSettings(), remember { Animatable(1f) },
                {}, {}, {}, {}, {}, {}, lyricsOpen = lyrics, onLyrics = { lyrics = it },
                visual = PlayerVisual())
        } } }
        fun pill() = compose.onNodeWithTag("player-lyrics-pill").fetchSemanticsNode().boundsInRoot
        val circle = pill()
        val originalIcon = compose.onNodeWithContentDescription("Show lyrics").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Show lyrics").performClick()
        compose.mainClock.advanceTimeBy(96)
        val partial = pill()
        assertTrue("The same bubble widens gradually", partial.width > circle.width && partial.width < circle.width * 2)
        assertEquals("Right edge remains anchored", circle.right, partial.right, 1f)
        compose.mainClock.advanceTimeBy(400)
        val expanded = pill()
        val source = compose.onNodeWithContentDescription("Lyrics sources").fetchSemanticsNode().boundsInRoot
        assertTrue("Persistent lyrics icon moves left", source.center.x < originalIcon.center.x - circle.width * .8f)
        compose.onNodeWithContentDescription("Hide lyrics").performClick()
        compose.mainClock.advanceTimeBy(96)
        assertTrue("Closing retains an intermediate pill", pill().width > circle.width && pill().width < expanded.width)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(circle.width, pill().width, 1f)
        compose.onNodeWithContentDescription("Show lyrics").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide lyrics").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Collapse player").performClick()
        compose.runOnIdle { assertTrue(collapsed) }
    }

    @Test fun lyricsTransportCentersOnTrackAndShelfRatherThanTimestampLabels() {
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(), remember { Animatable(1f) },
                {}, {}, {}, {}, {}, {}, lyricsOpen = true, visual = PlayerVisual())
            QueueSurface(remember { Animatable(0f) }, InterfaceSettings(), {}) {}
        } } }
        val seek = compose.onNodeWithContentDescription("Seek").fetchSemanticsNode().boundsInRoot
        val shelf = compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot
        for (label in listOf("Previous", "Play", "Next")) {
            val control = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertEquals("$label centers between the track and shelf", (seek.center.y + shelf.top) / 2, control.center.y, 1.5f)
        }
    }

    @Test fun lightArtworkContinuationDoesNotRevealADarkTemporaryPalette() {
        // A dark blue average and light lower artwork exaggerate the BA3 handoff.
        val wash = Bitmap.createBitmap(ArtworkWash.WIDTH, ArtworkWash.HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.LTGRAY)
        }
        val blue = Color(0xFF102050)
        val visual = PlayerVisual(palette = PlayerPalette(blue, blue, blue, wash = wash, softWash = wash))
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground),
                progress, {}, {}, {}, {}, {}, {}, visual = visual)
        } } }
        for (phase in listOf(.6f, .75f, .85f, .94f, 1f, .85f, .6f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(phase) } }
            val pixels = compose.onNodeWithTag("player-surface").captureToImage().toPixelMap()
            val color = pixels[(pixels.width * .94f).toInt(), (pixels.height * .72f).toInt()]
            assertTrue("The final light continuation is already present at $phase: $color", color.red > .65f)
            assertTrue("No temporary blue cast at $phase: $color", kotlin.math.abs(color.red - color.blue) < .08f)
        }
    }

    @Test fun heldLyricsCollapseAlreadyRestoresArtworkBeforeRelease() {
        val cover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            var expanded by remember { mutableStateOf(true) }
            PlayerSurface(state, expanded, { expanded = it }, InterfaceSettings(), remember { Animatable(1f) },
                {}, {}, {}, {}, {}, {}, lyricsOpen = true, visual = PlayerVisual(cover))
        } } }
        compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("player-surface").performTouchInput {
            down(Offset(8f, centerY))
            repeat(30) { moveBy(Offset(0f, height * .014f), delayMillis = 30) }
        }
        compose.mainClock.advanceTimeBy(32)
        val image = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).assertIsDisplayed().captureToImage().toPixelMap()
        val color = image[image.width / 2, image.height / 2]
        assertTrue("Artwork must already be visible during the held gesture: $color", color.red > color.blue + .2f)
        compose.onNodeWithTag("player-surface").performTouchInput { advanceEventTime(500); up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun glassStaysTranslucentUntilLateExpansionAndTransportPersists() {
        val progress = Animatable(0f)
        var behind by mutableStateOf(Color.Red)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(behind)) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = PlayerVisual()) { Text("Custom action") }
        } } }
        compose.runOnIdle { scope.launch { progress.snapTo(.55f) } }
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seek").assertDoesNotExist()
        compose.onNodeWithText("Custom action").assertDoesNotExist()
        fun panelColor(): Color = compose.onNodeWithTag("player-surface").captureToImage().let {
            it.toPixelMap()[it.width * 9 / 10, it.height / 2]
        }
        val before = panelColor()
        compose.runOnIdle { behind = Color.Blue }
        val after = panelColor()
        assertTrue("Expanding glass still transmits browsing", before.red > after.red + .2f)
        assertTrue("Expanding glass still transmits browsing", after.blue > before.blue + .2f)
        compose.runOnIdle { scope.launch { progress.snapTo(.96f) } }
        compose.onNodeWithContentDescription("Seek").assertIsDisplayed()
        compose.onNodeWithText("Custom action").assertIsDisplayed()
        val complete = panelColor()
        compose.runOnIdle { behind = Color.Red }
        val opaque = panelColor()
        assertEquals("Artwork backing becomes opaque near full size", complete.red, opaque.red, .02f)
        assertEquals("Artwork backing becomes opaque near full size", complete.blue, opaque.blue, .02f)
    }

    @Test fun glassFillsTheSharedRoundedOutlineDuringHeldExpansionAndReversal() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        lateinit var backdrop: FrostedBackdrop
        compose.setContent { LocalicaTheme {
            // Isolate outline coverage from the independently tested brightness trim.
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(whiteTint = 1f, shadowStrength = 0f, contrast = 0f)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Magenta)) {
                    scope = rememberCoroutineScope()
                    backdrop = rememberFrostedBackdrop(Unit)
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(backdrop).background(Color.Magenta))
                    PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {},
                        visual = PlayerVisual(), glassBackdrop = backdrop)
                }
            }
        } }
        // The material now colors its sampled scene rather than painting an
        // unrelated coating; supply the same recorded backing as the real player.
        compose.waitUntil(5000) {
            if (android.os.Build.VERSION.SDK_INT >= 33) backdrop.layer.size.width > 0
            else backdrop.lensSnapshot != null
        }
        for (p in listOf(.15f, .5f, .15f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            val pixels = compose.onNodeWithTag("player-surface").captureToImage().toPixelMap()
            // Inside the rounded rectangle but outside the old, taller capsule's corner.
            val edge = pixels[10, (pixels.height * .3f).toInt()]
            assertTrue("Glass must reach the shared outline at $p: $edge", edge.green > .7f)

        }
    }

    @Test fun handleKeepsItsInsetAndLeavesBeforeItCanOverlapMiniPlayerControls() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = PlayerVisual())
        } } }
        fun at(value: Float): Pair<Float, Float> {
            compose.runOnIdle { scope.launch { progress.snapTo(value) } }
            val panel = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithTag("player-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return panel.top to handle.top
        }
        val full = at(1f)
        val held = at(.85f)
        assertEquals("Handle keeps its inset from the moving edge", full.second - full.first, held.second - held.first, 1f)
        assertEquals("Handle travels at the same speed as the edge", held.first - full.first, held.second - full.second, 1f)
        compose.runOnIdle { scope.launch { progress.snapTo(.65f) } }
        compose.onNodeWithTag("player-handle", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
    }

    @Test fun expandingFullBackgroundSoftensCoverEdgesButFloatingArtworkStaysDefined() {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(192 * 192) { if ((it % 192) / 3 % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK }, 0, 192, 0, 0, 192, 192)
        }.asImageBitmap()
        val soft = kotlinx.coroutines.runBlocking { blurGlassSnapshot(bitmap, 12f) }
        val visual = PlayerVisual(bitmap, softArtwork = soft)
        val progress = Animatable(0f)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(Color.Gray)) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = layout), progress, {}, {}, {}, {}, {}, {}, visual = visual)
        } } }
        compose.runOnIdle { scope.launch { progress.snapTo(.65f) } }
        fun contrasts(): Pair<Float, Float> {
            val image = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).captureToImage().toPixelMap()
            fun contrast(from: Float, to: Float): Float {
                val samples = ((image.width * from).toInt()..(image.width * to).toInt()).map { image[it, image.height / 2].red }
                return samples.max() - samples.min()
            }
            return contrast(.03f, .1f) to contrast(.4f, .6f)
        }
        val softened = contrasts()
        assertTrue("Center retains detail", softened.second > .8f)
        assertTrue("Edge loses sharp detail while expanding: $softened", softened.first < softened.second * .65f)
        for (phase in listOf(.88f, .94f, .98f, 1f, .94f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(phase) } }
            val late = contrasts()
            assertTrue("No hard edge returns at the final handoff or its reversal ($phase): $late", late.first < late.second * .65f)
        }
        compose.runOnIdle { layout = ArtworkLayout.Floating }
        val floating = contrasts()
        assertTrue("Floating artwork keeps a defined edge: $floating", floating.first > floating.second * .8f)
    }
}
