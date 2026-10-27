package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LibraryPlaybackReadTest {
    private data class Query(val sql: String, val arguments: Int, val inTransaction: Boolean)
    private fun song(id: String) = Track("content://fixture/$id", "Title $id", artist = "Artist", album = "Album",
        durationMs = 12345L, fileName = "$id.flac", relativePath = "nested/$id.flac", id = id,
        albumArtist = "Album artist", trackNumber = 7, discNumber = 2, year = 2026, genre = "Genre",
        comment = "Comment", composer = "Composer", lyricist = "Lyricist", modifiedAt = 1234567L)
    private fun result(root: String, name: String, tracks: List<Track>) = ScanResult(FolderSelection(root, name), tracks, 0)
    private fun fullRead(query: Query) = query.sql == "select * from folders order by name collate nocase, uri"
    private fun selectedRead(query: Query) = query.sql.contains("from folder_tracks join folders") && query.sql.contains("trackid in (")

    private suspend fun database(block: suspend (LibraryDatabase, RoomLibraryStore, ConcurrentLinkedQueue<Query>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queries = ConcurrentLinkedQueue<Query>()
        val owner = AtomicReference<LibraryDatabase>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, arguments ->
                queries += Query(sql.replace("`", "").trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT),
                    arguments.size, owner.get()?.inTransaction() == true)
            }, Executor { it.run() }).build()
        owner.set(db)
        try { block(db, RoomLibraryStore(db.libraryDao()), queries) } finally { db.close() }
    }

    @Test fun selectedRecordsMatchFullCanonicalMetadataLocationsAndAvoidFullFolderHydration() = runBlocking {
        database { db, store, queries ->
            val shared = song("shared")
            store.replaceRoot(result("root-z", "zeta", listOf(shared, song("z-only"))))
            store.replaceRoot(result("root-b", "ALPHA", listOf(shared.copy(uri = "content://b/shared", relativePath = "b.flac"), song("b-only"))))
            store.replaceRoot(result("root-a", "alpha", listOf(shared.copy(uri = "content://a/shared", relativePath = "a.flac"), song("a-only"))))
            // A legitimate unlinked tracks row must not inflate the linked-library count.
            db.openHelper.writableDatabase.execSQL("INSERT INTO tracks (id,title,fileName) VALUES ('unlinked','Orphan','orphan.flac')")
            val canonical = store.current()
            val ids = linkedSetOf("z-only", "missing", "shared")
            val originalIds = ids.toSet()
            queries.clear()
            val selected = store.playbackSnapshot(ids)
            val observed = queries.toList()
            assertEquals(canonical.tracks.filter { it.id in ids }, selected.tracks)
            assertEquals(4, selected.totalTracks)
            assertNull(selected.fullSnapshot)
            assertEquals(originalIds, ids)
            val track = selected.tracks.single { it.id == "shared" }
            assertEquals(listOf("root-a", "root-b", "root-z"), track.locations.map { it.folderUri })
            assertEquals(shared.copy(uri = "content://a/shared", relativePath = "a.flac", addedAt = track.addedAt,
                locations = listOf(TrackLocation("root-a", "content://a/shared", "a.flac"),
                    TrackLocation("root-b", "content://b/shared", "b.flac"),
                    TrackLocation("root-z", shared.uri, shared.relativePath))), track)
            assertNotNull(track.addedAt)
            assertTrue("Only bounded requested rows are selected", observed.any(::selectedRead))
            assertEquals(3, observed.single(::selectedRead).arguments)
            assertTrue("Small selection does not hydrate all folders", observed.none(::fullRead))
            assertEquals(1, observed.count { it.sql.startsWith("select count(distinct folder_tracks.trackid)") })
            assertTrue("Count and selected reads execute inside the owning transaction", observed.filter {
                selectedRead(it) || it.sql.startsWith("select count(distinct folder_tracks.trackid)")
            }.all { it.inTransaction })
        }
    }

    @Test fun metadataLocationFolderRenameAndRemovalAreFreshWithoutObserverAdmission() = runBlocking {
        database { _, store, _ ->
            val original = song("shared")
            store.replaceRoot(result("root-z", "Z", listOf(original)))
            store.replaceRoot(result("root-a", "A", listOf(original.copy(uri = "content://a/shared", relativePath = "a.flac"))))
            val initial = store.playbackSnapshot(setOf("shared"))
            val added = initial.tracks.single().addedAt
            store.updateMetadataBatch(listOf(initial.tracks.single().copy(title = "Updated", artist = "New artist",
                album = "New album", albumArtist = "New album artist", durationMs = 98765L, fileName = "updated.flac",
                trackNumber = 9, discNumber = 3, year = 2027, genre = "New genre", comment = "New comment",
                composer = "New composer", lyricist = "New lyricist", modifiedAt = 7654321L, addedAt = -1L)))
            val updated = store.playbackSnapshot(setOf("shared"))
            assertEquals(store.current().tracks, updated.tracks)
            assertEquals("Updated", updated.tracks.single().title)
            assertEquals(added, updated.tracks.single().addedAt)
            assertEquals(initial.tracks.single().locations, updated.tracks.single().locations)

            store.replaceRoot(result("root-z", "0 first", listOf(updated.tracks.single().copy(
                uri = "content://z/new-location", relativePath = "new/location.flac"))))
            val renamed = store.playbackSnapshot(setOf("shared"))
            assertEquals(store.current().tracks, renamed.tracks)
            assertEquals(listOf("root-z", "root-a"), renamed.tracks.single().locations.map { it.folderUri })
            assertEquals("content://z/new-location", renamed.tracks.single().uri)
            assertEquals("new/location.flac", renamed.tracks.single().relativePath)
            assertEquals(added, renamed.tracks.single().addedAt)
            store.removeRoot("root-z")
            val remaining = store.playbackSnapshot(setOf("shared", "absent"))
            assertEquals(store.current().tracks, remaining.tracks)
            assertEquals(1, remaining.totalTracks)
            assertEquals("content://a/shared", remaining.tracks.single().uri)
            store.removeRoot("root-a")
            val removed = store.playbackSnapshot(setOf("shared"))
            assertTrue(removed.tracks.isEmpty())
            assertEquals(0, removed.totalTracks)
        }
    }

    @Test fun emptyAnd900IdBoundaryUseTrueCountWhile901IdsUseCompleteReadFallback() = runBlocking {
        database { _, store, queries ->
            store.replaceRoot(result("root", "Root", List(902) { song(it.toString().padStart(4, '0')) }))
            val canonical = store.current()
            queries.clear()
            val empty = store.playbackSnapshot(emptySet())
            assertTrue(empty.tracks.isEmpty())
            assertEquals(902, empty.totalTracks)
            assertNull(empty.fullSnapshot)
            assertTrue(queries.none(::selectedRead))
            assertTrue(queries.none(::fullRead))

            val boundedIds = canonical.tracks.take(900).mapTo(linkedSetOf()) { it.id }
            queries.clear()
            val bounded = store.playbackSnapshot(boundedIds)
            assertEquals(canonical.tracks.filter { it.id in boundedIds }, bounded.tracks)
            assertEquals(902, bounded.totalTracks)
            assertEquals(900, queries.single(::selectedRead).arguments)
            assertTrue(queries.none(::fullRead))

            val fallbackIds = boundedIds + canonical.tracks[900].id
            queries.clear()
            val fallback = store.playbackSnapshot(fallbackIds)
            assertEquals(canonical.tracks.filter { it.id in fallbackIds }, fallback.tracks)
            assertEquals(902, fallback.totalTracks)
            assertNull(fallback.fullSnapshot)
            assertTrue(queries.any(::fullRead))
            assertTrue("No oversized IN query or independent chunks", queries.none(::selectedRead))
        }
    }

    @Test fun ambiguousTrackSortTiesPreserveCompleteReadStableOrder() = runBlocking {
        database { _, store, queries ->
            val first = song("one").copy(uri = "content://same", title = "Same", relativePath = "same.flac")
            store.replaceRoot(result("root", "Root", listOf(first, first.copy(id = "two"), song("other"))))
            val canonical = store.current()
            val ids = linkedSetOf("two", "one")
            queries.clear()
            val selected = store.playbackSnapshot(ids)
            assertEquals(canonical.tracks.filter { it.id in ids }, selected.tracks)
            assertEquals(3, selected.totalTracks)
            assertTrue(queries.any(::selectedRead))
            assertTrue("Unspecified SQL tie order must not change the full path's stable sort", queries.any(::fullRead))
        }
    }


}
