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
            pump(drawer) {
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
            tap(compose.onNodeWithText("Destination", substring = false))
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
                LocalGlassAppearance provides GlassAppearance(shapeDamping = 2f), LocalSessionSelection provides drawer.selection) {
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
        pump(drawer) { entry.mounted && entry.anchor.hidden && entry.anchor.modalPresent && entry.anchor.contact.expansion.value >= .999f }
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
        pump(drawer) { drawer.host.entries.isEmpty() && !opened.anchor.modalPresent && !opened.anchor.hidden &&
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

    private fun pump(drawer: Drawer, predicate: () -> Boolean) = compose.waitUntil(5000) {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            assertTrue("At most one Add-to-playlists owner", drawer.host.entries.size <= 1)
            predicate()
        }
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

    private fun tap(node: SemanticsNodeInteraction) {
        compose.awaitExpectedWindowFocus(node)
        contact(screenBounds(node).center)
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
