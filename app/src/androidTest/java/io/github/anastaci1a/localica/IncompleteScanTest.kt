package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class IncompleteScanTest {
    @Test fun incompleteRootResultsKeepSongsLocationsDatesAndGrantsUntilCompleteRetry() = fixture {
        repository.scan(root)
        repository.scan(other)
        val before = store.current()
        assertEquals(2, before.tracks.size)
        assertTrue(before.tracks.all { it.locations.size == 2 && it.addedAt != null })
        val grants = persisted()
        for (mode in listOf("loading-empty", "loading-partial", "error", "error-empty", "null",
            "loading-malformed", "error-malformed")) {
            mode(mode)
            expectFailure { repository.scan(root) }
            assertEquals(mode, before, store.current())
            assertEquals(mode, grants, persisted())
            assertCursorsClosed()
        }
        mode("updated")
        repository.scan(root)
        val after = store.current()
        assertEquals(3, after.tracks.size)
        for (old in before.tracks) {
            val current = after.tracks.single { it.id == old.id }
            assertEquals(old.addedAt, current.addedAt)
            assertEquals(old.locations, current.locations)
        }
        assertEquals(grants, persisted())
        assertCursorsClosed()
    }

    @Test fun nestedFailureAndCancellationDoNotPublishEarlierDirectoryRowsOrRevertOtherRoots() = fixture {
        repository.scan(root)
        mode("updated")
        repository.scan(other)
        val before = store.current()
        assertEquals(3, before.tracks.size)
        val grants = persisted()
        for (mode in listOf("nested-loading-partial", "nested-error", "nested-null")) {
            mode(mode)
            metadataReads = 0
            expectFailure { repository.scan(root) }
            assertTrue("Root songs were read before the nested failure", metadataReads > 0)
            assertEquals(before, store.current())
            assertEquals(grants, persisted())
            assertCursorsClosed()
        }
        mode("complete")
        cancelMetadata = true
        try {
            repository.scan(root)
            fail("Scan should cancel")
        } catch (_: CancellationException) { }
        finally { cancelMetadata = false }
        assertEquals(before, store.current())
        assertEquals(grants, persisted())
        assertCursorsClosed()
        // A truly empty, successful listing is still allowed to remove this root's membership.
        mode("empty")
        repository.scan(root)
        assertEquals(before.tracks.map { it.id }.toSet(), store.current().tracks.map { it.id }.toSet())
        assertTrue(store.current().tracks.all { track -> track.locations.all { it.folderUri == other } })
        assertEquals(grants, persisted())
    }

    @Test fun failedNewRootReleasesOnlyItsNewPersistedGrant() = fixture {
        repository.scan(other)
        val before = store.current()
        val grants = persisted()
        assertFalse(permissions.hasReadAccess(newRoot))
        for (mode in listOf("loading-empty", "error", "null")) {
            mode(mode)
            expectFailure { repository.scan(newRoot) }
            assertEquals(before, store.current())
            assertEquals(grants, persisted())
            assertFalse(permissions.hasReadAccess(newRoot))
            assertCursorsClosed()
        }
        mode("complete")
        repository.scan(newRoot)
        assertTrue(permissions.hasReadAccess(newRoot))
        assertEquals(2, store.current().folders.size)
        assertCursorsClosed()
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.prepare(); fixture.block() } finally { fixture.close() }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val resolver = context.contentResolver
        private val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = RoomLibraryStore(database.libraryDao())
        val permissions = PersistedFolderPermissions(resolver)
        val root = tree("root")
        val other = tree("other")
        val newRoot = tree("new-root")
        var metadataReads = 0
        var cancelMetadata = false
        private val documents = AndroidDocuments(context)
        // Exercise actual resolver/provider listings without requiring decoder fixtures.
        private val source = object : DocumentSource by documents {
            override suspend fun metadata(uri: String, fileName: String): AudioMetadata {
                metadataReads++
                if (cancelMetadata) throw CancellationException("Fixture cancellation")
                return AudioMetadata(title = fileName, durationMs = 120_000)
            }
        }
        val repository = LibraryRepository(store, TreeScanner(source), permissions)
        private fun tree(id: String) = DocumentsContract.buildTreeDocumentUri(IncompleteScanGrantActivity.AUTHORITY, id).toString()
        private fun grant(action: String) {
            context.startActivity(Intent(action)
                .setClassName(instrumentation.context.packageName, IncompleteScanGrantActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        suspend fun prepare() {
            assertEquals(IncompleteScanGrantActivity.TARGET, context.packageName)
            listOf(root, other, newRoot).forEach(permissions::release)
            grant(IncompleteScanGrantActivity.GRANT)
            withTimeout(10_000) {
                while (true) {
                    try { documents.root(newRoot); break } catch (_: SecurityException) { delay(25) }
                }
            }
            mode("complete")
        }
        fun mode(value: String) { resolver.call(Uri.parse(root), "scanFixtureMode", value, null) }
        fun persisted() = resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()
        suspend fun expectFailure(block: suspend () -> Unit) {
            try { block(); fail("Incomplete listing must fail") } catch (_: IOException) { }
        }
        fun assertCursorsClosed() {
            val stats = requireNotNull(resolver.call(Uri.parse(root), "scanFixtureStats", null, null))
            assertEquals("Every provider cursor must close, including rejected results", stats.getInt("opened"), stats.getInt("closed"))
        }
        suspend fun close() {
            try {
                listOf(root, other, newRoot).forEach(permissions::release)
                grant(IncompleteScanGrantActivity.REVOKE)
                // Do not let asynchronous activity cleanup race the next fixture's grant.
                withTimeout(10_000) {
                    while (true) {
                        try { documents.root(newRoot) } catch (_: SecurityException) { break }
                        delay(25)
                    }
                }
            } finally { database.close() }
        }
    }
}
