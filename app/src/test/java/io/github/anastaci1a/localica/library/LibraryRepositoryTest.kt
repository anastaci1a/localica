package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LibraryRepositoryTest {
    private val previous = LibrarySnapshot(listOf(FolderSelection("old", "Old folder")), listOf(
        Track("song", "Existing", fileName = "song.mp3", relativePath = "song.mp3",
            locations = listOf(TrackLocation("old", "song", "song.mp3"))),
    ))

    @Test fun `failed addition preserves every root and releases only the new grant`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val source = FakeDocuments().apply { directoryFailure = IOException("Disconnected") }
        val repository = LibraryRepository(store, TreeScanner(source), grants, StandardTestDispatcher(testScheduler))
        try { repository.scan("new"); fail("Scan should fail") } catch (_: IOException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }

    @Test fun `adding an empty root preserves previous songs and grants`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val repository = LibraryRepository(store, TreeScanner(FakeDocuments()), grants, StandardTestDispatcher(testScheduler))
        repository.scan("new")
        assertEquals(setOf("old", "new"), store.current().folders.map { it.uri }.toSet())
        assertEquals(previous.tracks, store.current().tracks)
        assertEquals(setOf("old", "new"), grants.granted)
    }

    @Test fun `cancelled rescan retains the current folder data and grant`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val source = FakeDocuments().apply { directoryFailure = CancellationException() }
        val repository = LibraryRepository(store, TreeScanner(source), grants, StandardTestDispatcher(testScheduler))
        try { repository.scan("old"); fail("Scan should cancel") } catch (_: CancellationException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }

    @Test fun `database failure preserves existing roots and grants`() = runTest {
        val store = MemoryStore(previous).apply { failWrites = true }
        val grants = FakePermissions("old")
        val repository = LibraryRepository(store, TreeScanner(FakeDocuments()), grants, StandardTestDispatcher(testScheduler))
        try { repository.scan("new"); fail("Write should fail") } catch (_: IOException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }

    @Test fun `failed removal keeps permission and successful removal releases only its grant`() = runTest {
        val store = MemoryStore(previous).apply { failWrites = true }
        val grants = FakePermissions("old", "other")
        val repository = LibraryRepository(store, TreeScanner(FakeDocuments()), grants, StandardTestDispatcher(testScheduler))
        try { repository.removeFolder("old"); fail("Removal should fail") } catch (_: IOException) { }
        assertEquals(setOf("old", "other"), grants.granted)
        assertEquals(previous, store.current())
        store.failWrites = false
        repository.removeFolder("old")
        assertTrue(store.current().folders.isEmpty())
        assertEquals(setOf("other"), grants.granted)
    }

    @Test fun `playback falls back to another granted tree for the same document`() {
        val track = previous.tracks.single().copy(locations = listOf(
            TrackLocation("old", "song", "song.mp3"), TrackLocation("other", "other-song", "nested/song.mp3")))
        val repository = LibraryRepository(MemoryStore(previous), TreeScanner(FakeDocuments()), FakePermissions("other"))
        assertEquals("other-song", repository.playableTrack(track).uri)
        assertEquals(track.id, repository.playableTrack(track).id)
    }
}

private class MemoryStore(initial: LibrarySnapshot) : LibraryStore {
    override suspend fun updateMetadata(track: Track) { snapshots.value = snapshots.value.copy(tracks = snapshots.value.tracks.map { if (it.id == track.id) track else it }) }
    override val snapshots = MutableStateFlow(initial)
    var failWrites = false
    override suspend fun current() = snapshots.value
    override suspend fun replaceRoot(result: ScanResult) {
        if (failWrites) throw IOException("Disk full")
        val before = snapshots.value
        snapshots.value = LibrarySnapshot(before.folders.filterNot { it.uri == result.folder.uri } + result.folder,
            before.tracks.filterNot { it.locations.any { place -> place.folderUri == result.folder.uri } } + result.tracks)
    }
    override suspend fun removeRoot(uri: String) {
        if (failWrites) throw IOException("Disk full")
        snapshots.value = snapshots.value.copy(folders = snapshots.value.folders.filterNot { it.uri == uri },
            tracks = snapshots.value.tracks.filterNot { it.locations.any { place -> place.folderUri == uri } })
    }
}

private class FakePermissions(vararg initial: String) : FolderPermissions {
    val granted = initial.toMutableSet()
    override fun hasReadAccess(uri: String) = uri in granted
    override fun acquire(uri: String) { granted.add(uri) }
    override fun release(uri: String) { granted.remove(uri) }
}
