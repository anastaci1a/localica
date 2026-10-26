package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native lazy placement and actual host-drawn selection geometry, without screenshot-induced redraw. */
class ArtistSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    private enum class Check { Settling, Single, All }
    private data class Body(val key: Int, val offset: Int, val size: Int,
        val raw: Rect?, val clipped: Rect?)
    private data class Layout(val viewport: Rect, val row: Rect, val beforePadding: Int,
        val direction: LayoutDirection, val position: Pair<Int, Int>, val all: Boolean, val bodies: List<Body>)
    private data class Draw(val sequence: Int, val hostDraws: Int, val check: Check,
        val layout: Layout, val regions: List<Rect>)
    private data class Frame(val layout: Layout, val draws: List<Draw>, val hostDraws: Int, val scrolling: Boolean)

    @Test fun selectedAlbumGeometryTracksEachDragFlingReversalOffscreenReturnAndResizeFrame() {
        lateinit var scroll: LazyListState
        lateinit var scene: SelectionScene
        lateinit var scope: CoroutineScope
        lateinit var view: View
        lateinit var viewportCoordinates: LayoutCoordinates
        lateinit var rowCoordinates: LayoutCoordinates
        var direction = LayoutDirection.Ltr
        var width by mutableStateOf(280.dp)
        var allSelected by mutableStateOf(false)
        // Plain fixture observations must not subscribe drawing to extra snapshot state.
        val placed = mutableMapOf<Int, LayoutCoordinates>()
        val completedDraws = mutableListOf<Draw>()
        var hostDraws = 0
        var validation = Check.Settling
        var checkedDraws = 0
        var contactHeld = false
        var primaryFailure: Throwable? = null
        fun phase(value: String) { android.util.Log.i("ArtistPlacementPhase", value) }
        fun layout(): Layout = Snapshot.withoutReadObservation {
            val viewport = viewportCoordinates.boundsInRoot()
            val info = scroll.layoutInfo
            Layout(viewport, Rect(rowCoordinates.positionInRoot(),
                Size(rowCoordinates.size.width.toFloat(), rowCoordinates.size.height.toFloat())),
                info.beforeContentPadding, direction,
                scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset, allSelected,
                info.visibleItemsInfo.map { item ->
                    val coordinates = placed[item.key as Int]?.takeIf { it.isAttached }
                    Body(item.key as Int, item.offset, item.size, coordinates?.let {
                        Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }, coordinates?.boundsInRoot()?.intersect(viewport))
                })
        }
        compose.setContent { LocalicaTheme {
            scroll = androidx.compose.foundation.lazy.rememberLazyListState()
            scope = rememberCoroutineScope()
            view = LocalView.current
            direction = LocalLayoutDirection.current
            // Exactly SelectionViewport's clip/overscroll/event split, with the
            // passive witness INSIDE overscroll and immediately outside the host.
            // An observer outside a retained overscroll layer can miss host draws.
            val overscroll = rememberOverscrollEffect()
            val events = remember(overscroll) { overscroll?.withoutVisualEffect() }
            ConnectedSelectionHost(Modifier.width(width).height(112.dp).background(Color.Black).testTag("album-viewport")
                .clipToBounds().then(if (overscroll != null) Modifier.overscroll(overscroll) else Modifier)
                .onPlaced { viewportCoordinates = it }.drawWithContent {
                    drawContent()
                    Snapshot.withoutReadObservation {
                        val current = layout()
                        completedDraws += Draw(completedDraws.size + 1, hostDraws, validation, current,
                            scene.lastDrawnRegions.map { it.bounds.translate(current.viewport.topLeft).intersect(current.viewport) }
                                .filterNot { it.isEmpty }.sortedBy { it.left })
                    }
                }, observePlacement = { scroll.layoutInfo; hostDraws++; Unit }) {
                scene = requireNotNull(LocalSelectionScene.current)
                LazyRow(Modifier.onPlaced { rowCoordinates = it }, state = scroll, overscrollEffect = events,
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items((0..19).toList(), key = { it }) { index ->
                        DisposableEffect(index) { onDispose { placed.remove(index) } }
                        Box(Modifier.width(80.dp).height(96.dp).selectionLens(allSelected || index == 2, false)
                            .onPlaced { placed[index] = it }.testTag("album-$index"))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        fun nativeFrame(): Frame {
            // One native traversal boundary, not idle and not a retry-until-match.
            // Posting from its animation callback observes after that traversal;
            // it does not request a redraw or advance the Compose animation clock.
            val completed = CountDownLatch(1)
            var result: Result<Frame>? = null
            val active = AtomicBoolean(true)
            val afterTraversal = Runnable {
                if (active.get()) {
                    try { result = runCatching { Frame(layout(), completedDraws.toList(), hostDraws, scroll.isScrollInProgress) } }
                    finally { completed.countDown() }
                }
            }
            val onAnimation = Runnable { if (active.get()) view.post(afterTraversal) }
            compose.runOnUiThread { view.postOnAnimation(onAnimation) }
            var failure: Throwable? = null
            try {
                assertTrue("Native traversal completes within 5 s", completed.await(5, TimeUnit.SECONDS))
                return requireNotNull(result).getOrThrow()
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                active.set(false)
                // Attached Views allow callback removal off the UI thread. A
                // detached/racing callback is inert; never block raw release on
                // another UI dispatch, or replace the primary failure.
                val cleanup = runCatching {
                    if (view.isAttachedToWindow) {
                        view.removeCallbacks(onAnimation); view.removeCallbacks(afterTraversal)
                    }
                }.exceptionOrNull()
                if (cleanup != null) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
        fun nativeBody(current: Layout, body: Body): Rect {
            val fromStart = current.beforePadding + body.offset
            val left = if (current.direction == LayoutDirection.Ltr) current.row.left + fromStart
                else current.row.right - fromStart - body.size
            return Rect(left, current.row.top, left + body.size, current.row.bottom)
        }
        fun visibleBodies(current: Layout) = current.bodies.filter {
            !nativeBody(current, it).intersect(current.viewport).isEmpty
        }
        fun assertLayout(current: Layout) {
            // Native keys own membership. Coordinates come ONLY from the fixture's
            // onPlaced callbacks, never from the production selection registry.
            // Do not call the native-key-filtered set an independent identity proof.
            current.bodies.forEach { body ->
                val raw = requireNotNull(body.raw) { "Native visible key ${body.key} needs attached onPlaced coordinates: $current" }
                val clipped = requireNotNull(body.clipped)
                val native = nativeBody(current, body)
                // Native item membership may include a fully clipped body whose
                // trailing spacing is still visible. Decide body visibility from
                // its native interval, never from the production region list.
                assertEquals("Independent clipped body visibility matches native key ${body.key}",
                    native.intersect(current.viewport).isEmpty, clipped.isEmpty)
                assertEquals("Independent placed x matches native key ${body.key}", native.left, raw.left, 1f)
                assertEquals("Independent placed width matches native key ${body.key}", body.size.toFloat(), raw.width, 1f)
            }
        }
        fun assertDraw(draw: Draw, mode: Check) {
            assertLayout(draw.layout)
            val actual = draw.regions
            if (mode == Check.Single) {
                val selected = visibleBodies(draw.layout).singleOrNull { it.key == 2 }?.clipped
                if (selected == null) {
                    assertTrue("No stale glass remains in the viewport when selection leaves", actual.isEmpty())
                } else {
                    assertEquals("Exactly one live selected album", 1, actual.size)
                    val body = actual.single()
                    assertEquals("Drawn left follows this frame's placed item", selected.left, body.left, 1f)
                    assertEquals("Drawn right follows this frame's placed item", selected.right, body.right, 1f)
                    assertEquals("Drawn top follows this frame's placed item", selected.top, body.top, 1f)
                    assertEquals("Drawn bottom follows this frame's placed item", selected.bottom, body.bottom, 1f)
                }
            } else if (mode == Check.All) {
                val expected = visibleBodies(draw.layout).map { requireNotNull(it.clipped) }.sortedBy { it.left }
                assertTrue("All mode exercises multiple visible album bodies", expected.size > 1)
                assertEquals("All-mode bodies draw=${draw.sequence} host=${draw.hostDraws} layout=${draw.layout} actual=$actual",
                    expected.size, actual.size)
                expected.zip(actual).forEach { (item, glass) ->
                    assertEquals(item.left, glass.left, 1f); assertEquals(item.right, glass.right, 1f)
                    assertEquals(item.top, glass.top, 1f); assertEquals(item.bottom, glass.bottom, 1f)
                }
            }
        }
        fun frame(mode: Check = Check.Single): Frame {
            compose.mainClock.advanceTimeByFrame()
            val current = nativeFrame()
            // Check every completed native draw, including draws between explicit
            // frame() calls. Never select only a later geometry-matching sample.
            current.draws.drop(checkedDraws).forEachIndexed { offset, draw ->
                val previous = current.draws.getOrNull(checkedDraws + offset - 1)?.hostDraws ?: 0
                assertTrue("No host draw may lack its completed witness: previous=$previous draw=$draw",
                    draw.hostDraws == previous || draw.hostDraws == previous + 1)
                if (draw.check != Check.Settling) assertDraw(draw, draw.check)
            }
            checkedDraws = current.draws.size
            val last = requireNotNull(current.draws.lastOrNull()) { "A completed host draw is required" }
            assertEquals("Every started host draw has completed its witness", current.hostDraws, last.hostDraws)
            // Static unchanged frames may reuse their presented witness. A changed
            // scroll/placement/selection state cannot reuse an old draw, even if
            // its selected rectangle happens to coincide with the old one.
            assertEquals("Current native layout must have a completed host draw", current.layout, last.layout)
            assertDraw(last, mode)
            return current
        }
        fun Frame.selected() = visibleBodies(layout).singleOrNull { it.key == 2 }?.clipped

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var point = Offset.Zero
        var downTime = 0L
        var downClock = 0L
        fun send(action: Int) {
            val eventTime = downTime + compose.mainClock.currentTime - downClock
            val event = MotionEvent.obtain(downTime, eventTime, action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Native pointer injection $action", automation.injectInputEvent(event, true))
                phase("native-action=$action eventOffset=${eventTime - downTime} clock=${compose.mainClock.currentTime}")
            } finally { event.recycle() }
        }
        fun down() {
            check(!contactHeld)
            point = compose.runOnUiThread {
                assertTrue(view.isAttachedToWindow && view.hasWindowFocus())
                val root = viewportCoordinates.boundsInRoot()
                val location = IntArray(2).also(view::getLocationOnScreen)
                val screen = root.translate(Offset(location[0].toFloat(), location[1].toFloat()))
                val visible = android.graphics.Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                screen.center.also {
                    assertTrue("Viewport center is a visible native contact", it.x >= visible.left && it.x < visible.right &&
                        it.y >= visible.top && it.y < visible.bottom)
                }
            }
            downTime = SystemClock.uptimeMillis()
            downClock = compose.mainClock.currentTime
            // Even uncertain/failed injection must get a best-effort raw CANCEL.
            contactHeld = true
            send(MotionEvent.ACTION_DOWN)
        }
        fun moveBy(delta: Offset, delayMillis: Long) {
            // Compose's old dispatcher advanced its clock before each event too.
            compose.mainClock.advanceTimeBy(delayMillis, ignoreFrameDuration = true)
            point += delta
            send(MotionEvent.ACTION_MOVE)
        }
        fun up(delayMillis: Long = 0) {
            if (delayMillis > 0) compose.mainClock.advanceTimeBy(delayMillis, ignoreFrameDuration = true)
            send(MotionEvent.ACTION_UP)
            contactHeld = false
        }
        try {
            compose.runOnUiThread { validation = Check.Single }
            val start = requireNotNull(frame().selected())
            phase("initial-frame")
            down(); moveBy(Offset(-30f, 0f), delayMillis = 32)
            val first = requireNotNull(frame().selected())
            assertTrue("Real drag moves album and selected glass together", first.left < start.left)
            val drawBeforeReverse = compose.runOnUiThread { hostDraws }
            moveBy(Offset(18f, 0f), delayMillis = 32)
            val reverse = requireNotNull(frame().selected())
            assertTrue("Reversal follows the finger rather than an eased old offset", reverse.left > first.left)
            assertTrue("Scroll invalidates the selection host drawing", compose.runOnUiThread { hostDraws } > drawBeforeReverse)
            phase("drag-and-reversal")
            // Keep the four recent MOVE samples contiguous after frame() advances
            // the event clock; release adds no delay to their 16 ms spacing.
            repeat(4) { moveBy(Offset(-20f, 0f), delayMillis = 16) }
            up()
            val positions = mutableSetOf<Pair<Int, Int>>()
            var sawFling = false
            repeat(12) {
                val current = frame()
                positions += current.layout.position
                sawFling = sawFling || current.scrolling
            }
            assertTrue("Release exercises native fling progression", sawFling && positions.size > 1)
            phase("fling-complete")
            fun jump(index: Int) {
                val completed = CompletableDeferred<Unit>()
                compose.runOnUiThread { scope.launch { try { scroll.scrollToItem(index); completed.complete(Unit) }
                    catch (failure: Throwable) { completed.completeExceptionally(failure) } } }
                runBlocking { withTimeout(5000) { completed.await() } }
                frame()
            }
            jump(12)
            assertTrue("Selected item is genuinely outside visible layout", compose.runOnUiThread {
                scroll.layoutInfo.visibleItemsInfo.none { it.key == 2 } })
            jump(0)
            assertNotNull("Re-entering selected item gets its current geometry", frame().selected())
            compose.runOnUiThread { width = 220.dp }
            frame()
            compose.runOnUiThread { scope.launch { scroll.scrollBy(40f) } }
            frame()
            compose.runOnUiThread { width = 280.dp }
            frame()
            // The original settled All oracle starts AFTER its 500 ms reveal.
            phase("offscreen-reentry-and-resize-complete")
            compose.runOnUiThread { validation = Check.Settling; allSelected = true }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnUiThread { validation = Check.All }
            frame(Check.All)
            phase("all-mode-initial")
            down(); moveBy(Offset(-40f, 0f), delayMillis = 32)
            frame(Check.All)
            phase("all-mode-drag")
            moveBy(Offset(24f, 0f), delayMillis = 32)
            frame(Check.All)
            phase("all-mode-reversal")
            up(delayMillis = 128)
            frame(Check.All)
            phase("body-complete")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            phase("cleanup-start held=$contactHeld failure=${primaryFailure?.javaClass?.simpleName}")
            compose.mainClock.autoAdvance = false
            val cleanup = runCatching {
                // No semantics lookup, idle prerequisite, or clock advance while
                // releasing an owned contact after the primary assertion failed.
                if (contactHeld) { send(MotionEvent.ACTION_CANCEL); contactHeld = false }
            }.exceptionOrNull()
            compose.mainClock.autoAdvance = true
            phase("cleanup-complete error=${cleanup?.javaClass?.simpleName}")
            if (cleanup != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
