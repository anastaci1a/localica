package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowsePreferences
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlaylistSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realPlaylistChooserTracksNativeDragReversalReentryAndInterruptedDeselection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Playlists)
        val preferences = BrowsePreferences(context)
        val previous = preferences.read(route)
        val frames = SelectorPlacementFrames(compose)
        lateinit var scope: CoroutineScope
        lateinit var source: PlaylistSelectionDrawSource
        val playlists = (0..39).map { ManualPlaylist(id = "p$it", name = "Playlist ${it.toString().padStart(2, '0')}") }
        var failure: Throwable? = null
        try {
            preferences.write(route, BrowseStyle(), false)
            compose.setContent { LocalicaTheme {
                scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalPlaylistSelectionDrawObserver provides { next ->
                    source = next
                    frames.observe(next.view) { playlistPlacement(next) }
                }) {
                    AddToPlaylistDialog(emptyList(), PlaylistState(playlists, loading = false), null, {}, {})
                }
            } }
            compose.waitForIdle()
            frames.awaitReady()
            compose.mainClock.autoAdvance = false
            frames.frame()
            frames.tap("p2")
            frames.settle()
            frames.requireChosen(setOf("p2"))
            compose.onNodeWithTag("playlist-selection-p2").assertIsSelected()
            frames.checking = true
            frames.exerciseMotion("p2") { index ->
                val done = CompletableDeferred<Unit>()
                compose.runOnUiThread { scope.launch {
                    try { source.state.scrollToItem(index); done.complete(Unit) }
                    catch (caught: Throwable) { done.completeExceptionally(caught) }
                } }
                runBlocking { withTimeout(5000) { done.await() } }
            }
            frames.departing = setOf("p2")
            frames.tap("p2")
            val departure = frames.frame()
            assertEquals(emptySet<String>(), departure.layout.chosen)
            val before = requireNotNull(departure.visibility["p2"])
            assertTrue("Visible deselection keeps its existing lens", before > 0f)
            repeat(3) { frames.frame() }
            val outgoing = requireNotNull(frames.frame().visibility["p2"])
            assertTrue("Departure actually fades before reselection", outgoing > 0f && outgoing < before)
            frames.tap("p2")
            val reversal = frames.frame()
            assertEquals(setOf("p2"), reversal.layout.chosen)
            val reversed = requireNotNull(reversal.visibility["p2"])
            assertTrue("Reversal retains the departing appearance", reversed > 0f && reversed < 1f)
            repeat(3) { frames.frame() }
            assertTrue(requireNotNull(frames.frame().visibility["p2"]) > reversed)
            frames.settle()
            frames.requireChosen(setOf("p2"))
            frames.tap("p2")
            frames.settle()
            frames.requireChosen(emptySet())
            compose.onNodeWithTag("playlist-selection-p2").assertIsNotSelected()
            assertTrue("Completed deselection removes every region", frames.frame().regions.isEmpty())
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            var cleanup = runCatching { frames.close() }.exceptionOrNull()
            runCatching { preferences.write(route, previous, false) }.exceptionOrNull()?.let {
                if (cleanup == null) cleanup = it else cleanup!!.addSuppressed(it)
            }
            val cleanupFailure = cleanup
            if (cleanupFailure != null) { if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure }
        }
    }
}

/** Independently placed rows and public native membership, not registry coordinates. */
internal data class SelectorPlacedItem(val key: String, val native: Rect, val placed: Rect?)
internal data class SelectorLayout(val viewport: Rect, val position: Pair<Int, Int>,
    val chosen: Set<String>, val items: List<SelectorPlacedItem>, val scrolling: Boolean)
internal data class SelectorDraw(val layout: SelectorLayout, val regions: List<Rect>,
    val visibility: Map<String, Float>, val membership: Map<String, Boolean>, val hostDraws: Int)

internal fun playlistPlacement(source: PlaylistSelectionDrawSource): SelectorDraw = Snapshot.withoutReadObservation {
    val info = source.state.layoutInfo
    val content = source.content.positionInRoot()
    val width = source.content.size.width.toFloat()
    val layout = SelectorLayout(source.viewport.boundsInRoot(),
        source.state.firstVisibleItemIndex to source.state.firstVisibleItemScrollOffset,
        source.chosen.toSet(), info.visibleItemsInfo.map { item ->
            val key = item.key as String
            val top = content.y + info.beforeContentPadding + item.offset
            SelectorPlacedItem(key, Rect(content.x, top, content.x + width, top + item.size),
                source.rows[key]?.takeIf { it.isAttached }?.let { Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) })
        }, source.state.isScrollInProgress)
    selectorDraw(source.scene, source.viewport, layout, source.hostDraws())
}

