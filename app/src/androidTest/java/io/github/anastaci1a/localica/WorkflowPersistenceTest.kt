package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.editing.MetadataRefreshJournal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkflowPersistenceTest {
    @Test fun catalogMigrationPrefersLibraryFilterAndKeepsCollectionsIndependent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("browse-style", 0)
        store.edit().clear()
            .putString("catalog.Album.tab.Library.Album.sort", "Year")
            .putBoolean("catalog.Album.tab.Library.Album.ascending", false)
            .putString("catalog.Album.tab.Albums.All.sort", "Duration")
            .putString("contents.Playlist.Playlist.3:mix.sort", "Title")
            .putString("contents.Playlist.Playlist.3:mix.list-size", "Large").commit()
        try {
            val prefs = BrowsePreferences(context)
            val filter = BrowseRoute(MainTab.Library, filter = MediaKind.Album)
            val tab = BrowseRoute(MainTab.Albums)
            assertEquals(TrackSort.Year, prefs.read(tab).sort)
            assertFalse(prefs.read(tab).ascending)
            assertEquals(prefs.read(filter), prefs.read(tab))
            assertFalse(prefs.overridden(filter))
            val playlist = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix"))
            assertEquals(ListSize.Large, prefs.read(playlist).listSize)
            prefs.write(tab, BrowseStyle(listSize = ListSize.Compact), true)
            assertEquals(ListSize.Compact, BrowsePreferences(context).read(filter).listSize)
            assertEquals(ListSize.Large, prefs.read(playlist).listSize)
            val queue = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "q"))
            prefs.write(queue, BrowseStyle(listSize = ListSize.Large), true)
            assertEquals(prefs.read(queue), prefs.read(queue.copy(tab = MainTab.Library)))
            assertEquals(ListSize.Normal, prefs.read(queue.copy(collection = MediaRef(MediaKind.Queue, "other"))).listSize)
        } finally { store.edit().clear().commit() }
    }

    @Test fun everyListDensitySurvivesPreferencesReopeningByName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        try {
            for (size in ListSize.densityOrder) {
                prefs.write(route, original.copy(listSize = size, extraInfo = ExtraInfo.Artist), false)
                val restored = BrowsePreferences(context).read(route)
                assertEquals(size, restored.listSize)
                assertEquals(ExtraInfo.Artist, restored.extraInfo)
            }
        } finally { prefs.write(route, original, overridden) }
    }

    @Test fun sortedOccurrencesAndManualBaselineSurviveReopeningBothStores() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val qName = "sorted-queue-test.db"; val pName = "sorted-playlist-test.db"
        context.deleteDatabase(qName); context.deleteDatabase(pName)
        var q = Room.databaseBuilder(context, QueueDatabase::class.java, qName).build()
        var p = Room.databaseBuilder(context, PlaylistDatabase::class.java, pName).build()
        val tracks = listOf("Z", "A", "Z").map { Track("song:$it", it, fileName = "$it.mp3", relativePath = "$it.mp3") }
        val queue = QueueEdits.create(tracks, 2, "Queue").copy(positionMs = 1234)
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), tracks)
        val sortedQueue = QueueEdits.sort(queue, TrackSort.Title, true)
        val sortedPlaylist = PlaylistEdits.sort(playlist, TrackSort.Title, true)
        try {
            q.queues().save(QueueLibrary(), QueueLibrary(listOf(sortedQueue), queue.id))
            p.playlists().save(emptyList(), listOf(sortedPlaylist))
            q.close(); p.close()
            q = Room.databaseBuilder(context, QueueDatabase::class.java, qName).build()
            p = Room.databaseBuilder(context, PlaylistDatabase::class.java, pName).build()
            val restoredQueue = q.queues().read().active!!
            val restoredPlaylist = p.playlists().read().single()
            assertEquals(sortedQueue, restoredQueue)
            assertEquals(sortedPlaylist, restoredPlaylist)
            assertEquals(queue.entries, QueueEdits.sort(restoredQueue, TrackSort.Manual, true).entries)
            assertEquals(playlist.entries, PlaylistEdits.sort(restoredPlaylist, TrackSort.Manual, true).entries)
            assertEquals(queue.currentEntryId, restoredQueue.currentEntryId)
            assertEquals(1234L, restoredQueue.positionMs)
        } finally { q.close(); p.close(); context.deleteDatabase(qName); context.deleteDatabase(pName) }
    }

    @Test fun metadataRefreshJournalRetainsEveryFileAcrossRestartAndUriReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "metadata-refresh-test.json")
        val first = Track("song:one", "One", fileName = "one.mp3", relativePath = "one.mp3")
        val second = first.copy(id = "two", uri = "song:two", title = "Two")
        try {
            val journal = MetadataRefreshJournal(file)
            journal.write(listOf(first, second))
            val moved = first.copy(uri = "song:replacement")
            journal.include(moved)
            assertEquals(listOf(second, moved), MetadataRefreshJournal(file).read())
            journal.write(listOf(second))
            assertEquals(listOf(second), MetadataRefreshJournal(file).read())
            journal.write(emptyList())
            assertTrue(MetadataRefreshJournal(file).read().isEmpty())
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
}
