package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** #704 under App Reduced Motion, through the production chain: the root's settings, then the effective
 * Reduced policy that LocalicaScreen provides, then a real browse list's scrollbar and its motion owner. */
class ScrollbarReducedRootTest {
    @get:Rule val compose = applicationRootComposeRule()
    private lateinit var state: LazyGridState
    private var restColour = Color.Unspecified

    private fun host(reduced: Boolean) {
        val root = LocalicaUiRootState(InterfaceSettings().copy(reducedMotion = reduced))
        val items = List(2000) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }
        compose.setContent { LocalicaUiRoot(root) { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides rememberEffectiveReducedMotion(root.settings.value.reducedMotion)) {
                restColour = MaterialTheme.colorScheme.outlineVariant
                state = rememberLazyGridState()
                Box(Modifier.safeDrawingPadding().height(640.dp).width(360.dp).background(Color.White)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().testTag("rows"), state = state) {
                        items(items.size + 1) { index -> Text("Row $index", Modifier.height(64.dp)) }
                    }
                    BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(Alignment.CenterEnd))
                }
            }
        } } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // The fixture starts idle: play out its fade so each test begins from a faded scrollbar.
        compose.mainClock.advanceTimeBy(ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 200L)
        assertTrue("Idle fixture starts faded", opacity() <= .02f)
    }

    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun opacity(): Float {
        val pixels = compose.onNodeWithTag("browse-scrollbar-thumb").captureToImage().toPixelMap()
        val seen = pixels[pixels.width / 2, pixels.height / 2].red
        return ((1f - seen) / (1f - restColour.red)).coerceIn(0f, 1f)
    }
    private fun scrollNudge() = compose.runOnIdle { state.dispatchRawDelta(1f) }

    @Test fun appReducedRevealIsImmediateAndThenFollowsTheBaseIdleFade() {
        host(reduced = true)
        scrollNudge(); frames(1)
        assertTrue("Reduced reveal has no fade-in: first frame ${opacity()}", opacity() >= .98f)
        val start = compose.mainClock.currentTime
        // Visible, untouched by the fade, until the idle deadline.
        while (compose.mainClock.currentTime - start < ScrollbarSpec.idleMillis - 100L) {
            frames(1); assertTrue("Visible before the idle deadline: ${opacity()}", opacity() >= .98f)
        }
        // Then the base fade: monotonic decrease to zero over the fade duration.
        val fade = mutableListOf<Pair<Long, Float>>()
        while (compose.mainClock.currentTime - start < ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 100L) {
            frames(1); fade += compose.mainClock.currentTime - start to opacity()
        }
        assertTrue("Fade-out is monotonic: $fade", fade.zipWithNext().all { (a, b) -> b.second <= a.second + 1.5f / 255f })
        val gone = fade.first { it.second <= .02f }.first
        val begun = fade.firstOrNull { it.second < .98f }?.first ?: error("No fade: $fade")
        assertTrue("Fade begins at the idle deadline, not before: $begun", begun >= ScrollbarSpec.idleMillis - 40L)
        assertTrue("Fade lasts about ${ScrollbarSpec.fadeMillis} ms: $begun to $gone", gone - begun >= ScrollbarSpec.fadeMillis * .6f)
    }

    @Test fun appReducedKeepsItVisibleWhileAFingerRestsOnTheList() {
        host(reduced = true)
        // A real drag keeps the list scrolling while the finger rests (a programmatic nudge does not).
        compose.onNodeWithTag("rows").performTouchInput {
            down(center); moveBy(Offset(0f, -viewConfiguration.touchSlop * 4), delayMillis = 200)
        }
        frames(1)
        val held = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - held < ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 800L) {
            compose.mainClock.advanceTimeBy(100L)
            assertTrue("A resting finger keeps it fully visible: ${opacity()}", opacity() >= .98f)
        }
        compose.onNodeWithTag("rows").performTouchInput { up() }
        val released = compose.mainClock.currentTime
        compose.mainClock.advanceTimeBy(ScrollbarSpec.idleMillis - 100L)
        assertTrue("Visible until the idle delay after release", opacity() >= .98f)
        compose.mainClock.advanceTimeBy(ScrollbarSpec.fadeMillis + 300L)
        assertTrue("Faded after release, idle delay and fade (${compose.mainClock.currentTime - released} ms)", opacity() <= .02f)
    }

    @Test fun appReducedGripIsInstant() = gripAfterTwoFrames(reduced = true)
    @Test fun standardGripStillEasesAfterTwoFrames() = gripAfterTwoFrames(reduced = false)

    private fun gripAfterTwoFrames(reduced: Boolean) {
        run {
            host(reduced)
            // The thumb takes touches only once it is past the half-opacity floor: play the fade-in out first.
            scrollNudge(); compose.mainClock.advanceTimeBy(ScrollbarSpec.fadeMillis + 64L)
            val rest = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.width
            compose.onNodeWithTag("browse-scrollbar-thumb").performTouchInput { down(center) }
            frames(2)
            val early = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.width
            compose.mainClock.advanceTimeBy(600L)
            val held = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.width
            assertTrue("Held is wider than rest ($rest to $held)", held > rest + .5f)
            if (reduced) assertEquals("Reduced grip is at its held width within two frames", held, early, .5f)
            else assertTrue("Standard grip is still easing after two frames ($early of $held)", early < held - .5f)
            compose.onNodeWithTag("browse-scrollbar-thumb").performTouchInput { up() }
        }
    }
}
