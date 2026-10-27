package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real QueueSurface/QueueScreen route and native contacts. Bounds, host identity, focus and
 * draw acknowledgements are ownership witnesses, NOT presented-pixel/material continuity proof.
 * The non-reduced, damping-2 fixture exposes an unsettled return without changing production. */
class QueueSaveOriginTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerSaveUsesItsMeasuredActionAndReturnsOnCancel() {
        val drawer = showDrawer()
        try {
            val opened = openSave(drawer)
            tap(compose.onNodeWithText("Cancel", substring = false))
            awaitReturned(drawer, opened)
        } finally { dispose(drawer) }
    }

    @Test fun nativeBackReturnsTheDialogToTheSameDrawerAction() {
        val drawer = showDrawer()
        try {
            val opened = openSave(drawer)
            compose.awaitExpectedWindowFocus(compose.onNodeWithText("Add to playlists"))
            // No editor was focused: this Back dismisses the actual dialog, not an IME.
            pressSystemBack()
            awaitReturned(drawer, opened)
        } finally { dispose(drawer) }
    }

    @Test fun restoredSaveReopensBeforeBodyRestWithoutCompetingOwners() {
        val drawer = showDrawer()
        try {
            val first = openSave(drawer)
            tap(compose.onNodeWithText("Cancel", substring = false))
            pump(drawer, returning = first) {
                !first.entry.mounted && !first.anchor.hidden && first.anchor.restoredDrawn &&
                    drawer.sourceView.hasWindowFocus() && !first.anchor.contact.state.momentum.settled
            }
            // Contact the ORIGINAL screen point, without advancing to body rest or forcing saving.
            // Sample immediately before dispatch; this is not a claim about compositor pixels.
            compose.runOnUiThread {
                assertFalse(first.anchor.hidden)
                assertTrue(first.anchor.restoredDrawn)
                assertFalse(first.anchor.contact.state.momentum.settled)
                assertTrue(drawer.sourceView.hasWindowFocus())
            }
            contact(first.bounds.center)
            pump(drawer) { drawer.host.entries.singleOrNull()?.mounted == true }
            val next = compose.runOnUiThread { drawer.host.entries.single() }
            compose.runOnUiThread {
                assertSame("Reopening stays with the real Save trigger", first.anchor, next.anchor)
                assertTrue("A retired logical owner is removed, never concurrently retained",
                    next === first.entry || first.entry !in drawer.host.entries)
            }
            awaitOpened(drawer, next)
            val nextView = popupView()
            compose.runOnUiThread {
                assertNotNull(nextView.windowToken)
                if (nextView === first.view) assertSame("A retained native view keeps its token", first.token, nextView.windowToken)
                else assertFalse("A successor window must not leave the old window attached", first.view.isAttachedToWindow)
            }
            // Geometric return may retire the entry before click admission while the source
            // body still eases. That valid successor must still have the same measured anchor.
            val reopened = Opened(next, first.bounds, nextView, compose.runOnUiThread { nextView.windowToken })
            tap(compose.onNodeWithText("Cancel", substring = false))
            awaitReturned(drawer, reopened)
            compose.runOnUiThread { assertEquals(1, drawer.maximumEntries) }
        } finally { dispose(drawer) }
    }

    @Test fun successfulSavePersistsBrowsedOccurrencesAndKeepsSelectionBeforeReturning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        var drawer: Drawer? = null
        try {
            runBlocking {
                withTimeout(5000) { controller.state.first { !it.loading } }
                val created = CompletableDeferred<Boolean>()
                controller.create("Destination") { created.complete(it) }
                assertTrue(withTimeout(5000) { created.await() })
            }
            val fixture = showDrawer(controller).also { drawer = it }
            val opened = openSave(fixture)
            val destinationId = controller.state.value.playlists.single().id
            val destination = compose.onNodeWithTag("playlist-selection-$destinationId")
            destination.assertIsDisplayed().assertIsNotSelected().assertHasClickAction()
            // Preserve the ONE original text-derived native contact. The actual
            // tagged row and native layout are independent admission witnesses.
            tap(compose.onNodeWithText("Destination", substring = false)) { point ->
                observeDestinationContact(fixture, destinationId, destination, point)
            }
            // Native UP selects the row; the paused clock still owes the composition
            // that enables Add. Semantics synchronization MUST remain off Main;
            // an exception from an invalid thread must not masquerade as unready.
            pump(fixture, predicateOnUiThread = false) {
                val selected = try { destination.assertIsSelected(); true } catch (_: AssertionError) { false }
                val enabled = try { compose.onNodeWithText("Add", substring = false).assertIsEnabled(); true }
                    catch (_: AssertionError) { false }
                observeDestinationState(fixture, destinationId, selected, enabled)
                selected && enabled
            }
            destination.assertIsSelected()
            tap(compose.onNodeWithText("Add", substring = false).assertIsEnabled())
            pump(fixture) { controller.state.value.playlists.single().entries.size == fixture.browsed.entries.size }
            awaitReturned(fixture, opened)
            val persisted = runBlocking { withTimeout(5000) { database.playlists().read() } }.single()
            assertEquals("Destination", persisted.name)
            assertEquals("Save the browsed queue, not the playing queue or selected subset",
                fixture.browsed.entries.map { it.track.id }, persisted.entries.map { it.track.id })
            assertEquals("Duplicate songs remain distinct playlist occurrences", 3, persisted.entries.map { it.id }.toSet().size)
            compose.runOnUiThread {
                assertEquals(fixture.originalQueue, fixture.state)
                assertEquals(fixture.originalSelection, fixture.selection.entries.toList())
                assertEquals(1, fixture.maximumEntries)
            }
        } finally { drawer?.let(::dispose); scope.cancel(); database.close() }
    }

    private class Drawer {
        private fun song(id: String) = Track("test:$id", id, fileName = "$id.mp3", relativePath = "$id.mp3")
        private val repeated = song("Repeated")
        val playing = QueueEdits.create(listOf(song("Playing only")), 0, "Playing queue")
        val browsed = QueueEdits.create(listOf(repeated, song("Middle"), repeated), 1, "Browsed queue")
        val state = QueueUiState(QueueLibrary(listOf(playing, browsed), playing.id), loading = false, browsedId = browsed.id)
        val originalQueue = state.copy()
        val selection = SessionSelection().apply {
            select(MediaRef(MediaKind.Queue, browsed.id), listOf(browsed.entries[1].let { SongRow(it.id, it.track) }))
        }
        val originalSelection = selection.entries.toList()
        val progress = Animatable(0f)
        var present by mutableStateOf(true)
        lateinit var host: GlassDialogs
        lateinit var sourceView: View
        lateinit var backing: FrostedBackdrop
        var maximumEntries = 0
        var playlistSource: PlaylistSelectionDrawSource? = null
        var destinationObservation = "not observed"
        var destinationLogCount = 0
        val destinationDiagnosticFailures = mutableListOf<Throwable>()
    }

    private data class Opened(val entry: GlassDialogEntry, val bounds: Rect, val view: View, val token: android.os.IBinder?) {
        val anchor: GlassAnchor get() = entry.anchor
    }

    private fun showDrawer(controller: PlaylistController? = null): Drawer {
        val drawer = Drawer()
        compose.setContent { if (drawer.present) LocalicaTheme {
            val host = requireNotNull(LocalGlassDialogs.current)
            val sourceView = LocalView.current
            val backing = rememberFrostedBackdrop(Unit, dark = false)
            val playlists = if (controller == null) PlaylistState(loading = false) else controller.state.collectAsState().value
            val entries = host.entries.size
            SideEffect {
                drawer.host = host; drawer.sourceView = sourceView; drawer.backing = backing
                drawer.maximumEntries = maxOf(drawer.maximumEntries, entries)
            }
            CompositionLocalProvider(LocalFrostedBackdrop provides backing, LocalReducedMotion provides false,
                LocalGlassAppearance provides GlassAppearance(shapeDamping = 2f), LocalSessionSelection provides drawer.selection,
                LocalPlaylistSelectionDrawObserver provides if (controller == null) null else
                    ({ value: PlaylistSelectionDrawSource -> drawer.playlistSource = value })) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) { drawRect(Color(0xFF8099AA)) }
                    PlayerClippedOverlay({ 1f }, 80.dp) {
                        QueueSurface(drawer.progress, InterfaceSettings(), {}) {
                            Box(Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalBrowseObscuredInset provides 0.dp) {
                                    QueueScreen(drawer.state, null, playlists = playlists, playlistController = controller,
                                        selectionVisible = true, playerOpen = false)
                                }
                            }
                        }
                    }
                }
            }
        } }
        tap(compose.onNodeWithContentDescription("Open queue"))
        compose.runOnIdle { assertEquals(1f, drawer.progress.value, .001f) }
        saveButton().assertIsDisplayed().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        return drawer
    }

    private fun openSave(drawer: Drawer): Opened {
        val bounds = screenBounds(saveButton())
        tap(saveButton())
        pump(drawer) { drawer.host.entries.singleOrNull()?.mounted == true }
        val entry = compose.runOnUiThread { drawer.host.entries.single() }
        compose.runOnUiThread {
            val anchor = entry.anchor
            assertTrue("Dialog must use a mounted source, not a detached fallback", anchor.triggerMounted)
            assertNotEquals(Rect.Zero, anchor.bounds)
            assertBounds(bounds, anchor.bounds)
            assertNotNull("ActionIcon face follows its source", anchor.face)
            assertTrue("Source is the finished Queue material", anchor.source?.materialOnly == true)
            assertSame(drawer.backing, anchor.source?.parent)
            assertEquals(GlassPlacement.Center, entry.placement)
        }
        pump(drawer) { entry.anchor.hidden && entry.anchor.contact.expansion.value > 0f && entry.anchor.contact.expansion.value < .95f }
        awaitOpened(drawer, entry)
        val view = popupView()
        return Opened(entry, bounds, view, compose.runOnUiThread { view.windowToken.also { assertNotNull(it) } })
    }

    private fun awaitOpened(drawer: Drawer, entry: GlassDialogEntry) {
        pump(drawer, endpoint = true) { entry.mounted && entry.anchor.hidden && entry.anchor.modalPresent && entry.anchor.contact.expansion.value >= .999f }
        compose.onNodeWithText("Add to playlists").assertIsDisplayed()
        compose.awaitExpectedWindowFocus(compose.onNodeWithText("Add to playlists"))
        saveButton().assertDoesNotExist()
        val popup = popupView()
        compose.runOnUiThread {
            assertNotSame("Picker owns a separate native window", drawer.sourceView, popup)
            assertTrue(popup.isAttachedToWindow && popup.hasWindowFocus())
            assertSame(entry, drawer.host.entries.single())
        }
    }

    private fun awaitReturned(drawer: Drawer, opened: Opened) {
        pump(drawer, endpoint = true) { drawer.host.entries.isEmpty() && !opened.anchor.modalPresent && !opened.anchor.hidden &&
            opened.anchor.restoredDrawn && drawer.sourceView.hasWindowFocus() }
        saveButton().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Add to playlists").assertDoesNotExist()
        assertBounds(opened.bounds, screenBounds(saveButton()))
        compose.runOnUiThread {
            assertTrue(opened.anchor.triggerMounted)
            assertEquals(drawer.originalQueue, drawer.state)
            assertEquals(drawer.originalSelection, drawer.selection.entries.toList())
        }
    }

    private fun pump(drawer: Drawer, endpoint: Boolean = false, predicateOnUiThread: Boolean = true,
        returning: Opened? = null,
        predicate: () -> Boolean) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        val started = android.os.SystemClock.uptimeMillis()
        // Endpoint-only waits have no intervening contact. Keep partial opening and
        // the restored-but-unsettled reopening witness on the original manual clock.
        if (endpoint) compose.mainClock.autoAdvance = true
        try { compose.waitUntil(5000) {
        if (endpoint) compose.waitForIdle() else if (returning != null) {
            // Hidden, actively returning frames cannot satisfy the contact phase.
            // Inspect each ordinary frame and stop batching as soon as it can.
            val advanced = compose.runOnUiThread {
                fun stillHiddenReturn(): Boolean {
                    assertTrue(drawer.host.entries.size <= 1 && drawer.maximumEntries <= 1)
                    return returning.entry in drawer.host.entries && !returning.entry.mounted &&
                        returning.anchor.hidden && returning.anchor.contact.expansion.isRunning &&
                        returning.anchor.contact.expansion.targetValue == 0f
                }
                var frames = 0
                while (frames < 16 && android.os.SystemClock.uptimeMillis() - started < 5000 && stillHiddenReturn()) {
                    // The public clock executes inline on Main. Native drawing
                    // is deliberately not claimed during this hidden-only batch.
                    compose.mainClock.advanceTimeByFrame()
                    frames++
                    if (!stillHiddenReturn()) break
                }
                frames
            }
            // Yield for native restoration as soon as the hidden gate clears.
            if (advanced == 0 && android.os.SystemClock.uptimeMillis() - started < 5000)
                compose.mainClock.advanceTimeByFrame()
        } else {
            // Two ordinary frames, not one 32ms jump. Predicate/native polling is
            // now every 32ms; the per-composition owner latch still sees both frames.
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
        }
        val onUiResult = compose.runOnUiThread {
            assertTrue("At most one Add-to-playlists owner", drawer.host.entries.size <= 1)
            assertTrue("Every composed owner count stays singular", drawer.maximumEntries <= 1)
            if (predicateOnUiThread) predicate() else null
        }
        if (predicateOnUiThread) onUiResult == true else predicate()
    } } catch (failure: Throwable) {
        runCatching {
            val detail = drawer.host.entries.joinToString { entry ->
                "mounted=${entry.mounted} hidden=${entry.anchor.hidden} modal=${entry.anchor.modalPresent} " +
                    "progress=${entry.anchor.contact.expansion.value} target=${entry.anchor.contact.expansion.targetValue} " +
                    "running=${entry.anchor.contact.expansion.isRunning} virtual=${compose.mainClock.currentTime} trigger=${entry.anchor.triggerMounted} " +
                    "restored=${entry.anchor.restoredDrawn} bounds=${entry.anchor.bounds}"
            }
            failure.addSuppressed(AssertionError("Post-failure non-atomic QueueSave state: entries=[$detail] " +
                "sourceAttached=${drawer.sourceView.isAttachedToWindow} sourceFocused=${drawer.sourceView.hasWindowFocus()} " +
                "destinationObservation=${drawer.destinationObservation}"))
            drawer.destinationDiagnosticFailures.forEach(failure::addSuppressed)
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
    }

    private fun saveButton() = compose.onNodeWithContentDescription("Save queue to playlist")
    private fun popupView() = (compose.onNodeWithTag("glass-morph-surface", useUnmergedTree = true)
        .fetchSemanticsNode().root as ViewRootForTest).view

    private fun screenBounds(node: SemanticsNodeInteraction): Rect {
        val semantics = node.assertIsDisplayed().fetchSemanticsNode()
        val view = (semantics.root as ViewRootForTest).view
        return compose.runOnUiThread {
            val at = IntArray(2).also(view::getLocationOnScreen)
            semantics.boundsInRoot.translate(Offset(at[0].toFloat(), at[1].toFloat()))
        }
    }

    private fun tap(node: SemanticsNodeInteraction, beforeContact: ((Offset) -> Unit)? = null) {
        compose.awaitExpectedWindowFocus(node)
        val point = screenBounds(node).center
        beforeContact?.invoke(point)
        contact(point)
    }

    private fun observeDestinationContact(drawer: Drawer, id: String, row: SemanticsNodeInteraction,
        originalPoint: Offset) {
        val semantics = row.assertIsDisplayed().assertIsNotSelected().assertHasClickAction().fetchSemanticsNode()
        val coordinates = semantics.layoutInfo.coordinates
        val view = (semantics.root as ViewRootForTest).view
        compose.runOnUiThread {
            val source = requireNotNull(drawer.playlistSource) { "No actual playlist adapter draw observation before contact" }
            val visibleKeys = source.state.layoutInfo.visibleItemsInfo.map { it.key }
            assertTrue("Destination is an actual currently visible native list key", id in visibleKeys)
            assertFalse("No earlier destination selection", id in source.chosen)
            assertSame("Placed row and actual adapter use the same native view", source.view, view)
            assertTrue(coordinates.isAttached && view.isAttachedToWindow && view.hasWindowFocus())
            val placed = requireNotNull(source.rows[id]) { "No current placed destination row" }
            assertTrue(placed.isAttached)
            val semanticBounds = mappedBounds(coordinates, view)
            val placedBounds = mappedBounds(placed, view)
            val mappedCenter = mappedPoint(coordinates,
                Offset(coordinates.size.width / 2f, coordinates.size.height / 2f), view)
            val inside = semanticBounds.contains(originalPoint) && placedBounds.contains(originalPoint)
            drawer.destinationObservation = "before-contact original=$originalPoint mappedCenter=$mappedCenter " +
                "delta=${originalPoint - mappedCenter} semanticNative=$semanticBounds placedNative=$placedBounds " +
                "nativeKey=true selected=false lastDrawChosen=${source.chosen.size} inside=$inside " +
                "view=${System.identityHashCode(view)} windowFocus=${view.hasWindowFocus()}"
            runCatching { android.util.Log.i("QueueSaveContact", drawer.destinationObservation) }
            assertTrue("Original contact must lie inside independently mapped current row: ${drawer.destinationObservation}", inside)
        }
    }

    /** Compare two independently observed facts; never treat Add readiness as selection proof.
     * The draw observer describes its last completed public draw, not native pixel consumption.
     */
    private fun observeDestinationState(drawer: Drawer, id: String, selected: Boolean, enabled: Boolean) {
        try { compose.runOnUiThread {
            val source = drawer.playlistSource
            val observation = "after-contact selected=$selected addEnabled=$enabled " +
                "nativeKey=${source?.state?.layoutInfo?.visibleItemsInfo?.any { it.key == id }} " +
                "lastDrawChosen=${source?.chosen?.contains(id)} rowAttached=${source?.rows?.get(id)?.isAttached} " +
                "view=${source?.view?.let(System::identityHashCode)} windowFocus=${source?.view?.hasWindowFocus()}"
            if (observation != drawer.destinationObservation) {
                drawer.destinationObservation = observation
                if (drawer.destinationLogCount++ < 12) runCatching { android.util.Log.i("QueueSaveContact", observation) }
            }
        } } catch (diagnostic: Throwable) {
            // Observations cannot replace the original assertion/deadline failure.
            if (drawer.destinationDiagnosticFailures.size < 3) drawer.destinationDiagnosticFailures += diagnostic
        }
    }

    private fun mappedPoint(coordinates: LayoutCoordinates, point: Offset, view: View): Offset {
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        return coordinates.localToWindow(point) + Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
    }

    private fun mappedBounds(coordinates: LayoutCoordinates, view: View): Rect {
        val width = coordinates.size.width.toFloat(); val height = coordinates.size.height.toFloat()
        val points = listOf(Offset.Zero, Offset(width, 0f), Offset(width, height), Offset(0f, height))
            .map { mappedPoint(coordinates, it, view) }
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun contact(point: Offset) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        var held = false
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native contact action=$action", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        try { send(MotionEvent.ACTION_DOWN); held = true; send(MotionEvent.ACTION_UP); held = false }
        finally { if (held) send(MotionEvent.ACTION_CANCEL) }
    }

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f); assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f); assertEquals(expected.bottom, actual.bottom, 1f)
    }

    private fun dispose(drawer: Drawer) {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { drawer.present = false }
        compose.waitForIdle()
    }
}
