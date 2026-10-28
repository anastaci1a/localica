package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class IncompleteScanTest {
    @Test fun startupControllerContinuesOtherRootsAfterOneProviderFails() = fixture {
        repository.scan(root)
        repository.scan(other)
        val before = store.current()
        mode("updated")
        unavailableRoot = root
        metadataReads = 0
        val finished = CompletableDeferred<Unit>()
        val controller = io.github.anastaci1a.localica.scanner.ScanController(
            InstrumentationRegistry.getInstrumentation().targetContext, repository)
        withContext(Dispatchers.Main) { controller.run(listOf(root, other), newFilesOnly = true) { finished.complete(Unit) } }
        withTimeout(10_000) { finished.await() }
        assertFalse(controller.state.value.running)
        assertEquals(2, controller.state.value.completedFolders)
        assertTrue(controller.state.value.message.orEmpty().contains("1 folder"))
        assertEquals(1, metadataReads)
        val after = store.current()
        assertEquals(3, after.tracks.size)
        before.tracks.forEach { assertEquals(it, after.tracks.single { current -> current.id == it.id }) }
        assertEquals(listOf(other), after.tracks.single { track -> before.tracks.none { it.id == track.id } }.locations.map { it.folderUri })
        assertCursorsClosed()
    }

    @Test fun manualFullRequestSurvivesAnActiveQuickScanForTheSameRoot() = fixture {
        repository.scan(root)
        val original = store.current().tracks
        original.forEach { store.updateMetadata(it.copy(title = "Cached edited title")) }
        mode("updated")
        metadataReads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeMetadata = { entered.complete(Unit); release.await(); beforeMetadata = null }
        val finished = CompletableDeferred<Unit>()
        val controller = io.github.anastaci1a.localica.scanner.ScanController(
            InstrumentationRegistry.getInstrumentation().targetContext, repository)
        try {
            withContext(Dispatchers.Main) { controller.run(listOf(root), newFilesOnly = true) { finished.complete(Unit) } }
            withTimeout(10_000) { entered.await() }
            withContext(Dispatchers.Main) { controller.run(listOf(root), newFilesOnly = false) { } }
            release.complete(Unit)
            withTimeout(10_000) { finished.await() }
            assertFalse(controller.state.value.running)
            assertEquals(2, controller.state.value.completedFolders)
            assertEquals("One new-file read, then all three files during the queued full scan", 4, metadataReads)
            val after = store.current()
            assertEquals(3, after.tracks.size)
            assertTrue("Full scan refreshes cached metadata after additive discovery",
                after.tracks.none { it.title == "Cached edited title" })
            assertCursorsClosed()
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.Main) { controller.cancel() }
            withTimeout(10_000) { finished.await() }
        }
    }

    @Test fun startupDiscoverySkipsKnownFileOpensAndAddsOverlapsWithoutReplacingCachedRows() = fixture {
        repository.scan(root)
        repository.scan(other)
        val before = store.current()
        val grants = persisted()
        metadataReads = 0
        probeFileOpens = true
        val initialStats = stats()
        val started = System.nanoTime()
        val unchanged = repository.scanNewFiles(root)
        val overlap = repository.scanNewFiles(other)
        assertEquals(0, metadataReads)
        assertEquals(0, stats().getInt("fileOpens") - initialStats.getInt("fileOpens"))
        assertEquals(4, stats().getInt("childQueries") - initialStats.getInt("childQueries"))
        assertEquals(0, unchanged.metrics.metadataReads + overlap.metrics.metadataReads)
        assertEquals(before, store.current())
        android.util.Log.i("StartupScanBenchmark", "unchanged roots=2 tracks=2 childQueries=4 fileOpens=0 elapsedNanos=${System.nanoTime() - started}")
        mode("updated")
        val added = repository.scanNewFiles(root)
        repository.scanNewFiles(other)
        assertEquals(1, metadataReads)
        assertEquals(1, stats().getInt("fileOpens") - initialStats.getInt("fileOpens"))
        assertEquals(1, added.metrics.metadataReads)
        val after = store.current()
        assertEquals(3, after.tracks.size)
        assertTrue(after.tracks.all { it.locations.size == 2 && it.addedAt != null })
        before.tracks.forEach { assertEquals(it, after.tracks.single { current -> current.id == it.id }) }
        mode("empty")
        repository.scanNewFiles(root)
        assertEquals(after, store.current())
        assertEquals(grants, persisted())
        repository.scan(root)
        assertTrue(store.current().tracks.all { track -> track.locations.all { it.folderUri == other } })
        assertCursorsClosed()
    }

    @Test fun interruptedStartupDoesNotPublishPartialAdditionsAndCanRetryAfterRelaunch() = fixture {
        repository.scan(root)
        val before = store.current()
        for (status in listOf("loading-partial", "error", "null", "nested-loading-partial", "nested-error", "nested-null")) {
            mode(status)
            expectFailure { repository.scanNewFiles(root) }
            assertEquals(before, store.current())
        }
        mode("updated")
        cancelMetadata = true
        try { repository.scanNewFiles(root); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        finally { cancelMetadata = false }
        assertEquals(before, store.current())
        // A fresh repository models loss of the process-local scanner; indexed identity is durable.
        freshRepository().scanNewFiles(root)
        val after = store.current()
        assertEquals(3, after.tracks.size)
        before.tracks.forEach { assertEquals(it, after.tracks.single { current -> current.id == it.id }) }
        assertCursorsClosed()
    }

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
            // Each picker retry supplies a fresh persistable grant after failed-scan cleanup.
            grant(IncompleteScanGrantActivity.GRANT)
            expectFailure("New root mode: $mode") { repository.scan(newRoot) }
            assertEquals(before, store.current())
            assertEquals(grants, persisted())
            assertFalse(permissions.hasReadAccess(newRoot))
            assertCursorsClosed()
        }
        mode("complete")
        grant(IncompleteScanGrantActivity.GRANT)
        repository.scan(newRoot)
        assertTrue(permissions.hasReadAccess(newRoot))
        assertEquals(2, store.current().folders.size)
        assertCursorsClosed()
    }

    @Test fun songInfoUsesVerifiedAncestorNamesWithinExistingGrantAndSurvivesRevocation() = fixture {
        repository.scan(root) // The existing fixture persists only this explicitly granted synthetic tree.
        val folders = listOf(
            FolderSelection(songInfoTree("si-a-90"), "Cached A"),
            FolderSelection(songInfoTree("si-b-44"), "Cached B"))
        val before = persisted()
        assertTrue("The broader existing root grant is readable", before.any { it.first == root && it.second })
        val resolved = songInfoNames(folders)
        assertEquals(listOf("root", "1", "Media"), resolved[0].components)
        assertEquals(listOf("root", "2", "Media"), resolved[1].components)
        assertEquals(listOf("1/Media", "2/Media"), songRootLabels(resolved).values.toList())
        assertEquals("Name resolution must not acquire or widen grants", before, persisted())
        val unsupported = songInfoNames(listOf(FolderSelection(songInfoTree("nested"), "Cached nested")))
        assertEquals(listOf("Cached nested"), unsupported.single().components)
        assertEquals(before, persisted())
        assertCursorsClosed()
        grant(IncompleteScanGrantActivity.REVOKE)
        val afterRevoke = persisted()
        assertFalse("Revocation removes the root read grant", afterRevoke.any { it.first == root && it.second })
        val unavailable = songInfoNames(folders)
        assertEquals(listOf("Cached A"), unavailable[0].components)
        assertEquals(listOf("Cached B"), unavailable[1].components)
        assertEquals("Revoked access must stay revoked", afterRevoke, persisted())
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        var primaryFailure: Throwable? = null
        try {
            fixture.prepare()
            fixture.block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { fixture.close() }
            catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
            }
        }
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
        var probeFileOpens = false
        var beforeMetadata: (suspend () -> Unit)? = null
        var unavailableRoot: String? = null
        private val documents = AndroidDocuments(context)
        // Exercise actual resolver/provider listings without requiring decoder fixtures.
        private val source = object : DocumentSource by documents {
            override fun root(treeUri: String): ScanDocument {
                if (treeUri == unavailableRoot) throw IOException("Fixture root unavailable")
                return documents.root(treeUri)
            }
            override suspend fun metadata(uri: String, fileName: String): AudioMetadata {
                metadataReads++
                beforeMetadata?.invoke()
                if (cancelMetadata) throw CancellationException("Fixture cancellation")
                if (probeFileOpens) {
                    // Count real provider open attempts; this synthetic provider intentionally has no audio bytes.
                    try { resolver.openFileDescriptor(Uri.parse(uri), "r")?.close() } catch (_: java.io.FileNotFoundException) { }
                }
                return AudioMetadata(title = fileName, durationMs = 120_000)
            }
        }
        val repository = LibraryRepository(store, TreeScanner(source), permissions)
        fun freshRepository() = LibraryRepository(store, TreeScanner(source), permissions)
        fun stats(): Bundle = requireNotNull(resolver.call(Uri.parse(root), "scanFixtureStats", null, null))
        private fun tree(id: String) = DocumentsContract.buildTreeDocumentUri(IncompleteScanGrantActivity.AUTHORITY, id).toString()
        fun songInfoTree(id: String) = tree(id)
        suspend fun songInfoNames(folders: List<FolderSelection>) = withContext(Dispatchers.IO) { resolveSongRootNames(context, folders) }
        suspend fun grant(action: String, phase: String = "body") {
            val requestId = java.util.UUID.randomUUID().toString()
            android.util.Log.i("ScanGrantFixture", "request id=$requestId action=$action phase=$phase")
            val completed = CompletableDeferred<Unit>()
            val completion = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    android.util.Log.i("ScanGrantFixture", "callback id=$requestId action=$action phase=$phase")
                    completed.complete(Unit)
                }
            }
            context.startActivity(Intent(action)
                .setClassName(instrumentation.context.packageName, IncompleteScanGrantActivity::class.java.name)
                .putExtra("completion", completion)
                .putExtra("request-id", requestId)
                .putExtra("phase", phase)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Read access alone cannot prove that a fresh persistable grant has arrived.
            try { withTimeout(10_000) { completed.await() } }
            catch (failure: TimeoutCancellationException) {
                throw AssertionError("Synthetic grant activity callback timed out: id=$requestId action=$action phase=$phase", failure)
            }
        }
        suspend fun prepare() {
            assertEquals(IncompleteScanGrantActivity.TARGET, context.packageName)
            listOf(root, other, newRoot).forEach(permissions::release)
            grant(IncompleteScanGrantActivity.GRANT, "prepare")
            try {
                withTimeout(10_000) {
                    while (true) {
                        try { documents.root(newRoot); break } catch (_: SecurityException) { delay(25) }
                    }
                }
            } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Synthetic grant read readiness timed out during prepare", failure)
            }
            mode("complete")
        }
        fun mode(value: String) { resolver.call(Uri.parse(root), "scanFixtureMode", value, null) }
        fun persisted() = resolver.persistedUriPermissions.map { Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission) }.toSet()
        suspend fun expectFailure(label: String = "Incomplete listing", block: suspend () -> Unit) {
            try { block(); fail("$label must fail") }
            catch (_: IOException) { }
            catch (failure: SecurityException) { throw AssertionError("$label failed before listing validation", failure) }
        }
        fun assertCursorsClosed() {
            val stats = requireNotNull(resolver.call(Uri.parse(root), "scanFixtureStats", null, null))
            assertEquals("Every provider cursor must close, including rejected results", stats.getInt("opened"), stats.getInt("closed"))
        }
        suspend fun close() {
            try {
                listOf(root, other, newRoot).forEach(permissions::release)
                grant(IncompleteScanGrantActivity.REVOKE, "cleanup")
                // Do not let asynchronous activity cleanup race the next fixture's grant.
                try {
                    withTimeout(10_000) {
                        while (true) {
                            try { documents.root(newRoot) } catch (_: SecurityException) { break }
                            delay(25)
                        }
                    }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError("Synthetic grant removal timed out during cleanup", failure)
                }
            } finally { database.close() }
        }
    }
}
