package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
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

    @Test fun topListDrawerKeepsOwnershipThroughOpenEndpoint() = heldPull(false, endpoints = true)
    @Test fun topGalleryDrawerKeepsOwnershipThroughOpenEndpoint() = heldPull(false, gallery = true, endpoints = true)
    @Test fun topListDrawerKeepsOwnershipThroughClosedEndpoint() = heldPull(false, endpoints = true, closedEndpoint = true)
    @Test fun topGalleryDrawerKeepsOwnershipThroughClosedEndpoint() = heldPull(false, gallery = true, endpoints = true, closedEndpoint = true)
    @Test fun listBoundaryTravelMustBeRetraced() = heldPull(false, boundaryDebt = true)
    @Test fun galleryBoundaryTravelMustBeRetraced() = heldPull(false, gallery = true, boundaryDebt = true)

    @Test fun collapsedDrawerLeavesBackgroundAboveShelfHittable() {
        val progress = Animatable(0f)
        var backgroundClicks = 0
        val settlements = mutableListOf<Boolean>()
        compose.setContent { LocalicaTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { backgroundClicks++ }.testTag("background"))
                QueueSurface(progress, InterfaceSettings(), { settlements += it }) {}
            }
        } }
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val shelf = compose.onNodeWithContentDescription("Open queue").fetchSemanticsNode().boundsInRoot
        root.performTouchInput { click(Offset(shelf.center.x, shelf.top / 2f) - origin) }
        compose.runOnIdle {
            assertEquals("Hidden drawer space must not intercept the background", 1, backgroundClicks)
            assertTrue(settlements.isEmpty())
        }
        root.performTouchInput { click(shelf.center - origin) }
        compose.runOnIdle {
            assertEquals("The visible shelf must still receive taps", listOf(true), settlements)
            assertEquals(1, backgroundClicks)
        }
    }

    private fun heldPull(fromHandle: Boolean, gallery: Boolean = false, endpoints: Boolean = false, boundaryDebt: Boolean = false, closedEndpoint: Boolean = false) {
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
        val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun record(event: String) {
            val line = "uptime=${android.os.SystemClock.uptimeMillis()} clock=${compose.mainClock.currentTime} $event"
            trace += line
        }
        // Retain the normal nonzero search-header slot. With it disabled, the
        // leading notice item is empty and does not establish a measured top.
        preferences.write(route, BrowseStyle(quickSearch = true,
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
            val initialRange = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("The fresh contact must start at the measured list top", 0f, initialRange.value(), .001f)
            if (boundaryDebt) {
                list.performScrollToIndex(if (gallery) 4 else 2)
                compose.waitForIdle()
            }
            val header = compose.onNode(hasContentDescription("Close queue") or hasContentDescription("Open queue"))
            val root = compose.onRoot()
            val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
            val headerBefore = header.fetchSemanticsNode().boundsInRoot
            val firstSong = if (boundaryDebt) list.fetchSemanticsNode().boundsInRoot
                else compose.onNodeWithTag("media-anchor:0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot
            assertTrue("Start on song content below the floating search control", firstSong.center.y > search.bottom)
            // Root-local coordinates stay fixed while every child moves with the drawer.
            var finger = (if (fromHandle) headerBefore.center else firstSong.center) - rootOrigin
            val originalFinger = finger
            fun observe(phase: String) {
                val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                record("$phase rootOrigin=$rootOrigin finger=$finger header=${header.fetchSemanticsNode().boundsInRoot} " +
                    "progress=${progress.value} listScroll=${range.value()}/${range.maxValue()} slop=$slop step=$step")
            }
            observe("before-down")
            compose.mainClock.autoAdvance = false
            root.performTouchInput { down(finger) }
            pointersDown = true
            fun move(dy: Float) {
                finger += Offset(0f, dy)
                record("inject-move dy=$dy rootLocal=$finger")
                root.performTouchInput { moveTo(finger, delayMillis = 32) }
                // Do not add an idle wait here: that would change the scheduling
                // being diagnosed before the existing frame advance.
                record("after-injection-before-frame progress=${progress.value}")
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                observe("after-frame")
            }
            fun scrollPosition() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (boundaryDebt) {
                move(slop + step)
                var moves = 0
                while (scrollPosition() > .001f && moves++ < 12) move(step)
                assertEquals("Reach top within the same list-owned contact", 0f, scrollPosition(), .001f)
                repeat(4) { move(step) }
                repeat(3) {
                    move(-step)
                    assertEquals("Reverse must repay boundary travel before scrolling", 0f, scrollPosition(), .001f)
                    assertEquals("List owner cannot move drawer", 1f, progress.value, .001f)
                }
                repeat(4) { move(-step) }
                assertTrue("After retracing excess, list scrolling resumes", scrollPosition() > 0f)
                assertEquals(1f, progress.value, .001f)
                root.performTouchInput { advanceEventTime(200); up() }
                pointersDown = false
                return
            }
            // Account for touch slop exactly once. The initial acquisition is not the
            // invariant: all subsequent travel must retain this single fixed baseline.
            move(slop + step)
            val acquiredTop = header.fetchSemanticsNode().boundsInRoot.top
            val acquiredFinger = finger.y
            assertTrue("A fresh pull must acquire the drawer; trace=$trace", acquiredTop > headerBefore.top + 1f)
            assertTrue("Acquisition cannot move farther than the finger", acquiredTop - headerBefore.top <=
                finger.y - originalFinger.y + 2f)
            if (endpoints) {
                // Cross fully open in one delta. Neither that delta's remainder nor
                // subsequent motion may switch this drawer contact to list scrolling.
                val endpoint = if (closedEndpoint) 0f else 1f
                val reverse = if (closedEndpoint) -1f else 1f
                val acquiredTravel = acquiredTop - headerBefore.top
                val drawerDistance = acquiredTravel / (1f - progress.value)
                move(if (closedEndpoint) drawerDistance - acquiredTravel + 2f * step
                    else -acquiredTravel - 2f * step)
                assertEquals(endpoint, progress.value, .001f)
                assertEquals(0f, scrollPosition(), .001f)
                move(reverse * step)
                assertEquals("Drawer retains excess travel at its endpoint", endpoint, progress.value, .001f)
                assertEquals(0f, scrollPosition(), .001f)
                move(reverse * 3f * step)
                assertTrue("Same owner responds after reversal", if (closedEndpoint) progress.value > 0f else progress.value < 1f)
                assertEquals(0f, scrollPosition(), .001f)
                root.performTouchInput { advanceEventTime(200); up() }
                pointersDown = false
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                assertEquals(1, settlements.size)
                return
            }
            val samples = mutableListOf<String>()
            fun assertAnchor(phase: String) {
                val top = header.fetchSemanticsNode().boundsInRoot.top
                val expected = acquiredTop + finger.y - acquiredFinger
                samples += "$phase finger=${finger.y} top=$top expected=$expected progress=${progress.value}"
                android.util.Log.i("QueueDragAnchor", "handle=$fromHandle gallery=$gallery ${samples.last()}")
                assertEquals("The original held anchor must survive $phase: $samples; trace=$trace", expected, top, 2f)
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
            try {
                if (pointersDown) compose.onRoot().performTouchInput { cancel() }
                compose.mainClock.autoAdvance = true
                preferences.write(route, previous, overridden)
            } finally {
                // Buffer callback observations during contact; synchronous logging
                // must not add work between an injected move and its frame sample.
                synchronized(trace) {
                    trace.forEach { android.util.Log.i("QueueDragTrace", "handle=$fromHandle gallery=$gallery $it") }
                }
            }
        }
    }
}
