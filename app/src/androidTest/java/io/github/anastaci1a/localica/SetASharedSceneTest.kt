package io.github.anastaci1a.localica

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import io.github.anastaci1a.localica.ui.KeyedDragState
import io.github.anastaci1a.localica.ui.KeyedDragSlots
import io.github.anastaci1a.localica.ui.KeyedDragOverlay
import io.github.anastaci1a.localica.ui.sharedKeyedDrag
import io.github.anastaci1a.localica.ui.components.KeyedDragContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.components.rememberPresence
import io.github.anastaci1a.localica.ui.components.rememberPlayerScene
import io.github.anastaci1a.localica.ui.components.rememberPlayerBackground
import io.github.anastaci1a.localica.ui.components.rememberPlayerCropRetention
import io.github.anastaci1a.localica.ui.components.playerLyricsData
import io.github.anastaci1a.localica.ui.components.PlayerScenePresentation
import io.github.anastaci1a.localica.ui.components.HeadingContent
import io.github.anastaci1a.localica.ui.components.LyricsContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exact inline pixels and outgoing-key retention against the delivered Compose content recipes. */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
class SetASharedSceneTest {
    @get:Rule val compose = localicaComposeRule()
    private var title by mutableStateOf("A")

    @Composable private fun Caption(text: String, alpha: () -> Float = { 1f }) {
        Text(text, Modifier.graphicsLayer { this.alpha = alpha() }, color = Color.Black)
    }
    private fun fixture(lyrics: Boolean, reduced: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Row(Modifier.safeDrawingPadding()) {
                Box(Modifier.size(140.dp, 64.dp).background(Color.White).testTag("reference")) {
                    if (lyrics) {
                        val transition = updateTransition(title)
                        transition.Crossfade(animationSpec = tween(if (reduced) 0 else 300)) { shown ->
                            val source = transition.animateFloat(transitionSpec = { tween(if (reduced) 0 else 300) }) { if (it == shown) 1f else 0f }
                            Caption(shown) { source.value }
                        }
                    } else Crossfade(title, animationSpec = tween(if (reduced) 0 else 165)) { Caption(it) }
                }
                Box(Modifier.size(140.dp, 64.dp).background(Color.White).testTag("shared")) {
                    if (lyrics) LyricsContent(title, reduced) { shown, source -> Caption(shown, source) }
                    else HeadingContent(title, reduced, false) { Caption(it) }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
    }
    private fun exactPixels() {
        fun pixels(tag: String): IntArray {
            val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
        val expected = pixels("reference")
        val actual = pixels("shared")
        assertArrayEquals(expected, actual)
        assertTrue("The fixture must draw visible ink", actual.any { it != android.graphics.Color.WHITE })
    }
    @Test fun headingRetainsOutgoingKeysDuringInterruption() {
        fixture(false, false); exactPixels()
        compose.runOnIdle { title = "B" }
        compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
        compose.onAllNodes(hasText("A") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(1)
        exactPixels()
        compose.runOnIdle { title = "C" }
        for (millis in listOf(16L, 48L, 64L, 200L)) {
            compose.mainClock.advanceTimeBy(millis); compose.waitForIdle(); exactPixels()
        }
        compose.onAllNodes(hasText("A") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(0)
        compose.onAllNodes(hasText("C") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(1)
    }
    @Test fun lyricsRetainsOutgoingKeysAndSharesTheOriginalSourceAlpha() {
        fixture(true, false)
        compose.runOnIdle { title = "B" }
        for (millis in listOf(32L, 48L, 80L)) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle(); exactPixels() }
        compose.runOnIdle { title = "C" }
        for (millis in listOf(32L, 48L, 80L, 400L)) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle(); exactPixels() }
    }
    @Test fun reducedLyricsRemainVisibleAndRetireTheOldKey() {
        fixture(true, true)
        compose.runOnIdle { title = "B" }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle(); exactPixels()
        compose.onAllNodes(hasText("B") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(1)
        compose.onAllNodes(hasText("A") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(0)
    }
    @Test fun reducedHeadingRemainsVisibleAndRetiresTheOldKey() {
        fixture(false, true)
        compose.runOnIdle { title = "B" }
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle(); exactPixels()
        compose.onAllNodes(hasText("B") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(1)
        compose.onAllNodes(hasText("A") and hasAnyAncestor(hasTestTag("shared"))).assertCountEquals(0)
    }
    @Test fun actionSlotRoleUsesRootCoordinatesAndCapturesDestinationsAtTheMotionFrame() {
        lateinit var state: KeyedDragState<String, Unit>
        var pixels = 0f
        var target by mutableStateOf(Offset(130f, 120f))
        var commits = 0
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current.density
            pixels = density
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            state = remember {
                KeyedDragState<String, Unit>(grid, scope, { it }, contentRole = KeyedDragContent.ActionSlots,
                    presentation = { Unit }, slots = KeyedDragSlots<String, Unit>(
                        destinations = { mapOf("action" to Rect(state.viewportOrigin + target * density, Size(60f * density, 60f * density))) },
                        sourceBounds = { mapOf("action" to Rect(state.viewportOrigin + Offset(20f, 20f) * density, Size(60f * density, 60f * density))) },
                        overlayTag = "shared-action"))
                    .also { it.items = listOf("action") }
            }
            Box(Modifier.safeDrawingPadding().padding(48.dp)) {
                Box(Modifier.size(240.dp).testTag("action-host")
                    .onGloballyPositioned { state.viewportOrigin = it.positionInRoot() }
                    .sharedKeyedDrag(state, true, false, pickupHaptic = true, itemAt = state::hit,
                        commit = { _, _, complete -> commits++; complete(true) })) {
                    Box(Modifier.offset(20.dp, 20.dp).size(60.dp).background(Color.Gray)) { Caption("Source") }
                    KeyedDragOverlay(state, false, countDescription = { "Dragging action" }) { _, _ -> Caption("X") }
                }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("action-host").performTouchInput {
            down(Offset(50f, 50f) * pixels)
            advanceEventTime(650)
            moveBy(Offset(4f, 0f)) // Below slop: the action role begins at the recognized hold.
        }
        val release = compose.runOnIdle {
            val d = requireNotNull(state.drag)
            assertEquals(state.viewportOrigin + Offset(50f, 50f) * pixels + Offset(4f, 0f), d.pointer)
            d.pointer
        }
        compose.onNodeWithTag("shared-action").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("action-host").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
        fun pose() = compose.runOnIdle {
            val d = requireNotNull(state.drag)
            Triple(d.landing.value, d.destinations!!.getValue("action").center, d.pointer)
        }
        val before = pose()
        assertEquals(lerp(release, before.second, before.first), before.third)
        compose.runOnIdle { target = Offset(110f, 140f) }
        compose.waitForIdle()
        assertEquals("A layout-only target change waits for the motion frame", before.third, pose().third)
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val after = pose()
        assertEquals(lerp(release, after.second, after.first), after.third)
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.runOnIdle { assertNull(state.drag); assertEquals(1, commits) }
    }

    @Test fun playerSceneFactoryKeepsItsIdentityAndUpdatesDiscreteIntent() {
        var retain by mutableStateOf(false)
        var first: PlayerScenePresentation? = null
        lateinit var latest: PlayerScenePresentation
        compose.setContent { LocalicaTheme {
            val player = rememberPresence("factory-player", false)
            val lyrics = rememberPresence("factory-lyrics", false)
            val background = rememberPlayerBackground(0f)
            val crop = rememberPlayerCropRetention(0f)
            val currentIntent = retain
            val scene = rememberPlayerScene(player.view, lyrics.view, background, crop,
                lyricsOpen = { currentIntent }, swipeActive = { false }, swipeOffset = { 0f })
            SideEffect { if (first == null) first = scene; latest = scene }
            playerLyricsData(scene) { Text("Retained lyrics", Modifier.testTag("retained-lyrics-data")) }
        } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("retained-lyrics-data").assertCountEquals(0)
        compose.runOnIdle { retain = true }
        compose.waitForIdle()
        compose.onAllNodesWithTag("retained-lyrics-data").assertCountEquals(1)
        compose.runOnIdle { assertSame(first, latest); retain = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag("retained-lyrics-data").assertCountEquals(0)
        compose.runOnIdle { assertSame(first, latest) }
    }

}
