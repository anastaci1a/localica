package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real browse owners retain the same constant inset in every projection. */
class SelectionInsetContextsTest {
    @get:Rule val compose = localicaComposeRule()
    private val tracks = List(30) { Track("inset:$it", "Song ${it.toString().padStart(2, '0')}",
        album = "Inset", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }

    private fun output(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        return File(folder, "selection-inset-api${android.os.Build.VERSION.SDK_INT}/$name").apply { parentFile?.mkdirs() }
    }
    private fun saveFrame(name: String) {
        val image = compose.onNodeWithTag("inset-context-scene").captureToImage()
        output(name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun animatedListCapturesEachRevealAndDismissFrame() = verify(animationProof = true)
    @Test fun list() = verify()
    @Test fun gallery() = verify(presentation = Presentation.Standard)
    @Test fun oneColumnGallery() = verify(presentation = Presentation.Large)
    @Test fun search() = verify(search = true)
    @Test fun popupSearch() = verify(search = true, popup = true)
    @Test fun queue() = verify(queue = true)
    @Test fun albumSubview() = verify(album = true)
    @Test fun artistSubviewWithAlbumCarousel() = verify(artist = true)
    @Test fun playlistSubview() = verify(playlist = true)
    @Test fun freshPointerStopsRealBrowserReveal() = verify(interrupt = "touch")
    @Test fun openingModalStopsRealBrowserReveal() = verify(interrupt = "modal")

    private fun verify(presentation: Presentation = Presentation.List, search: Boolean = false,
        popup: Boolean = false, queue: Boolean = false, album: Boolean = false, artist: Boolean = false, playlist: Boolean = false, interrupt: String? = null, animationProof: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        val library = LibrarySnapshot(tracks = tracks)
        val albumRef = if (album) MediaRef(MediaKind.Album,
            LibraryViews.groups(library, SongListView.Albums, LibraryQuery()).single().id) else null
        val artistRef = if (artist) MediaRef(MediaKind.Artist,
            LibraryViews.groups(library, SongListView.Artists, LibraryQuery()).single().id) else null
        val playlistState = PlaylistState(listOf(ManualPlaylist(id = "inset-playlist", name = "Inset",
            entries = tracks.map { SongRow(it.id, it) })), loading = false)
        val route = BrowseRoute(if (queue) MainTab.Queues else MainTab.Songs,
            collection = if (queue) MediaRef(MediaKind.Queue, "inset-queue") else if (playlist) MediaRef(MediaKind.Playlist, "inset-playlist") else albumRef ?: artistRef,
            search = search, text = if (search) "Song" else "", queryCommitted = search)
        val preferences = BrowsePreferences(context)
        preferences.write(route, preferences.read(route).copy(presentation = presentation, itemsPerRow = if (presentation == Presentation.Large) 1 else 3, quickSearch = false), false)
        val state = QueueUiState(QueueLibrary(listOf(ListeningQueue(id = "inset-queue", name = "Inset",
            entries = tracks.map { QueueEntry(it.id, it) }, currentEntryId = tracks.first().id)), "inset-queue"), loading = false)
        val session = SessionSelection()
        var blocker by mutableStateOf(false)
        lateinit var panel: FloatingSelectionPanelState
        var inset = 0f
        var pixelDensity = 1f
        compose.setContent { EngineTheme(reduced = interrupt == null && !animationProof) {
            panel = rememberFloatingSelectionPanel(session.panelVisible, interrupt == null && !animationProof)
            val density = LocalDensity.current
            pixelDensity = density.density
            SideEffect { inset = with(density) { panel.clearance.toPx() } }
            CompositionLocalProvider(LocalBrowseObscuredInset provides (if (popup) 0.dp else 40.dp + panel.clearance),
                LocalBrowseSelectionPanel provides (if (popup) null else panel), LocalSessionSelection provides session) {
                Box(Modifier.fillMaxSize().testTag("inset-context-scene")) {
                    BrowserScreen(LibraryUiState(library, loading = false), playlistState, route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = interrupt == null && !animationProof,
                        queueState = state, popupDismiss = if (popup) ({}) else null)
                    val anchor = rememberGlassAnchor()
                    Box(Modifier.align(Alignment.TopEnd)) {
                        ActionIcon(AppIcon.ActionEllipsis, "Block inset scrolling", anchor = anchor) { blocker = true }
                    }
                    GlassMenu(blocker, anchor, { blocker = false }) {
                        ActionMenuItem("Close blocker", icon = AppIcon.ActionClose) { blocker = false }
                    }
                    if (!popup) FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                        SessionSelectionPanel(session, null, null, PlaylistState(loading = false))
                    }
                }
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("browser-list")
        list.performScrollToIndex(30)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("media-inset:29")
        val before = last.fetchSemanticsNode().boundsInRoot.top
        if (interrupt != null || animationProof) compose.mainClock.autoAdvance = false
        compose.runOnIdle { session.select(route.collection, listOf(SongRow(tracks.first().id, tracks.first()))) }
        if (animationProof) {
            val rows = mutableListOf("phase,frame,progress,clearance,scroll,ownerPhase,ownerId")
            try {
                var previous = 0f
                repeat(30) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val delta = before - last.fetchSemanticsNode().boundsInRoot.top
                    rows += "reveal,$frame,${panel.progress},$inset,$delta,${compose.runOnIdle { panel.face.view.phase.value() }},${System.identityHashCode(panel.face.view)}"
                    saveFrame("reveal-${frame.toString().padStart(2, '0')}.png")
                    assertTrue("Native reveal stays monotonic", delta >= previous - 1f)
                    assertEquals("Native reveal follows the pill on each frame", inset, delta, 1.1f)
                    previous = delta
                }
                compose.runOnIdle { session.clear() }
                repeat(30) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val delta = before - last.fetchSemanticsNode().boundsInRoot.top
                    rows += "dismiss,$frame,${panel.progress},$inset,$delta"
                    saveFrame("dismiss-${frame.toString().padStart(2, '0')}.png")
                    assertTrue("Native dismissal stays monotonic", delta <= previous + 1f)
                    assertEquals("Native dismissal follows the removed room", inset, delta, 1.1f)
                    previous = delta
                }
                assertEquals("Dismissal returns exactly to the old bottom", 0f, previous, 1.1f)
            } finally {
                output("frames.csv").writeText(rows.joinToString("\n") + "\n")
                compose.mainClock.autoAdvance = true
            }
            return
        }
        if (interrupt != null) {
            try {
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                assertTrue("Reveal moved before interruption", before - last.fetchSemanticsNode().boundsInRoot.top > 1f)
                if (interrupt == "touch") list.performTouchInput { down(center); advanceEventTime(16); up() }
                else compose.onNodeWithContentDescription("Block inset scrolling").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val stopped = last.fetchSemanticsNode().boundsInRoot.top
                compose.mainClock.advanceTimeBy(2000)
                compose.waitForIdle()
                assertEquals("Real input/modal retires the old scroll", stopped, last.fetchSemanticsNode().boundsInRoot.top, 1.1f)
            } finally { compose.mainClock.autoAdvance = true }
            return
        }
        compose.waitForIdle()
        val expected = if (popup) compose.onNodeWithTag("selection-pill").fetchSemanticsNode().boundsInRoot.height + 20f * pixelDensity else inset
        assertEquals("Real host moves by the complete panel inset", expected,
            before - last.fetchSemanticsNode().boundsInRoot.top, 1.1f)
        compose.runOnIdle { session.clear() }
        compose.waitForIdle()
        assertEquals("Real host removes occupied tail", before, last.fetchSemanticsNode().boundsInRoot.top, 1.1f)
    }
}
