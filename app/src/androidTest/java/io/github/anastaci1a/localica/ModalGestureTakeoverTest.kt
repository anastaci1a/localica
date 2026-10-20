package io.github.anastaci1a.localica

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.ListSize
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Use Window.Callback, not performTouchInput: the latter bypasses Android's window boundary. */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModalGestureTakeoverTest {
    @get:Rule val compose = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val drivers = mutableListOf<WindowTouch>()
    private var popup by mutableStateOf(false)
    private var child by mutableStateOf(false)
    private var style by mutableStateOf(BrowseStyle(listSize = ListSize.Compact))
    private val original = style
    private val motion = BrowsePinchMotion()
    private lateinit var rootWindow: Window
    private lateinit var rootView: android.view.View
    private lateinit var popupWindow: Window
    private lateinit var popupView: android.view.View
    private lateinit var childWindow: Window
    private lateinit var rootOwner: ModalGestureScope
    private lateinit var popupOwner: ModalGestureScope
    private lateinit var childOwner: ModalGestureScope
    private lateinit var grid: LazyGridState
    private lateinit var popupGrid: LazyGridState
    private lateinit var childGrid: LazyGridState
    private lateinit var drag: KeyedDragState<String, Unit>
    private val rootOrigin get() = windowOrigin(rootView)
    private val popupOrigin get() = windowOrigin(popupView)
    private var commits = 0
    private var moves = 0
    private var openOnCommit = false
    private var popupActivations = 0
    private var retiredCommittedHandoff = false

    @After fun releaseInput() {
        // A failed held assertion must not leave native contact or modal windows for teardown.
        compose.mainClock.autoAdvance = false
        try {
            drivers.forEach { runCatching { it.cancel() } }
            compose.runOnUiThread { popup = false; child = false }
            compose.mainClock.advanceTimeBy(2000)
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun fixture(pinch: Boolean = true, keyedDrag: Boolean = false) {
        compose.setContent { LocalicaTheme {
            val view = LocalView.current
            rootView = view
            rootWindow = checkNotNull(view.context.activity()).window
            rootOwner = checkNotNull(LocalModalGestures.current)
            OnModalGestureCancellation {
                retiredCommittedHandoff = retiredCommittedHandoff || motion.source != null && motion.committed
            }
            grid = rememberLazyGridState()
            val density = LocalDensity.current
            val scope = rememberCoroutineScope()
            drag = remember { object : KeyedDragState<String, Unit>(grid, scope, { it }) {
                override fun capturePresentation() = Unit
            } }.also { it.items = (0 until 80).map(Int::toString) }
            Column(Modifier.fillMaxSize()) {
                ActionTextButton({ popupActivations++; popup = true }, Modifier.testTag("open-modal").height(64.dp)) { Text("Open options") }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    var modifier = Modifier.fillMaxSize().testTag("background-grid").controlScrollParent(grid, ControlScrollAxis.Vertical)
                    if (pinch) modifier = modifier.browsePinch(true, motion, style, BrowseRoute(MainTab.Songs), grid,
                        constraints.maxWidth, density, scope, false, onPreview = { style = it }, onCommit = {
                            commits++; style = it
                            if (openOnCommit) popup = true
                        }, onAnchor = {})
                    if (keyedDrag) modifier = modifier.sharedKeyedDrag(drag, true, false, itemAt = drag::hit) { _, _, complete -> moves++; complete(true) }
                    LazyVerticalGrid(GridCells.Fixed(1), modifier, state = grid, overscrollEffect = null) {
                        items(80, key = { it.toString() }) { index ->
                            Text("Song $index", Modifier.fillMaxWidth().height(if (style.listSize == ListSize.Compact) 60.dp else 80.dp)
                                .then(if (pinch) Modifier.retainPinchItem(index.toString(), motion) else Modifier))
                        }
                    }
                }
            }
            GlassMorphPopup(popup, rememberGlassAnchor(), { popup = false }, GlassPlacement.Center) {
                popupView = LocalView.current
                popupWindow = checkNotNull((popupView.parent as? DialogWindowProvider)?.window)
                popupOwner = checkNotNull(LocalModalGestures.current)
                Column(Modifier.width(280.dp)) {
                    ActionTextButton({ child = true }, Modifier.testTag("open-child")) { Text("Nested options") }
                    popupGrid = rememberLazyGridState()
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.height(220.dp).testTag("popup-grid")
                        .controlScrollParent(popupGrid, ControlScrollAxis.Vertical), state = popupGrid, overscrollEffect = null) {
                        items(40) { Text("Option $it", Modifier.height(48.dp)) }
                    }
                    ActionTextButton({ popup = false }, Modifier.testTag("dismiss-modal")) { Text("Done") }
                }
            }
            // Deliberately a sibling declaration: Queue rename/delete dialogs use this pattern.
            GlassMorphPopup(child, rememberGlassAnchor(), { child = false }, GlassPlacement.Center) {
                val childView = LocalView.current
                childWindow = checkNotNull((childView.parent as? DialogWindowProvider)?.window)
                childOwner = checkNotNull(LocalModalGestures.current)
                childGrid = rememberLazyGridState()
                Column(Modifier.width(240.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.height(180.dp).testTag("child-grid")
                        .controlScrollParent(childGrid, ControlScrollAxis.Vertical), state = childGrid, overscrollEffect = null) {
                        items(30) { Text("Nested option $it", Modifier.height(48.dp)) }
                    }
                    ActionTextButton({ child = false }, Modifier.testTag("dismiss-child")) { Text("Back") }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun thirdPointerPopupCancelsHeldPinchAndNeitherReleaseOrderCanSaveIt() {
        fixture()
        val area = bounds("background-grid", rootOrigin)
        val trigger = bounds("open-modal", rootOrigin).center
        val touch = WindowTouch(rootWindow)
        for (releaseFirst in listOf(0, 1)) {
            beginPinch(touch, area)
            await { motion.held && style != original }
            touch.down(2, trigger); touch.up(2)
            await { popup && rootOwner.blocked && motion.source == null }
            compose.runOnUiThread { assertEquals(original, style); assertEquals(0, commits) }
            touch.move(0, area.center + Offset(-150f, 0f)); touch.move(1, area.center + Offset(150f, 0f))
            touch.up(releaseFirst); touch.up(1 - releaseFirst)
            pump(500)
            compose.runOnUiThread { assertEquals(original, style); assertEquals(0, commits) }
            compose.runOnUiThread { popup = false }
            await { !rootOwner.blocked }
            pump(1000)
        }
        compose.runOnUiThread { assertEquals(2, popupActivations) }
        beginPinch(touch, area)
        await { style != original }
        touch.up(0); touch.up(1)
        await { commits == 1 && motion.source == null }
        compose.runOnUiThread { assertEquals(ListSize.Normal, style.listSize) }
    }

    @Test fun dismissalDoesNotReviveCancelledPointersAndFreshPinchStillWorks() {
        fixture()
        val area = bounds("background-grid", rootOrigin)
        val touch = WindowTouch(rootWindow)
        beginPinch(touch, area)
        await { style != original }
        // Accessibility/programmatic activation has the same ownership transition as a button.
        compose.runOnUiThread { popup = true }
        await { rootOwner.blocked && motion.source == null }
        compose.runOnUiThread { popup = false }
        await { !rootOwner.blocked }
        touch.move(1, area.center + Offset(170f, 0f)); touch.move(0, area.center + Offset(-170f, 0f))
        touch.up(1); touch.up(0)
        pump(500)
        compose.runOnUiThread { assertEquals(original, style); assertEquals(0, commits) }
        beginPinch(touch, area)
        await { style != original }
        touch.up(1); touch.up(0)
        await { commits == 1 }
    }

    @Test fun takeoverCancelsUncommittedSettleButPreservesAnAlreadyPublishedRelease() {
        fixture()
        val area = bounds("background-grid", rootOrigin)
        val touch = WindowTouch(rootWindow)
        beginPinch(touch, area)
        await { style != original }
        touch.up(0); touch.up(1)
        compose.runOnUiThread { popup = true }
        await { rootOwner.blocked && motion.source == null }
        compose.runOnUiThread { assertEquals(0, commits); assertEquals(original, style); popup = false }
        await { !rootOwner.blocked }
        pump(1000)
        compose.runOnUiThread { openOnCommit = true }
        beginPinch(touch, area)
        await { style != original }
        touch.up(1); touch.up(0)
        await { commits == 1 && popup && motion.source == null }
        compose.runOnUiThread {
            assertTrue("Take over during retained handoff, not only after normal cleanup", retiredCommittedHandoff)
            assertEquals(ListSize.Normal, style.listSize)
        }
    }

    @Test fun popupStopsHeldBackgroundScrollAndAnAlreadyRunningFling() {
        fixture(pinch = false)
        val area = bounds("background-grid", rootOrigin)
        val trigger = bounds("open-modal", rootOrigin).center
        val touch = WindowTouch(rootWindow)
        touch.down(0, area.center + Offset(0f, 100f))
        touch.move(0, area.center, 64); touch.move(0, area.center + Offset(0f, -100f), 64)
        await { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 }
        touch.down(1, trigger); touch.up(1)
        await { rootOwner.blocked && !grid.isScrollInProgress }
        val stopped = position(grid)
        touch.move(0, area.center + Offset(0f, -200f)); touch.up(0)
        pump(600)
        assertEquals(stopped, position(grid))
        compose.runOnUiThread { popup = false }
        await { !rootOwner.blocked }
        pump(1000)
        touch.down(0, area.center + Offset(0f, 140f))
        touch.move(0, area.center, 12); touch.move(0, area.center + Offset(0f, -140f), 12); touch.up(0)
        await { grid.isScrollInProgress }
        compose.runOnUiThread { popup = true }
        await { rootOwner.blocked && !grid.isScrollInProgress }
        val flung = position(grid)
        pump(800)
        assertEquals(flung, position(grid))
    }

    @Test fun siblingModalCancelsParentWindowWhileItsOwnScrollingRemainsAvailable() {
        fixture(pinch = false)
        // A semantics click verifies that accessible activation still opens the shared modal.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("open-modal").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("popup-grid").assertExists()
        compose.mainClock.autoAdvance = false
        val popupArea = bounds("popup-grid", popupOrigin)
        val nestedTrigger = bounds("open-child", popupOrigin).center
        val parentTouch = WindowTouch(popupWindow)
        parentTouch.down(0, popupArea.center + Offset(0f, 60f))
        parentTouch.move(0, popupArea.center - Offset(0f, 60f), 64)
        await { popupGrid.firstVisibleItemIndex > 0 || popupGrid.firstVisibleItemScrollOffset > 0 }
        parentTouch.down(1, nestedTrigger); parentTouch.up(1)
        await { child && popupOwner.blocked && rootOwner.blocked && ::childOwner.isInitialized &&
            ::childGrid.isInitialized && !childOwner.blocked && !popupGrid.isScrollInProgress }
        val stopped = position(popupGrid)
        parentTouch.move(0, popupArea.center - Offset(0f, 100f)); parentTouch.up(0)
        pump(500)
        assertEquals(stopped, position(popupGrid))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("child-grid").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(childGrid.firstVisibleItemIndex > 0 || childGrid.firstVisibleItemScrollOffset > 0) }
        compose.onNodeWithTag("dismiss-child").performClick()
        compose.runOnIdle { assertTrue(rootOwner.blocked); assertFalse(popupOwner.blocked) }
        compose.onNodeWithTag("popup-grid").performTouchInput { swipeUp() }
        compose.runOnIdle { assertNotEquals(stopped, popupGrid.firstVisibleItemIndex to popupGrid.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("dismiss-modal").performClick()
        compose.runOnIdle { assertFalse(rootOwner.blocked) }
    }

    @Test fun popupCancelsLiftedKeyedDragWithoutSendingAReorderCommit() {
        fixture(pinch = false, keyedDrag = true)
        val area = bounds("background-grid", rootOrigin)
        val trigger = bounds("open-modal", rootOrigin).center
        val touch = WindowTouch(rootWindow)
        val start = Offset(area.center.x, area.top + 40f)
        touch.down(0, start)
        pump(700)
        touch.move(0, start + Offset(0f, 90f), 64)
        await { drag.drag != null }
        touch.down(1, trigger); touch.up(1)
        await { rootOwner.blocked && drag.drag?.dropping != false }
        touch.move(0, start + Offset(0f, 250f)); touch.up(0)
        await { drag.drag == null }
        compose.runOnUiThread { assertEquals(0, moves) }
    }

    @Test fun realBrowsePinchTakeoverRestoresSourcePreferenceAndViewportAnchor() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = io.github.anastaci1a.localica.browse.BrowsePreferences(context)
        val route = BrowseRoute(MainTab.Songs)
        val saved = preferences.read(route)
        val overridden = preferences.overridden(route)
        preferences.write(route, original, false)
        try {
            val tracks = (0 until 100).map {
                io.github.anastaci1a.localica.library.Track("modal:$it", "Song %03d".format(it),
                    fileName = "$it.mp3", relativePath = "$it.mp3")
            }
            compose.setContent { LocalicaTheme {
                val view = LocalView.current
                rootView = view
                rootWindow = checkNotNull(view.context.activity()).window
                rootOwner = checkNotNull(LocalModalGestures.current)
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(io.github.anastaci1a.localica.library.LibrarySnapshot(
                        folders = listOf(io.github.anastaci1a.localica.library.FolderSelection("root", "Music")), tracks = tracks), loading = false),
                        io.github.anastaci1a.localica.playlist.PlaylistState(), route, {}, {}, {}, { _, _, _ -> },
                        null, null, true, false, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                    ActionTextButton({ popup = true }, Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                        .height(56.dp).testTag("open-modal")) { Text("Options") }
                }
                if (popup) GlassAlertDialog({ popup = false }, title = { Text("Options") },
                    confirmButton = { ActionTextButton({ popup = false }) { Text("Done") } })
            } }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(30)
            val before = compose.onNodeWithText("Song 029").fetchSemanticsNode().boundsInRoot.top
            val area = bounds("browser-list", rootOrigin)
            val trigger = bounds("open-modal", rootOrigin).center
            compose.mainClock.autoAdvance = false
            val touch = WindowTouch(rootWindow)
            beginPinch(touch, area)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "List, Normal"
            }
            touch.down(2, trigger); touch.up(2)
            await { rootOwner.blocked }
            touch.move(0, area.center + Offset(-160f, 0f)); touch.up(1); touch.up(0)
            compose.runOnUiThread { popup = false }
            await { !rootOwner.blocked }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(original, preferences.read(route))
            assertEquals("Rollback must restore the source viewport, not merely its preference", before,
                compose.onNodeWithText("Song 029").fetchSemanticsNode().boundsInRoot.top, 2f)
        } finally { preferences.write(route, saved, overridden) }
    }

    private fun beginPinch(touch: WindowTouch, area: Rect) {
        touch.down(0, area.center + Offset(-50f, 0f)); touch.down(1, area.center + Offset(50f, 0f))
        touch.move(0, area.center + Offset(-110f, 0f), 32); touch.move(1, area.center + Offset(110f, 0f), 32)
    }
    private fun bounds(tag: String, origin: Offset) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.translate(origin)
    private fun windowOrigin(view: android.view.View): Offset = compose.runOnUiThread {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        Offset(location[0].toFloat(), location[1].toFloat())
    }
    private fun position(state: LazyGridState): Pair<Int, Int> = compose.runOnUiThread { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    private fun pump(millis: Long) { compose.mainClock.advanceTimeBy(millis) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(5000) {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread(condition)
    }

    private inner class WindowTouch(private val window: Window) {
        private val points = linkedMapOf<Int, Offset>()
        private var downTime = 0L
        private var eventTime = android.os.SystemClock.uptimeMillis()
        init { drivers += this }
        fun down(id: Int, point: Offset) {
            if (points.isEmpty()) downTime = maxOf(eventTime + 1, android.os.SystemClock.uptimeMillis())
            points[id] = point
            send(if (points.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (points.keys.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        fun move(id: Int, point: Offset, delay: Long = 16) { points[id] = point; send(MotionEvent.ACTION_MOVE, delay) }
        fun up(id: Int) {
            send(if (points.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (points.keys.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            points.remove(id)
        }
        fun cancel() { if (points.isNotEmpty()) { send(MotionEvent.ACTION_CANCEL); points.clear() } }
        private fun send(action: Int, delay: Long = 16) {
            eventTime = maxOf(eventTime + delay, android.os.SystemClock.uptimeMillis(), downTime)
            val properties = points.keys.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coordinates = points.values.map { point -> MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f; size = 1f } }.toTypedArray()
            compose.runOnUiThread {
                val event = MotionEvent.obtain(downTime, eventTime, action, points.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { window.callback.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
    }
    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
        else -> null
    }
}
