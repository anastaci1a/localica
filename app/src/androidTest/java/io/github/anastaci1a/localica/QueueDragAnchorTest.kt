package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Check held contact registration, not merely the drawer's eventual settled endpoint. */
class QueueDragAnchorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun handleRetainsItsAnchorAcrossFramesPauseAndReversal() = heldPull(fromHandle = true)
    @Test fun freshTopListPullRetainsItsAnchorAcrossFramesPauseAndReversal() = heldPull(fromHandle = false)
    @Test fun freshTopGalleryPullRetainsItsAnchorAcrossFramesPauseAndReversal() =
        heldPull(fromHandle = false, gallery = true)

    private fun heldPull(fromHandle: Boolean, gallery: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = BrowsePreferences(context)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "drawer-anchor"))
        val previous = preferences.read(route)
        val overridden = preferences.overridden(route)
        val tracks = (0 until 60).map { index -> Track("anchor:$index", "Song ${index.toString().padStart(3, '0')}",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        val queue = ListeningQueue(id = "drawer-anchor", name = "Drawer anchor",
            entries = tracks.map { QueueEntry(id = it.id, track = it) }, currentEntryId = tracks.first().id)
        val queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val progress = Animatable(1f)
        val settlements = mutableListOf<Boolean>()
        var slop = 0f
        var step = 0f
        var pointersDown = false
        preferences.write(route, BrowseStyle(quickSearch = false,
            presentation = if (gallery) Presentation.Standard else Presentation.List,
            itemsPerRow = if (gallery) 3 else null), true)
        try {
            compose.setContent { LocalicaTheme {
                slop = LocalViewConfiguration.current.touchSlop
                step = with(LocalDensity.current) { 16.dp.toPx() }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    QueueSurface(progress, InterfaceSettings(), { settlements += it }) {
                        // Use the actual queue BrowserScreen, with its grid, overscroll,
                        // selection and pinch observers, rather than a bare LazyColumn.
                        Box(Modifier.weight(1f)) {
                            QueueScreen(queueState, null, library = library, onMinimize = {})
                        }
                    }
                }
            } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-anchor:0").fetchSemanticsNodes().isNotEmpty()
            }
            val list = compose.onNodeWithTag("browser-list")
            list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                if (gallery) "Gallery, 3 columns" else "List, Normal"))
            list.performScrollToIndex(0)
            compose.waitForIdle()
            val header = compose.onNodeWithContentDescription("Close queue")
            val root = compose.onRoot()
            val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
            val headerBefore = header.fetchSemanticsNode().boundsInRoot
            val listBefore = list.fetchSemanticsNode().boundsInRoot
            // Root-local coordinates stay fixed while every child moves with the drawer.
            var finger = (if (fromHandle) headerBefore.center else
                Offset(listBefore.center.x, listBefore.top + step * 2f)) - rootOrigin
            val originalFinger = finger
            compose.mainClock.autoAdvance = false
            root.performTouchInput { down(finger) }
            pointersDown = true
            fun move(dy: Float) {
                finger += Offset(0f, dy)
                root.performTouchInput { moveTo(finger, delayMillis = 32) }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            // Account for touch slop exactly once. The initial acquisition is not the
            // invariant: all subsequent travel must retain this single fixed baseline.
            move(slop + step)
            val acquiredTop = header.fetchSemanticsNode().boundsInRoot.top
            val acquiredFinger = finger.y
            assertTrue("A fresh pull must acquire the drawer", acquiredTop > headerBefore.top + 1f)
            assertTrue("Acquisition cannot move farther than the finger", acquiredTop - headerBefore.top <=
                finger.y - originalFinger.y + 2f)
            val samples = mutableListOf<String>()
            fun assertAnchor(phase: String) {
                val top = header.fetchSemanticsNode().boundsInRoot.top
                val expected = acquiredTop + finger.y - acquiredFinger
                samples += "$phase finger=${finger.y} top=$top expected=$expected progress=${progress.value}"
                android.util.Log.i("QueueDragAnchor", "handle=$fromHandle gallery=$gallery ${samples.last()}")
                assertEquals("The original held anchor must survive $phase: $samples", expected, top, 2f)
                assertTrue("No settle before release: $samples", settlements.isEmpty())
                assertTrue("Keep this regression away from endpoint clamping", progress.value > 0f && progress.value < 1f)
            }
            repeat(4) { move(step); assertAnchor("down-$it") }
            root.performTouchInput { advanceEventTime(320) }
            repeat(20) {
                compose.mainClock.advanceTimeByFrame()
                assertAnchor("pause-frame-$it")
            }
            move(step); assertAnchor("resume")
            repeat(3) { move(-step); assertAnchor("reverse-$it") }
            repeat(3) { move(step); assertAnchor("return-$it") }
            root.performTouchInput { advanceEventTime(200); up() }
            pointersDown = false
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals("Exactly one release settles the drawer", 1, settlements.size)
        } finally {
            if (pointersDown) compose.onRoot().performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
            preferences.write(route, previous, overridden)
        }
    }
}
