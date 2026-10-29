package io.github.anastaci1a.localica

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Direct unregistered provider and owned opaque fixtures only: no grants or scanner claims. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusDocumentsProviderTest {
    private val authority = NavigationRandomCorpusDocumentsProvider.AUTHORITY
    private val rootId = NavigationRandomCorpusDocumentsProvider.ROOT
    private val overlapId = NavigationRandomCorpusDocumentsProvider.OVERLAP

    @Test
    fun graphHasExactNumericOrderAndOverlapUsesTheSameFirst100Identities() {
        val binding = plannedBinding()
        val graph = NavigationRandomCorpusDocumentsProvider.Graph(binding)
        val all = graph.children(rootId)
        val overlap = graph.children(overlapId)
        assertEquals(13_313, all.size)
        assertEquals(all.take(100), overlap)
        for (index in all.indices) {
            val row = all[index]
            assertEquals(binding.tracks[index].documentId, row.documentId)
            assertEquals("track$index.mp3", row.displayName)
            assertEquals("audio/mpeg", row.mimeType)
            assertEquals(binding.tracks[index].declaredFileBytes, row.bytes)
            assertEquals(binding.tracks[index], graph.track(row.documentId))
            assertEquals(row, graph.document(row.documentId))
            assertTrue(graph.isChild(rootId, row.documentId))
            assertEquals(index < 100, graph.isChild(overlapId, row.documentId))
        }
        assertFalse(graph.isChild(rootId, overlapId))
        assertFalse(graph.isChild(overlapId, rootId))
        assertFalse(graph.isChild(all[0].documentId, all[1].documentId))
        unsupported { (all as MutableList<*>).clear() }
        unsupported { (overlap as MutableList<*>).clear() }
    }

    @Test
    fun graphDirectoryLabelsUnknownIdsAndUriSyntaxDoNotGrantAccess() {
        val binding = plannedBinding()
        val graph = NavigationRandomCorpusDocumentsProvider.Graph(binding)
        for ((id, label) in listOf(rootId to "Generated corpus", overlapId to "Overlap (first 100)")) {
            val row = graph.document(id)
            assertEquals(label, row.displayName)
            assertEquals(Document.MIME_TYPE_DIR, row.mimeType)
            assertEquals(null, row.bytes)
            missing { graph.track(id) }
        }
        for (id in listOf("unknown", "${NavigationScaleCatalog.NAMESPACE}:track:013", "${NavigationScaleCatalog.NAMESPACE}:track:-1",
            "${NavigationScaleCatalog.NAMESPACE}:track:13313")) {
            missing { graph.document(id) }
            missing { graph.children(id) }
            missing { graph.track(id) }
        }
        for (index in listOf(0, 99, 100, 13_312)) {
            val id = binding.tracks[index].documentId
            val canonical = DocumentsContract.buildDocumentUri(authority, id)
            val tree = DocumentsContract.buildTreeDocumentUri(authority, overlapId)
            val alias = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            assertEquals(id, DocumentsContract.getDocumentId(canonical))
            assertEquals(id, DocumentsContract.getDocumentId(alias))
            assertEquals(authority, alias.authority)
        }
        expectFailure { NavigationRandomCorpusDocumentsProvider.Graph(plannedBinding("other.fixture")) }
    }

    @Test
    fun newProviderIsClosedEvenWhenAnIndependentGraphExists() {
        NavigationRandomCorpusDocumentsProvider.Graph(plannedBinding())
        val provider = NavigationRandomCorpusDocumentsProvider()
        assertTrue(provider.onCreate())
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        missing { provider.queryDocument(rootId, null) }
        missing { provider.queryChildDocuments(rootId, null, sortOrder = null) }
        missing { provider.openDocument("${NavigationScaleCatalog.NAMESPACE}:track:0", "r", null) }
        assertFalse(provider.isChildDocument(rootId, "${NavigationScaleCatalog.NAMESPACE}:track:0"))
    }

    @Test
    fun independentReadOnlyDescriptorsKeepIndependentOffsetsAndOutliveOpenReturn() = withRoot { root ->
        write(root, "a.bin", "abcdef")
        val files = NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, mapOf("a.bin" to 6L))
        files.open("a.bin", "r").use { first ->
            files.open("a.bin", "r").use { second ->
                val a = readBytes(first.fileDescriptor, 2)
                val b = readBytes(second.fileDescriptor, 2)
                assertEquals("ab", String(a, Charsets.UTF_8))
                assertEquals("ab", String(b, Charsets.UTF_8))
                assertEquals("cd", String(readBytes(first.fileDescriptor, 2), Charsets.UTF_8))
                try {
                    Os.write(first.fileDescriptor, byteArrayOf(1), 0, 1)
                    fail("Read-only descriptor accepted a write")
                } catch (_: ErrnoException) {
                    // Actual descriptor operation, not a mode-label assertion.
                }
            }
        }
        files.requireUnchanged()
    }

    @Test
    fun callerMapSnapshotCancellationAndUnsupportedOpenRequestsFailClosed() = withRoot { root ->
        write(root, "a.bin", "abc")
        val caller = mutableMapOf("a.bin" to 3L)
        val files = NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, caller)
        caller.clear()
        caller["other.bin"] = 99L
        files.open("a.bin", "r").use { assertEquals(3L, it.statSize) }
        for (mode in listOf("", "w", "rw", "rwt", "wa")) missing { files.open("a.bin", mode).close() }
        for (path in listOf("unknown", "../a.bin", "/a.bin")) missing { files.open(path, "r").close() }
        val canceled = CancellationSignal().also { it.cancel() }
        try {
            files.open("a.bin", "r", canceled).close()
            fail("Expected cancellation")
        } catch (_: OperationCanceledException) {
            // A pre-canceled request must not hand out a descriptor.
        }
        files.open("a.bin", "r").use { assertEquals(3L, it.statSize) }
    }

    @Test
    fun replacedFilesAndLengthChangesInvalidateCapturedPhysicalIdentity() = withRoot { root ->
        val file = write(root, "a.bin", "abc")
        val files = NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, mapOf("a.bin" to 3L))
        // Rename a separately existing inode over the observed path (no inode-reuse ambiguity).
        val replacement = write(root, "replacement", "xyz")
        Os.rename(replacement.absolutePath, file.absolutePath)
        expectFailure { files.requireUnchanged() }
        expectFailure { files.open("a.bin", "r").close() }
        val fresh = NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, mapOf("a.bin" to 3L))
        file.appendText("d")
        expectFailure { fresh.requireUnchanged() }
        expectFailure { fresh.open("a.bin", "r").close() }
    }

    @Test
    fun symlinkReplacementAndNoncanonicalRootsAreRejectedWithoutFollowingThem() = withRoot { root ->
        val file = write(root, "a.bin", "abc")
        val files = NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, mapOf("a.bin" to 3L))
        val target = write(root, "target.bin", "xyz")
        assertTrue(file.delete())
        Os.symlink(target.absolutePath, file.absolutePath)
        expectFailure { files.requireUnchanged() }
        expectFailure { files.open("a.bin", "r").close() }
        expectFailure { NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(File(root, "."), mapOf("target.bin" to 3L)) }
        expectFailure { NavigationRandomCorpusDocumentsProvider.ReadOnlyFiles.observe(root, mapOf("a.bin" to 3L)) }
    }

    @Test
    fun oneVerifiedOpaqueFixturePublishesOnceAfterFailureAndConcurrentPrepare() = withRoot { root ->
        val cover = write(root, "art/shared.cover", "opaque-art")
        val artworkHash = digest(cover.readBytes())
        val tracks = (0..13_312).map { index ->
            val file = write(root, "audio/track$index.mp3", "opaque-$index")
            NavigationRandomCorpusTrack(index, "${NavigationScaleCatalog.NAMESPACE}:track:$index", "audio/track$index.mp3",
                file.length(), digest(file.readBytes()), hash(index + 50_000), "art/shared.cover", artworkHash)
        }
        val jsonl = tracks.joinToString("\n") { row(it).toString() }.toByteArray(Charsets.UTF_8)
        File(root, "tracks.jsonl").writeBytes(jsonl)
        val manifest = JSONObject().put("schema_version", 1).put("count", 13_313)
            .put("synthetic", true).put("representative", false).put("real_loads_assumed_slower", true)
            .put("installed", false).put("scanned", false).put("persisted_queue", false)
            .put("tracks_manifest_sha256", digest(jsonl)).toString().toByteArray(Charsets.UTF_8)
        File(root, "manifest.json").writeBytes(manifest)
        val binding = NavigationRandomCorpusBinding(authority, digest(manifest), tracks)
        val provider = NavigationRandomCorpusDocumentsProvider()
        val late = File(root, "audio/track13312.mp3")
        late.writeText("tamper-13312") // Same length: physical observation passes; full hash verification must fail.
        expectFailure { provider.prepare(root, binding) }
        missing { provider.queryDocument(rootId, null) }
        assertFalse(provider.isChildDocument(rootId, tracks[0].documentId))
        late.writeText("opaque-13312")

        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val attempts = List(2) {
                pool.submit(Callable<String> {
                    ready.countDown()
                    start.await()
                    try {
                        provider.prepare(root, binding)
                        "published"
                    } catch (_: IllegalStateException) {
                        "already-published"
                    }
                })
            }
            ready.await()
            start.countDown()
            assertEquals(listOf("already-published", "published"), attempts.map { it.get() }.sorted())
        } finally {
            start.countDown()
            pool.shutdown()
            // Do not remove the owned root while a contender can still be using it.
            var interrupted = false
            while (!pool.isTerminated) {
                try { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        try {
            provider.prepare(root, binding)
            fail("Second prepare must not replace publication")
        } catch (_: IllegalStateException) {
            // Even an identical binding cannot be republished.
        }
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        provider.queryChildDocuments(rootId, null, "display_name DESC").use { cursor ->
            assertEquals(13_313, cursor.count)
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            for (track in tracks) {
                assertTrue(cursor.moveToNext())
                assertEquals(track.documentId, cursor.getString(idColumn))
            }
        }
        provider.queryChildDocuments(overlapId, null, sortOrder = null).use { assertEquals(100, it.count) }
        provider.queryDocument(rootId, null).use {
            assertEquals(5, it.columnCount)
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(it.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
            assertEquals(0, it.getInt(it.getColumnIndexOrThrow(Document.COLUMN_FLAGS)))
        }
        val projection = arrayOf(Document.COLUMN_SIZE, "unrecognized-column", Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        val expectedProjection = projection.toList()
        val projected = provider.queryDocument(tracks[99].documentId, projection)
        projection[0] = "caller-mutated-column"
        projected.use {
            assertEquals(expectedProjection, it.columnNames.toList())
            assertTrue(it.moveToFirst())
            assertEquals(tracks[99].declaredFileBytes, it.getLong(0))
            assertTrue(it.isNull(1))
            assertEquals("track99.mp3", it.getString(2))
            assertEquals(tracks[99].documentId, it.getString(3))
        }
        assertTrue(provider.isChildDocument(overlapId, tracks[99].documentId))
        assertFalse(provider.isChildDocument(overlapId, tracks[100].documentId))
        for (index in 0..1) provider.openDocument(tracks[index].documentId, "r", null).use {
            assertEquals(tracks[index].declaredFileBytes, it.statSize)
            assertEquals("opaque-$index", String(readBytes(it.fileDescriptor, 32), Charsets.UTF_8))
        }
        missing { provider.openDocument(rootId, "r", null).close() }
        missing { provider.openDocument(tracks[0].documentId, "rw", null).close() }
    }

    private fun plannedBinding(providerAuthority: String = authority): NavigationRandomCorpusBinding =
        NavigationRandomCorpusBinding(providerAuthority, hash(900_000), List(13_313) { index ->
            NavigationRandomCorpusTrack(index, "${NavigationScaleCatalog.NAMESPACE}:track:$index", "audio/track$index.mp3",
                100L + index, hash(index + 1), hash(index + 50_000), "art/shared.cover", hash(800_000))
        })

    private fun row(track: NavigationRandomCorpusTrack): JSONObject = JSONObject().put("index", track.index)
        .put("document_id", track.documentId).put("relative_path", track.relativeMp3Path).put("file_bytes", track.declaredFileBytes)
        .put("file_sha256", track.fileSha256).put("audio_payload_sha256", track.audioSha256)
        .put("artwork_path", track.coverRelativePath).put("artwork_sha256", track.artworkSha256)

    private fun write(root: File, relative: String, value: String): File = File(root, relative).also {
        val parent = requireNotNull(it.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        it.writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    private fun withRoot(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val allocated = File.createTempFile("navigation-provider-test-", ".owned", cache)
        check(allocated.delete() && allocated.mkdir())
        val root = allocated.canonicalFile
        try { block(root) } finally { removeOwned(root) }
    }

    private fun removeOwned(file: File) {
        if (OsConstants.S_ISDIR(Os.lstat(file.absolutePath).st_mode)) {
            for (child in requireNotNull(file.listFiles())) removeOwned(child)
        }
        check(file.delete()) { "Cannot remove owned provider fixture: $file" }
    }

    private fun missing(block: () -> Unit) {
        try { block(); fail("Expected FileNotFoundException") } catch (_: FileNotFoundException) { }
    }

    private fun readBytes(fd: FileDescriptor, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val length = Os.read(fd, bytes, count, limit - count)
            assertTrue(length >= 0)
            if (length == 0) break
            count += length
        }
        return bytes.copyOf(count)
    }

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected argument or filesystem failure") } catch (_: Exception) { }
    }

    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected UnsupportedOperationException") } catch (_: UnsupportedOperationException) { }
    }

    private fun hash(value: Int): String = value.toString(16).padStart(64, '0')
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
