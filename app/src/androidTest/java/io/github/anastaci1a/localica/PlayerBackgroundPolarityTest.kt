package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real PlayerSurface background/ink regressions; no production GPU sample/readback is enabled. */
class PlayerBackgroundPolarityTest {
    @get:Rule val compose = createComposeRule()
    private val owner = PlayerBackgroundPolarityState()
    private var visual by mutableStateOf(PlayerVisual())
    private var visit by mutableStateOf("first")
    private var tall by mutableStateOf(false)
    private var topInset by mutableStateOf(0.dp)
    private var queue by mutableStateOf(false)
    private var lyrics by mutableStateOf(false)
    @Volatile private var inkComposition: PlayerInkComposition? = null
    private val ink: Color get() = inkComposition?.contentColor ?: Color.Unspecified
    private var queueInk = Color.Unspecified
    private var settings by mutableStateOf(InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground,
        artworkTransition = ArtworkTransition.BlurThrough, glass = GlassAppearance(contrast = 0f)))

    private fun cover(white: Boolean, border: Boolean = false): PlayerVisual {
        val bitmap = Bitmap.createBitmap(150, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (white) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            if (border) for (y in 210 until height) for (x in 0 until width) setPixel(x, y, android.graphics.Color.BLACK)
        }
        val palette = runBlocking { playerPalette(bitmap) }
        return PlayerVisual(bitmap.asImageBitmap(), palette,
            softArtwork = runBlocking { blurGlassSnapshot(bitmap.asImageBitmap(), bitmap.width * .035f) }, contentKey = "$white/$border")
    }

    private fun mount(initial: PlayerVisual) {
        visual = initial
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalPlayerInkCompositionObserver provides { inkComposition = it }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val available = maxHeight
                    BoxWithConstraints(Modifier.padding(top = topInset).width(300.dp)
                        .height(minOf(if (tall) 900.dp else 560.dp, available - topInset))) {
                        PlayerSurface(PlaybackState(connected = true, title = "Region", artist = "Artwork", durationMs = 120000),
                            true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                            visual = visual, artworkVisit = visit, backgroundPolarity = owner, lyricsOpen = lyrics) {
                            Text("Controls", Modifier.testTag("control-ink"))
                        }
                        if (queue) GlassPanelTheme(owner.decision.lightInk) {
                            QueueSurface(remember { Animatable(1f) }, settings, {}) {
                                val color = LocalContentColor.current
                                SideEffect { queueInk = color }
                                Text("Queue controls", Modifier.testTag("queue-ink"))
                            }
                        }
                    }
                }
            }
        } }
        current()
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(10000) {
        // Preparation/readback completion may enqueue composition while the animation clock is paused.
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        condition()
    }
    private fun current() = await {
        val observed = inkComposition
        val decision = owner.decision
        // Current describes samples, not provider composition or presented pixels. Wait for
        // that same decision/frame to reach the real provider; glyph captures still prove drawing.
        decision.confidence == PlayerBackgroundConfidence.Current && observed != null &&
            observed.decision === decision && observed.frame === owner.frame
    }
    private fun jointInkContext(): String {
        val observed = inkComposition
        fun frame(value: ArtworkBackgroundFrame?): String = value?.let {
            "${System.identityHashCode(it)} frozen=${it.frozenVisible} nativeBlur=${it.liveBlur} roles=" +
                it.roles.joinToString { role -> "${System.identityHashCode(role.identity)}:${role.visual.contentKey}@${role.weight}" }
        } ?: "none"
        return "decision=${owner.decision}; frame=${frame(owner.frame)}; " +
            "composedDecision=${observed?.decision}; composedFrame=${frame(observed?.frame)}; ink=${observed?.contentColor}; " +
            "decisionIdentity=${System.identityHashCode(owner.decision)}; " +
            "composedDecisionIdentity=${System.identityHashCode(observed?.decision)}; sampled=${owner.sampleDiagnostic}"
    }
    private fun frames(count: Int) { repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
    private fun sampleBackgroundAtTransport(): Float {
        val background = compose.onNodeWithTag("player-surface")
        val bounds = background.fetchSemanticsNode().boundsInRoot
        val transport = compose.onNodeWithTag("player-transport-row").fetchSemanticsNode().boundsInRoot
        val image = background.captureToImage().toPixelMap()
        // Empty padding in the real transport row, not a rendered foreground recording.
        val x = (transport.left - bounds.left + 3).toInt().coerceIn(0, image.width - 1)
        val y = (transport.top - bounds.top + 3).toInt().coerceIn(0, image.height - 1)
        return image[x, y].red
    }
    private fun assertRenderedInk(light: Boolean) {
        assertEquals(if (light) Color.White else Color.Black, ink)
        val image = compose.onNodeWithContentDescription("Play").captureToImage().toPixelMap()
        var inkPixels = 0
        for (y in image.height / 4 until image.height * 3 / 4) for (x in image.width / 4 until image.width * 3 / 4) {
            val red = image[x, y].red
            if (if (light) red > .9f else red < .1f) inkPixels++
        }
        assertTrue("Real transport glyph draws the selected ink", inkPixels > 10)
    }

    @Test fun whiteCoverBlackLowerBorderUsesDirectAndContinuationFootprintsAtShortTallAndTranslatedInsets() {
        val border = cover(white = true, border = true)
        assertTrue("The old middle-palette policy would select black ink", border.palette.isLight())
        mount(border)
        for (height in listOf(false, true)) for (inset in listOf(0.dp, 27.dp)) {
            compose.runOnIdle { tall = height; topInset = inset }
            compose.waitForIdle(); current()
            assertTrue(owner.decision.lightInk)
            assertRenderedInk(true)
            assertTrue("The actual backing behind transport is dark", sampleBackgroundAtTransport() < .5f)
            val measured = owner.measured
            val art = requireNotNull(measured["cover"])
            val seek = requireNotNull(measured["seek"])
            if (!height) assertTrue("Short layout includes direct cover behind seek", seek.top < art.bottom)
            else assertTrue("Tall layout includes continuation behind transport", requireNotNull(measured["transport"]).center.y > art.bottom)
            assertTrue("Measured source frame includes translated inset without a fixed bottom-half crop", measured.values.all {
                it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
            })
        }
    }

    @Test fun oppositeCoversUseVisibleOutgoingIncomingWeightsAndOnePlayerQueueDecision() {
        // The real lyrics route uses the ordinary opacity blend; Carousel mode would
        // instead start a perspective-moving artwork transition on a visual change.
        lyrics = true
        val black = cover(false); val white = cover(true)
        mount(black)
        assertRenderedInk(true); assertTrue(sampleBackgroundAtTransport() < .1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = white; visit = "white" }
        frames(4)
        assertTrue("Incoming target is not yet the visible owner", owner.decision.lightInk)
        assertTrue(sampleBackgroundAtTransport() < .4f)
        frames(26); current()
        assertFalse(owner.decision.lightInk)
        assertRenderedInk(false); assertTrue(sampleBackgroundAtTransport() > .9f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { queue = true }
        compose.waitForIdle()
        assertEquals("Both real surfaces use the same page decision", ink.red > .5f, queueInk.red > .5f)
        val image = compose.onNodeWithTag("queue-ink").captureToImage().toPixelMap()
        assertTrue((0 until image.height).any { y -> (0 until image.width).any { x -> image[x, y].red < .2f } })
    }

    @Test fun interruptedFrozenRoleIsExplicitlyStaleAndRecoversOnlyFromLiveSuccessor() {
        // The real lyrics route uses the ordinary opacity blend; Carousel mode would
        // instead start a perspective-moving artwork transition on a visual change.
        lyrics = true
        val black = cover(false); val white = cover(true)
        mount(black)
        val verified = owner.decision.lightInk
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = white; visit = "white" }
        frames(6)
        compose.runOnIdle { visual = black; visit = "interrupted-black" }
        frames(2)
        await { owner.frame?.frozenVisible == true }
        await { owner.decision.unavailable == PlayerBackgroundUnavailable.FrozenSampleUnavailable }
        assertEquals(PlayerBackgroundConfidence.Stale, owner.decision.confidence)
        assertEquals(verified, owner.decision.lightInk)
        // This intentionally does not assert a current/frozen pixel match: that acceptance remains open.
        frames(32); current()
        assertTrue(owner.decision.lightInk)
        assertNull(owner.decision.unavailable)
        assertRenderedInk(true); assertTrue(sampleBackgroundAtTransport() < .1f)
    }

    @Test fun maximumTrimDoesNotLockAColdWhiteBackgroundToWhiteInk() {
        settings = settings.copy(glass = settings.glass.copy(contrast = .5f))
        tall = true
        mount(cover(true))
        compose.waitUntil(10000) { !owner.decision.lightInk && sampleBackgroundAtTransport() > .9f }
        assertRenderedInk(false)
        compose.mainClock.autoAdvance = false
        repeat(12) { frames(1); assertFalse("Polarity/toned-background feedback must settle", owner.decision.lightInk) }
        val black = cover(false)
        compose.runOnIdle { visual = black; visit = "maximum-trim-black" }
        frames(32); current()
        if (ink != Color.White) println("maximumTrim ink mismatch: ${jointInkContext()}")
        assertRenderedInk(true)
        repeat(12) { frames(1); assertTrue(owner.decision.lightInk) }
    }

    @Test fun nativeBlurRetainsVerifiedInkAsDistinctUnavailableThenRecovers() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        settings = settings.copy(artworkTransition = ArtworkTransition.BlurThrough)
        val black = cover(false); val white = cover(true)
        mount(black)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = white; visit = "blurred-white" }
        frames(4)
        await { owner.decision.unavailable == PlayerBackgroundUnavailable.NativeBlurSampleUnavailable }
        assertEquals(PlayerBackgroundConfidence.Stale, owner.decision.confidence)
        assertTrue(owner.decision.lightInk)
        // No assertion claims these held pixels match the native blurred role.
        frames(30); current()
        assertFalse(owner.decision.lightInk)
        assertRenderedInk(false)
    }

    @Test fun sameCoverVisitKeepsTheRealFadeAndItsPolarityAndRapidSuccessorCannotPublishOldInk() {
        // The real lyrics route uses the ordinary opacity blend; Carousel mode would
        // instead start a perspective-moving artwork transition on a visual change.
        lyrics = true
        val white = cover(true); val black = cover(false)
        mount(white)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visit = "same-cover-new-visit" }
        frames(5)
        assertTrue("A new occurrence still composes both fade roles", requireNotNull(owner.frame).roles.size >= 2)
        assertFalse(owner.decision.lightInk)
        assertTrue(sampleBackgroundAtTransport() > .9f)
        compose.runOnIdle { visual = black; visit = "black" }
        frames(2)
        compose.runOnIdle { visual = white; visit = "new-white" }
        frames(2)
        compose.runOnIdle { visual = black; visit = "latest-black" }
        frames(36); current()
        assertTrue(owner.decision.lightInk)
        assertRenderedInk(true); assertTrue(sampleBackgroundAtTransport() < .1f)
    }
}
