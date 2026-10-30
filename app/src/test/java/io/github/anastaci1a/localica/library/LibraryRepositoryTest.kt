package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LibraryRepositoryTest {
    @Test fun `manual scan waits for startup merge then refreshes instead of being lost`() = runTest {
        val store = MemoryStore(previous)
        val files = FakeDocuments().apply {
            entries["root"] = listOf(audio("song", "song.mp3"), audio("new", "new.mp3"))
            tags["song"] = AudioMetadata(title = "Refreshed manually")
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        val source = object : DocumentSource by files {
            override suspend fun metadata(uri: String, fileName: String, listedModifiedAt: Long?): AudioMetadata {
                if (hold) { hold = false; entered.complete(Unit); release.await() }
                return files.metadata(uri)
            }
        }
        val repository = LibraryRepository(store, TreeScanner(source), FakePermissions("old"), StandardTestDispatcher(testScheduler))
        val startup = async { repository.scanNewFiles("old") }
        entered.await()
        val manual = async { repository.scan("old") }
        testScheduler.runCurrent()
        assertFalse(manual.isCompleted)
        assertEquals(0, files.metadataReads)
        assertEquals(previous, store.current())
        release.complete(Unit)
        startup.await()
        manual.await()
        assertEquals(3, files.metadataReads)
        assertEquals(2, store.current().tracks.size)
        assertEquals("Refreshed manually", store.current().tracks.single { it.id == "song" }.title)
    }

    @Test fun `unchanged startup avoids metadata and database writes while manual scan refreshes`() = runTest {
        val initial = previous.copy(tracks = previous.tracks.map { it.copy(addedAt = 7L) })
        val store = MemoryStore(initial)
        val source = FakeDocuments().apply {
            entries["root"] = listOf(audio("song", "song.mp3"))
            tags["song"] = AudioMetadata(title = "Changed")
        }
        val repository = LibraryRepository(store, TreeScanner(source), FakePermissions("old"), StandardTestDispatcher(testScheduler))
        repository.scanNewFiles("old")
        assertEquals(initial, store.current())
        assertEquals(0, source.metadataReads)
        assertEquals(0, store.merges)
        repository.scan("old")
        assertEquals(1, source.metadataReads)
        assertEquals("Changed", store.current().tracks.single().title)
    }

    @Test fun `startup adds new identities and overlapping memberships without changing old metadata`() = runTest {
        val initial = previous.copy(folders = previous.folders + FolderSelection("other", "Other"))
        val store = MemoryStore(initial)
        val source = FakeDocuments().apply {
            entries["root"] = listOf(audio("song", "song.mp3"), audio("new", "new.mp3").copy(modifiedAt = 1L))
            tags["song"] = AudioMetadata(title = "Must not read")
            tags["new"] = AudioMetadata(title = "New song")
        }
        val repository = LibraryRepository(store, TreeScanner(source), FakePermissions("old", "other"), StandardTestDispatcher(testScheduler))
        repository.scanNewFiles("old")
        val saved = store.current().tracks.associateBy { it.id }
        assertEquals(initial.tracks.single(), saved["song"])
        assertEquals(1, source.metadataReads)
        repository.scanNewFiles("other")
        assertEquals(1, source.metadataReads)
        assertEquals(2, store.current().tracks.size)
        assertTrue(store.current().tracks.all { it.locations.map { place -> place.folderUri }.toSet() == setOf("old", "other") })
        assertEquals(saved["new"]?.addedAt, store.current().tracks.single { it.id == "new" }.addedAt)
    }

    @Test fun `startup empty failed and canceled listings keep cached songs and grants`() = runTest {
        val store = MemoryStore(previous)
        val source = FakeDocuments()
        val permissions = FakePermissions("old")
        val repository = LibraryRepository(store, TreeScanner(source), permissions, StandardTestDispatcher(testScheduler))
        repository.scanNewFiles("old")
        assertEquals(previous, store.current())
        source.entries["root"] = listOf(audio("new", "new.mp3"), directory("nested"))
        source.entries["nested"] = emptyList()
        source.metadataFailure = CancellationException()
        source.failureUri = "new"
        try { repository.scanNewFiles("old"); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        source.metadataFailure = null
        source.directoryFailure = IOException("Unavailable")
        try { repository.scanNewFiles("old"); fail("Failure must propagate") } catch (_: IOException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), permissions.granted)
        assertEquals(0, store.merges)
    }

    @Test fun `startup never reacquires revoked access or recreates a removed root`() = runTest {
        val store = MemoryStore(previous)
        val permissions = FakePermissions()
        val source = FakeDocuments()
        val repository = LibraryRepository(store, TreeScanner(source), permissions, StandardTestDispatcher(testScheduler))
        try { repository.scanNewFiles("old"); fail("Revoked access must fail") } catch (_: IllegalStateException) { }
        try { repository.scanNewFiles("removed"); fail("Removed roots must fail") } catch (_: IllegalArgumentException) { }
        assertTrue(permissions.granted.isEmpty())
        assertTrue(source.queried.isEmpty())
        assertEquals(previous, store.current())
    }

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
    @Test fun folderAvailabilityUsesOneBatchWithUniqueExactUris() {
        var calls = 0
        var requested: Set<String>? = null
        val permissions = object : FolderPermissions {
            override fun hasReadAccess(uri: String): Boolean = error("Batch must not probe each root")
            override fun readable(uris: Set<String>): Set<String> {
                calls++; requested = uris; return setOf("root", "unrequested")
            }
            override fun acquire(uri: String) = Unit
            override fun release(uri: String) = Unit
        }
        val repository = LibraryRepository(MemoryStore(previous), TreeScanner(FakeDocuments()), permissions)
        val missing = repository.unavailableFolders(listOf(FolderSelection("root", "One"),
            FolderSelection("root", "Alias"), FolderSelection("root/child", "Child"), FolderSelection("other", "Other")))
        assertEquals(1, calls)
        assertEquals(setOf("root", "root/child", "other"), requested)
        assertEquals(setOf("root/child", "other"), missing)
    }

    @Test fun defaultPermissionBatchRetainsLiveExactReadChecks() {
        val permissions = FakePermissions("root")
        val repository = LibraryRepository(MemoryStore(previous), TreeScanner(FakeDocuments()), permissions)
        val folders = listOf(FolderSelection("root", "Root"), FolderSelection("root/child", "Child"))
        assertEquals(setOf("root/child"), repository.unavailableFolders(folders))
        permissions.release("root"); permissions.acquire("root/child")
        assertEquals(setOf("root"), repository.unavailableFolders(folders))
        assertTrue(repository.unavailableFolders(emptyList()).isEmpty())
    }
}

private class MemoryStore(initial: LibrarySnapshot) : LibraryStore {
    override suspend fun updateMetadata(track: Track) { snapshots.value = snapshots.value.copy(tracks = snapshots.value.tracks.map { if (it.id == track.id) track else it }) }
    override val snapshots = MutableStateFlow(initial)
    var failWrites = false
    var merges = 0
    override suspend fun mergeRoot(result: ScanResult) {
        if (failWrites) throw IOException("Disk full")
        merges++
        val before = snapshots.value
        val tracks = before.tracks.associateByTo(linkedMapOf()) { it.id }
        result.tracks.forEach { track ->
            val saved = tracks[track.id]
            val location = TrackLocation(result.folder.uri, track.uri, track.relativePath)
            tracks[track.id] = (saved ?: track.copy(addedAt = 123L)).let {
                if (it.locations.any { place -> place.folderUri == result.folder.uri }) it
                else it.copy(locations = it.locations + location)
            }
        }
        snapshots.value = before.copy(tracks = tracks.values.toList())
    }
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
