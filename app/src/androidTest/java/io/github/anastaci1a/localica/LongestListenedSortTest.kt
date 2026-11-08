package io.github.anastaci1a.localica

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import io.github.anastaci1a.localica.statistics.withListeningStatistics
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #666: the "Longest listened" sort appears beside "Most played", orders by listening time and persists. */
class LongestListenedSortTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sortDialogOffersLongestListenedAfterMostPlayedAndStartsHighestFirst() {
        var saved: BrowseStyle? = null
        compose.setContent { LocalicaTheme {
            BrowseSortDialog(BrowseRoute(MainTab.Songs), BrowseStyle(), false, {}, { value, _ -> saved = value }, {})
        } }
        compose.onNodeWithText("Sort by").performClick()
        compose.onNodeWithText("Title").assertIsSelected()
        val sortLabels = TrackSort.entries.map(::sortLabel).toSet()
        val listed = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()
            .map { it.boundsInRoot.top to (it.config.getOrNull(SemanticsProperties.Text)?.joinToString { text -> text.text } ?: "") }
            .filter { it.second in sortLabels }.sortedBy { it.first }.map { it.second }
        assertEquals(listed.toString(), listed.indexOf("Most played") + 1, listed.indexOf("Longest listened"))
        compose.onNodeWithText("Longest listened").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Select", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select", substring = false).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Longest listened").assertIsDisplayed()
        compose.onNodeWithText("Descending").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Ascending").assertIsNotSelected()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(TrackSort.LongestListened, saved!!.sort)
            assertFalse(saved!!.ascending)
        }
    }

    @Test fun longestListenedPersistsForSongsAlbumsArtistsPlaylistsAndSavedOrders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = BrowsePreferences(context)
        val routes = listOf(BrowseRoute(MainTab.Songs), BrowseRoute(MainTab.Albums), BrowseRoute(MainTab.Artists),
            BrowseRoute(MainTab.Playlists), BrowseRoute(MainTab.Library, MediaRef(MediaKind.Artist, "band")),
            BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix")))
        for (route in routes) {
            val previous = preferences.read(route)
            val overridden = preferences.overridden(route)
            try {
                preferences.write(route, BrowseStyle(sort = TrackSort.LongestListened, ascending = false,
                    extraInfo = ExtraInfo.LongestListened), false)
                val saved = preferences.read(route)
                assertEquals(route.toString(), TrackSort.LongestListened, saved.sort)
                assertFalse(route.toString(), saved.ascending)
                assertEquals(route.toString(), ExtraInfo.LongestListened, saved.extraInfo)
                assertEquals(route.toString(), LibraryQuery(sort = TrackSort.LongestListened, ascending = false),
                    preferences.sortSnapshot().query(route))
            } finally {
                if (overridden) preferences.write(route, previous, true) else preferences.write(route, previous, false)
            }
        }
        val ids = listOf("b", "a", "c")
        val saved = SavedOrder(ids, TrackSort.LongestListened, false)
        assertEquals(saved, SavedOrderCodec.decode(SavedOrderCodec.encode(saved), ids))
    }

    @Test fun statisticsOverlayOrdersSongsAlbumsAndArtistsByListeningTime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = RoomLibraryStore(database.libraryDao())
            val repository = LibraryRepository(store, TreeScanner(AndroidDocuments(context)),
                PersistedFolderPermissions(context.contentResolver))
            val short = Track("short", "Short", artist = "Solo", album = "Singles", durationMs = 60_000,
                fileName = "short.mp3", relativePath = "short.mp3")
            val long = Track("long", "Long", artist = "Band", album = "Epic", durationMs = 900_000,
                fileName = "long.mp3", relativePath = "long.mp3")
            store.replaceRoot(ScanResult(FolderSelection("root", "Music"), listOf(short, long), 0))
            val counts = MutableStateFlow(ListeningStatistics(mapOf("short" to 10L, "long" to 1L)))
            val source = repository.sortSnapshots.withListeningStatistics(counts)
            val byTime = LibraryQuery(sort = TrackSort.LongestListened, ascending = false)
            val byPlays = LibraryQuery(sort = TrackSort.MostPlayed, ascending = false)
            fun titles(snapshot: LibrarySnapshot, tab: MainTab) =
                BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(tab), byTime).items.map { it.title }

            // 10 x 60 s = 600 s against 1 x 900 s: more plays, but less listening time.
            val before = source.first()
            assertEquals(listOf("short", "long"), LibraryQueries.tracks(before, byPlays).map { it.id })
            assertEquals(listOf("long", "short"), LibraryQueries.tracks(before, byTime).map { it.id })
            assertEquals(listOf("Epic", "Singles"), titles(before, MainTab.Albums))
            assertEquals(listOf("Band", "Solo"), titles(before, MainTab.Artists))
            assertEquals("15:00", BrowseQueries.evaluate(before, emptyList(), BrowseRoute(MainTab.Albums), byTime)
                .items.first().information(BrowseStyle(sort = TrackSort.LongestListened)))

            counts.value = ListeningStatistics(mapOf("short" to 20L, "long" to 1L))
            val after = source.first()
            assertEquals(listOf("short", "long"), LibraryQueries.tracks(after, byTime).map { it.id })
            assertEquals(listOf("Singles", "Epic"), titles(after, MainTab.Albums))
            assertEquals(listOf("Solo", "Band"), titles(after, MainTab.Artists))
        } finally { database.close() }
    }
}
