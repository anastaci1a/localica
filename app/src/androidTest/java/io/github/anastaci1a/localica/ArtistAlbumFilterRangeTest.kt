package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ArtistAlbumFilterRangeTest {
    @get:Rule val compose = createComposeRule()
    private inner class Fixture {
        // Paired comparison control; ordinary invocations retain the original explicit idle.
        private val settleIdleMode = when (val mode = InstrumentationRegistry.getArguments()
            .getString("artistRangeSettleIdle") ?: "original") {
            "original", "deduplicated" -> mode
            else -> error("artistRangeSettleIdle must be original or deduplicated, was '$mode'")
        }
        private var observedFrame = 0
        private fun phase(value: String) {
            android.util.Log.i("ArtistRangeFixture", "phase=$value uptime=${android.os.SystemClock.uptimeMillis()}")
        }
        var reduced by mutableStateOf(false)
        var density = 1f
        val name = "Range "+UUID.randomUUID().toString().take(8)
        val tracks = (0..10).map { Track("range:$name:$it", "Song $it", artist = name, albumArtist = name,
            album = if (it < 10) "Ten songs" else "One song", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val snapshot = LibrarySnapshot(tracks = tracks)
        val artist = LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, artist.id), name)
        val preferences = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        init {
            phase("settle-idle-mode-$settleIdleMode")
            phase("preferences")
            preferences.write(route, BrowseStyle(presentation = Presentation.List, listSize = ListSize.Normal,
                sort = TrackSort.Title, ascending = true), true)
            phase("mount-begin")
            compose.setContent { LocalicaTheme {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Box(Modifier.fillMaxWidth().height(560.dp)) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route,
                            {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                            reducedMotion = reduced, queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            phase("mount-end")
            compose.mainClock.autoAdvance = false
            settle()
            phase("initial-settle-end")
            prepareVisibleAlbums()
        }
        fun top() = compose.onNodeWithTag("browse-full-span").getUnclippedBoundsInRoot().top.value -
            compose.onNodeWithTag("browser-list").getUnclippedBoundsInRoot().top.value
        fun frame(): Float = frame(explicitIdle = true)
        private fun frame(explicitIdle: Boolean): Float {
            val index = ++observedFrame
            phase("frame-$index-advance")
            compose.mainClock.advanceTimeByFrame()
            if (explicitIdle) {
                phase("frame-$index-idle")
                compose.waitForIdle()
                phase("frame-$index-bounds")
            } else {
                phase("frame-$index-explicit-idle-omitted")
                // Both bounds getters still synchronize. Their interval now includes that wait.
                phase("frame-$index-bounds-including-idle")
            }
            return top().also { phase("frame-$index-complete") }
        }
        fun settle() { repeat(40) { frame(explicitIdle = settleIdleMode == "original") } }
        private fun SemanticsNodeInteraction.unclippedPixels(): Rect = getUnclippedBoundsInRoot().let {
            Rect(it.left.value * density, it.top.value * density, it.right.value * density, it.bottom.value * density)
        }
        private fun assertContactArea(name: String, chip: Rect, visible: Rect) {
            // A nonempty clipped semantic rectangle can be only one physical pixel.
            // Require an interior contact patch, including at least half the chip's height.
            val minimumSide = 24f * density
            assertTrue("Native $name contact needs substantial visible area: chip=$chip visible=$visible",
                visible.width >= minimumSide && visible.height >= maxOf(minimumSide, chip.height / 2f))
        }
        private fun prepareVisibleAlbums() {
            // Setup only: measure the complete chips, not their clipped one-pixel remnants.
            // One real scroll and a bounded frame budget; never retry contacts/selection.
            phase("prepare-albums-begin")
            val names = listOf("Ten songs", "One song")
            val viewport = compose.onNodeWithTag("browser-list").unclippedPixels()
            val chips = names.map { compose.onNodeWithText(it).unclippedPixels() }
            val clearance = 24f * density
            val firstTop = chips.minOf { it.top }
            val lastBottom = chips.maxOf { it.bottom }
            assertTrue("Complete album chips must fit the measured viewport: chips=$chips viewport=$viewport",
                lastBottom - firstTop <= viewport.height - 2f * clearance)
            val correction = when {
                lastBottom > viewport.bottom - clearance -> lastBottom - (viewport.bottom - clearance)
                firstTop < viewport.top + clearance -> firstTop - (viewport.top + clearance)
                else -> 0f
            }
            if (correction != 0f) {
                scrollBy(correction / density)
                settle()
            }
            val placedViewport = compose.onNodeWithTag("browser-list").unclippedPixels()
            names.forEach { name ->
                val target = compose.onNodeWithText(name)
                val chip = target.unclippedPixels()
                val visible = chip.intersect(target.fetchSemanticsNode().boundsInRoot).intersect(placedViewport)
                assertTrue("Setup must expose the complete chip vertically: $name chip=$chip viewport=$placedViewport",
                    chip.top >= placedViewport.top + clearance - 1f && chip.bottom <= placedViewport.bottom - clearance + 1f)
                assertContactArea(name, chip, visible)
                android.util.Log.i("ArtistRangeFixture", "prepared-target=$name unclipped=$chip viewport=$placedViewport visible=$visible correction=$correction")
            }
            phase("prepare-albums-complete")
        }
        fun choose(name: String) {
            phase("choose-$name-target")
            val previousTop = top()
            // Short tap owns the complete subset; do not reset/filter or scroll first.
            val before = compose.mainClock.currentTime
            val target = compose.onNodeWithText(name).assertIsDisplayed()
            val chip = target.unclippedPixels()
            val node = target.fetchSemanticsNode()
            val viewport = compose.onNodeWithTag("browser-list").unclippedPixels()
            val visibleTarget = chip.intersect(node.boundsInRoot).intersect(viewport)
            assertContactArea(name, chip, visibleTarget)
            val view = (node.root as ViewRootForTest).view
            val point = compose.runOnUiThread {
                assertTrue(view.isAttachedToWindow && view.hasWindowFocus())
                val location = IntArray(2).also(view::getLocationOnScreen)
                val visible = android.graphics.Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                val screenTarget = visibleTarget.translate(Offset(location[0].toFloat(), location[1].toFloat()))
                    .intersect(Rect(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat()))
                assertContactArea(name, chip, screenTarget)
                val x = screenTarget.center.x
                val y = screenTarget.center.y
                assertTrue(x >= visible.left && x < visible.right && y >= visible.top && y < visible.bottom)
                android.util.Log.i("ArtistRangeFixture", "native-target=$name unclipped=$chip node=${node.boundsInRoot} viewport=$viewport visible=$visibleTarget screen=($x,$y) viewVisible=$visible")
                x to y
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val down = android.os.SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                    action, point.first, point.second, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            var held = false
            try {
                send(android.view.MotionEvent.ACTION_DOWN)
                held = true
                send(android.view.MotionEvent.ACTION_UP)
                held = false
            } finally { if (held) send(android.view.MotionEvent.ACTION_CANCEL) }
            assertEquals("Native album contact must not advance the paused motion clock", before, compose.mainClock.currentTime)
            // Publish the input's selection on its first Compose frame, and verify
            // that this very frame retains the old offset rather than hiding a clamp.
            assertEquals("Selection publication retains its first-frame offset", previousTop, frame(), 1f)
            compose.onNodeWithText(name).assertIsSelected()
            compose.onNodeWithText("All albums").assertIsNotSelected()
            phase("choose-$name-complete")
        }
        fun scrollBy(dp: Float) {
            phase("scroll-begin")
            compose.onNodeWithTag("browser-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * density) }
            phase("scroll-end")
        }
        fun largeAtExcessOffset(): Float {
            choose("Ten songs"); settle()
            scrollBy(top()); settle() // Fixture setup, never product filter behavior.
            val header = compose.onNodeWithTag("browse-full-span").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
            val viewport = compose.onNodeWithTag("browser-list").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
            scrollBy(header - viewport + 240f)
            settle()
            prepareVisibleAlbums()
            return top()
        }
        fun close() { compose.mainClock.autoAdvance = true; preferences.reset(route) }
    }

    @Test fun shrinkingToOneSongRetainsFirstFrameThenEasesOnlyToMeasuredNewEnd() {
        val f = Fixture()
        try {
            val before = f.largeAtExcessOffset()
            f.choose("One song")
            assertEquals("New keys cannot produce an immediate native clamp flash", before, f.top(), 1f)
            val positions = (0 until 30).map { f.frame() }
            assertTrue("Out-of-range offset actually adjusts", positions.last() > before + 8f)
            assertTrue("Valid final end is scrolled: never an unconditional top reset", positions.last() < -8f)
            positions.zipWithNext().forEach { (a, b) -> assertTrue("Correction is monotonic", b >= a - 1f) }
            val early = positions[8] - positions[2]
            val late = positions[23] - positions[17]
            assertTrue("Measured correction eases out instead of snapping/linear travel", early > late)
            compose.onAllNodesWithTag("artist-filter-range-reserve").assertCountEquals(0)
            val end = f.top()
            f.scrollBy(100f); f.settle()
            assertEquals("No synthetic extent remains beyond the new real end", end, f.top(), 1f)
        } finally { f.close() }
    }

    @Test fun stillValidOffsetRemainsExactAcrossAlbumSubsetReduction() {
        val f = Fixture()
        try {
            f.choose("Ten songs"); f.settle()
            f.scrollBy(24f); f.settle()
            val before = f.top()
            assertTrue(before < -8f)
            f.choose("One song")
            assertEquals(before, f.top(), 1f)
            repeat(30) { assertEquals("A valid old offset never auto-scrolls", before, f.frame(), 1f) }
        } finally { f.close() }
    }

    @Test fun rapidChangesTouchTakeoverAndReducedMotionCannotReviveAnOldCorrection() {
        val f = Fixture()
        try {
            f.largeAtExcessOffset()
            f.choose("One song")
            repeat(3) { f.frame() }
            // A newer expansion owns the current offset; the old shrink must not finish it.
            val beforeExpand = f.top()
            f.choose("Ten songs")
            repeat(30) { assertEquals("Stale shrink owner is retired", beforeExpand, f.frame(), 1f) }
            f.choose("One song")
            repeat(2) { f.frame() }
            val list = compose.onNodeWithTag("browser-list")
            list.performTouchInput { down(center); moveBy(Offset(0f, 24f), delayMillis = 32) }
            val held = f.frame()
            repeat(4) { assertEquals("A held finger suspends automatic range easing", held, f.frame(), 1f) }
            list.performTouchInput { advanceEventTime(128); up() }
            f.settle()
            compose.onAllNodesWithTag("artist-filter-range-reserve").assertCountEquals(0)
            f.largeAtExcessOffset()
            compose.runOnIdle { f.reduced = true }
            f.choose("One song")
            repeat(4) { f.frame() }
            val snapped = f.top()
            assertTrue("Reduced motion still retains a nonzero valid end", snapped < -8f)
            repeat(20) { assertEquals("Reduced motion has no continuing ease", snapped, f.frame(), 1f) }
            compose.onAllNodesWithTag("artist-filter-range-reserve").assertCountEquals(0)
        } finally { f.close() }
    }
}