internal fun selectorDraw(scene: SelectionScene, viewport: LayoutCoordinates, layout: SelectorLayout, hostDraws: Int): SelectorDraw {
    val at = viewport.positionInRoot()
    val entries = scene.regions.values
    val keys = entries.map { it.itemKey }
    check(keys.all { it is String } && keys.toSet().size == keys.size) { "Every registered adapter row needs a unique stable itemKey: $keys" }
    return SelectorDraw(layout, scene.lastDrawnRegions.map { it.bounds.translate(at).intersect(layout.viewport) }
        .filterNot { it.isEmpty }.sortedBy { it.top },
        entries.associate { it.itemKey as String to it.visibility() },
        entries.associate { it.itemKey as String to it.selected }, hostDraws)
}

/** A content-draw witness inside the real shared host/overscroll. This reports its
 * submitted region geometry, not physical pixels or a completed GPU presentation. */
internal class SelectorPlacementFrames(private val compose: ComposeContentTestRule) {
    var checking = false
    var departing = emptySet<String>()
    private val draws = mutableListOf<Triple<SelectorDraw, Set<String>, Boolean>>()
    private var checked = 0
    private lateinit var view: View
    private var snapshot: (() -> SelectorDraw)? = null
    private var held = false
    private var point = Offset.Zero
    private var downTime = 0L
    private var downClock = 0L

    fun awaitReady() = compose.waitUntil(5000) {
        compose.runOnUiThread { ::view.isInitialized && view.isAttachedToWindow && view.hasWindowFocus() && snapshot != null }
    }

    fun observe(view: View, snapshot: () -> SelectorDraw) = Snapshot.withoutReadObservation {
        this.view = view
        this.snapshot = snapshot
        val draw = snapshot()
        val previous = draws.lastOrNull()?.first?.hostDraws ?: 0
        check(draw.hostDraws == previous || draw.hostDraws == previous + 1) { "No selected host draw may lack its content witness" }
        // Retained child display lists may redraw independently. Only a new host
        // serial proves a selected-host traversal; an empty lens needs no owner.
        if (draw.hostDraws != previous || draw.regions.isEmpty())
            draws += Triple(draw, departing.toSet(), checking)
    }

    fun frame(): SelectorDraw {
        compose.mainClock.advanceTimeByFrame()
        val done = CountDownLatch(1)
        val active = AtomicBoolean(true)
        var result: Result<Pair<SelectorDraw, List<Triple<SelectorDraw, Set<String>, Boolean>>>>? = null
        val after = Runnable { if (active.get()) {
            result = runCatching { Snapshot.withoutReadObservation { requireNotNull(snapshot).invoke() to draws.toList() } }
            done.countDown()
        } }
        val animation = Runnable { if (active.get()) view.post(after) }
        compose.runOnUiThread {
            check(::view.isInitialized) { "Actual adapter must have drawn before native-frame observation" }
            view.postOnAnimation(animation)
        }
        try {
            assertTrue("Native traversal completes within 5 s", done.await(5, TimeUnit.SECONDS))
            val (current, completed) = requireNotNull(result).getOrThrow()
            if (checking) {
                completed.drop(checked).forEach { (draw, fading, validate) -> if (validate) verify(draw, fading) }
                checked = completed.size
                val last = requireNotNull(completed.lastOrNull()) { "A real in-host drawing witness is required" }.first
                // A static frame may reuse its witness; changed native geometry may not.
                assertEquals("Current public native geometry has a matching host draw",
                    current.layout.copy(scrolling = last.layout.scrolling), last.layout)
                assertEquals("No newly started selected-host draw lacks its witness", current.hostDraws, last.hostDraws)
                verify(last, departing)
            }
            return current
        } finally {
            active.set(false)
            view.removeCallbacks(animation)
            view.removeCallbacks(after)
        }
    }

