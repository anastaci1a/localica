package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.browse.BrowseSortSnapshot
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class PinnedHomePreparationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyPinsSkipCatalogPreparationAcrossSourcesAndRepinFreshRows() {
        fun track(id: String, title: String) = Track("empty-home:$id", title,
            artist = "Artist", album = "Album", fileName = "$id.mp3", relativePath = "$id.mp3")
        val first = track("first", "First pinned")
        var snapshot by mutableStateOf(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")),
            tracks = listOf(first, track("second", "Second"), track("third", "Third"))))
        val playlists = PlaylistState(loading = false)
        var pins by mutableStateOf<List<MediaRef>>(emptyList())
        val pin = MediaRef(MediaKind.Song, first.id)
        val populatedPins = listOf(pin)
        val cache = BrowsePresentationCache()
        val dispatches = AtomicInteger()
        val permitQuery = AtomicBoolean(false)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches.incrementAndGet()
                assertTrue("An empty Home pin request must not dispatch catalog preparation", permitQuery.get())
                Dispatchers.Default.dispatch(context, block)
            }
        }
        var browseCalls = 0
        var opened: BrowserItem? = null
        var openedRows: List<SongRow> = emptyList()
        compose.setContent {
            LocalicaTheme {
                CompositionLocalProvider(LocalBrowsePresentationCache provides cache,
                    LocalBrowseSortSnapshot provides BrowseSortSnapshot.Default) {
                    PinnedHome(LibraryUiState(snapshot, loading = false), playlists, pins,
                        onSettings = {}, onBrowse = { browseCalls++ },
                        onOpen = { item, rows -> opened = item; openedRows = rows },
                        onPin = {}, onMove = { _, _ -> }, reducedMotion = true, queryDispatcher = dispatcher)
                }
            }
        }
        fun awaitEmpty() {
            val current = compose.runOnIdle { snapshot }
            compose.waitUntil(5_000) { cache.home(current, playlists.playlists, emptyList(), BrowseSortSnapshot.Default)?.isEmpty() == true }
            compose.onNodeWithText("Hold a song, album, or playlist and choose Pin to Home.").assertIsDisplayed()
            compose.onNodeWithTag("pin-${first.id}").assertDoesNotExist()
        }
        fun awaitPin() {
            val current = compose.runOnIdle { snapshot }
            compose.waitUntil(5_000) { cache.home(current, playlists.playlists, populatedPins, BrowseSortSnapshot.Default)?.isNotEmpty() == true }
            compose.onNodeWithTag("pin-${first.id}").assertIsDisplayed()
        }
        awaitEmpty()
        assertEquals(0, dispatches.get())
        compose.runOnIdle { permitQuery.set(true); pins = populatedPins }
        awaitPin()
        val populatedDispatches = dispatches.get()
        assertTrue("Populated Home still prepares the real catalog", populatedDispatches > 0)
        compose.runOnIdle { permitQuery.set(false); pins = emptyList() }
        awaitEmpty()
        assertEquals(populatedDispatches, dispatches.get())
        compose.runOnIdle { snapshot = snapshot.withPlayCounts(mapOf(first.id to 2L), emptyMap()) }
        awaitEmpty()
        assertEquals(populatedDispatches, dispatches.get())
        compose.runOnIdle { snapshot = snapshot.copy(tracks = snapshot.tracks.map {
            if (it.id == first.id) it.copy(title = "Fresh pinned") else it
        } + track("fourth", "Fourth")) }
        awaitEmpty()
        assertEquals(populatedDispatches, dispatches.get())
        compose.onNodeWithText("4 songs in your library").assertIsDisplayed()
        compose.onNodeWithText("Browse all music").performClick()
        compose.runOnIdle { assertEquals(1, browseCalls); assertNull(opened) }
        compose.runOnIdle { permitQuery.set(true); pins = populatedPins }
        awaitPin()
        compose.onNodeWithText("Fresh pinned").assertIsDisplayed()
        compose.onNodeWithTag("pin-${first.id}").performClick()
        compose.runOnIdle {
            val row = requireNotNull(opened?.row)
            assertEquals(first.id, row.track.id)
            assertEquals("Fresh pinned", row.track.title)
            assertEquals(2L, row.track.playCount)
            assertSame(snapshot.tracks.first { it.id == first.id }, row.track)
            assertEquals(listOf(first.id), openedRows.map { it.track.id })
        }
        assertTrue("Re-pinning still uses fresh catalog preparation", dispatches.get() > populatedDispatches)
        Log.i("HomePreparationWork", "initialEmptyDispatches=0 populatedDispatches=$populatedDispatches " +
            "emptySourceUpdateDispatches=0 finalDispatches=${dispatches.get()} opened=${opened?.row?.track?.id}")
    }
}
