package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LibraryRepositoryTest {
    private val previous = LibrarySnapshot(FolderSelection("old", "Old folder"), listOf(
        Track("song", "Existing", fileName = "song.mp3", relativePath = "song.mp3"),
    ))

    @Test fun `failed folder change preserves library and releases only the new grant`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val source = FakeDocuments().apply { directoryFailure = IOException("Disconnected") }
        val repository = LibraryRepository(store, TreeScanner(source), grants, StandardTestDispatcher(testScheduler))
        try {
            repository.scan("new")
            fail("Scan should fail")
        } catch (_: IOException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }

    @Test fun `successful empty folder replaces old library and releases obsolete grant`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val repository = LibraryRepository(store, TreeScanner(FakeDocuments()), grants, StandardTestDispatcher(testScheduler))
        repository.scan("new")
        assertEquals("new", store.current().folder?.uri)
        assertTrue(store.current().tracks.isEmpty())
        assertEquals(setOf("new"), grants.granted)
    }

    @Test fun `cancelled rescan retains the current folder grant`() = runTest {
        val store = MemoryStore(previous)
        val grants = FakePermissions("old")
        val source = FakeDocuments().apply { directoryFailure = CancellationException() }
        val repository = LibraryRepository(store, TreeScanner(source), grants, StandardTestDispatcher(testScheduler))
        try { repository.scan("old") } catch (_: CancellationException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }

    @Test fun `database failure does not hand off folder permissions`() = runTest {
        val store = MemoryStore(previous).apply { failWrites = true }
        val grants = FakePermissions("old")
        val repository = LibraryRepository(store, TreeScanner(FakeDocuments()), grants, StandardTestDispatcher(testScheduler))
        try { repository.scan("new"); fail("Write should fail") } catch (_: IOException) { }
        assertEquals(previous, store.current())
        assertEquals(setOf("old"), grants.granted)
    }
}

private class MemoryStore(initial: LibrarySnapshot) : LibraryStore {
    override val snapshots = MutableStateFlow(initial)
    var failWrites = false
    override suspend fun current() = snapshots.value
    override suspend fun replace(snapshot: LibrarySnapshot) {
        if (failWrites) throw IOException("Disk full")
        snapshots.value = snapshot
    }
}

private class FakePermissions(vararg initial: String) : FolderPermissions {
    val granted = initial.toMutableSet()
    override fun hasReadAccess(uri: String) = uri in granted
    override fun acquire(uri: String) { granted.add(uri) }
    override fun release(uri: String) { granted.remove(uri) }
}