    private fun verify(draw: SelectorDraw, fading: Set<String>) {
        val expected = mutableListOf<Rect>()
        draw.layout.items.forEach { item ->
            val placed = requireNotNull(item.placed) { "Public visible key ${item.key} needs current independently placed coordinates" }
            assertRect("Native bounds agree with separate row onPlaced ${item.key}", item.native, placed)
            val v = draw.visibility[item.key] ?: 0f
            if (item.key in draw.layout.chosen) assertEquals(true, draw.membership[item.key])
            if (item.key in fading && item.key !in draw.layout.chosen) assertEquals(false, draw.membership[item.key])
            if (item.key in draw.layout.chosen || item.key in fading) {
                assertTrue("Visibility remains bounded", v in 0f..1f)
                if (v > 0f) {
                    val scale = .92f + .08f * v
                    val dx = placed.width * (1f - scale) / 2f
                    val dy = placed.height * (1f - scale) / 2f
                    val clipped = Rect(placed.left + dx, placed.top + dy, placed.right - dx, placed.bottom - dy).intersect(draw.layout.viewport)
                    if (!clipped.isEmpty) expected += clipped
                }
            }
        }
        expected.sortBy { it.top }
        assertEquals("Only native visible selected or legitimately departing rows contribute glass: $draw", expected.size, draw.regions.size)
        expected.zip(draw.regions).forEach { (body, glass) -> assertRect("This draw follows independently placed body", body, glass) }
    }

    private fun assertRect(label: String, expected: Rect, actual: Rect) {
        assertEquals(label, expected.left, actual.left, 1f); assertEquals(label, expected.top, actual.top, 1f)
        assertEquals(label, expected.right, actual.right, 1f); assertEquals(label, expected.bottom, actual.bottom, 1f)
    }

    fun requireChosen(expected: Set<String>) {
        val now = frame()
        assertEquals("Actual adapter membership", expected, now.layout.chosen)
        expected.forEach { assertEquals("Selected endpoint remains exact", 1f, requireNotNull(now.visibility[it]), 0f) }
    }

    fun settle() { repeat(32) { frame() } }

    fun exerciseMotion(selected: String, jump: (Int) -> Unit) {
        val initial = frame().layout.items.single { it.key == selected }.native.top
        down()
        move(-24f, 32)
        val first = frame().layout.items.single { it.key == selected }.native.top
        assertTrue("Native drag moves the selected row", first < initial)
        move(16f, 32)
        val reversed = frame().layout.items.single { it.key == selected }.native.top
        assertTrue("Native reversal follows present placement", reversed > first)
        repeat(4) { move(-24f, 16) }
        up()
        val positions = mutableSetOf<Pair<Int, Int>>()
        var fling = false
        repeat(12) { val draw = frame(); positions += draw.layout.position; fling = fling || draw.layout.scrolling }
        assertTrue("Real release exercises moving native lazy layout", fling && positions.size > 1)
        jump(12)
        assertTrue("Selected key actually virtualizes out", frame().layout.items.none { it.key == selected })
        assertTrue("No stale region remains when selection is outside native layout", frame().regions.isEmpty())
        jump(0)
        assertTrue("Selected key re-enters native layout", frame().layout.items.any { it.key == selected })
        requireChosen(setOf(selected))
    }

    fun tap(key: String) {
        val draw = frame()
        val bounds = draw.layout.items.single { it.key == key }.placed!!.intersect(draw.layout.viewport)
        down(bounds.center)
        up()
    }

    private fun down(root: Offset? = null) {
        check(!held)
        point = compose.runOnUiThread {
            assertTrue("Real owned native window is ready for input", view.isAttachedToWindow && view.hasWindowFocus())
            val current = requireNotNull(snapshot).invoke()
            val local = root ?: current.layout.viewport.center
            val location = IntArray(2).also(view::getLocationOnScreen)
            (local + Offset(location[0].toFloat(), location[1].toFloat())).also {
                val visible = android.graphics.Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                assertTrue("Pointer lies inside the actual visible native view", visible.contains(it.x.toInt(), it.y.toInt()))
            }
        }
        downTime = SystemClock.uptimeMillis(); downClock = compose.mainClock.currentTime
        held = true
        send(MotionEvent.ACTION_DOWN)
    }

    private fun move(dp: Float, delay: Long) {
        compose.mainClock.advanceTimeBy(delay, ignoreFrameDuration = true)
        point += Offset(0f, dp * view.resources.displayMetrics.density)
        send(MotionEvent.ACTION_MOVE)
    }
    private fun up() { send(MotionEvent.ACTION_UP); held = false }
    private fun send(action: Int) {
        val event = MotionEvent.obtain(downTime, downTime + compose.mainClock.currentTime - downClock, action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Native pointer $action delivered", InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    fun close() {
        try { if (held) { send(MotionEvent.ACTION_CANCEL); held = false } }
        finally { compose.mainClock.autoAdvance = true }
    }
}
