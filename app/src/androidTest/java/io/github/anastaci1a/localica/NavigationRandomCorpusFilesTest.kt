package io.github.anastaci1a.localica

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh, owned test directories and opaque bytes only; never a generated or installed corpus. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusFilesTest {
    private val authority = "io.github.localica.files.fixture"

    @Test
    fun exactInventoryIsSortedHasObservedLengthsAndReturnsAnImmutableList() = withRoot { root ->
        val z = write(root, "nested/z.bin", "opaque-z")
        val a = write(root, "a.bin", "opaque-a")
        val observed = NavigationRandomCorpusFiles.observeFiles(root, linkedMapOf(
            "nested/z.bin" to expected(z, declared = false), "a.bin" to expected(a)
        ))
        assertEquals(listOf("a.bin", "nested/z.bin"), observed.map { it.relativePath })
        assertEquals(listOf(a.length(), z.length()), observed.map { it.bytes })
        assertEquals(listOf(digest(a.readBytes()), digest(z.readBytes())), observed.map { it.sha256 })
        unsupported { (observed as MutableList<NavigationRandomCorpusFiles.Observation>).clear() }
        assertEquals(2, observed.size)
    }

    @Test
    fun missingExtraFilesAndExtraEmptyDirectoriesFailExactInventory() = withRoot { root ->
        val file = write(root, "required.bin", "required")
        val expected = mapOf("required.bin" to expected(file))
        assertTrue(file.delete())
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, expected) }
        write(root, "required.bin", "required")
        val extra = write(root, "extra.bin", "extra")
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, expected) }
        assertTrue(extra.delete())
        val empty = File(root, "unused/empty")
        assertTrue(empty.mkdirs())
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, expected) }
    }

    @Test
    fun unsafeRelativeNamesAndFileAncestorDeclarationsFail() = withRoot { root ->
        val value = NavigationRandomCorpusFiles.ExpectedFile(digest("x".toByteArray()), 1L)
        for (path in listOf("", "/absolute", "../outside", "a/../b", "./a", "a//b", "a/", "a\\b",
            "a%2Fb", "a:b", ".hidden", "a b", "a\nb", "é")) {
            expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf(path to value)) }
        }
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("a" to value, "a/b" to value)) }
        // A textual prefix is not an ancestor; only slash boundaries count.
        val a = write(root, "a", "x")
        val ab = write(root, "ab", "y")
        assertEquals(2, NavigationRandomCorpusFiles.observeFiles(root, mapOf("a" to expected(a), "ab" to expected(ab))).size)
    }

    @Test
    fun noncanonicalRootAndSymlinkRootOrAncestorAreRejected() = withRoot { root ->
        val real = File(root, "real").also { assertTrue(it.mkdir()) }
        val file = write(real, "value.bin", "value")
        val declarations = mapOf("value.bin" to expected(file))
        expectFailure { NavigationRandomCorpusFiles.observeFiles(File(real, "."), declarations) }
        val alias = File(root, "alias")
        Os.symlink(real.absolutePath, alias.absolutePath)
        expectFailure { NavigationRandomCorpusFiles.observeFiles(alias, declarations) }
        val child = File(real, "child").also { assertTrue(it.mkdir()) }
        val nested = write(child, "nested.bin", "nested")
        expectFailure { NavigationRandomCorpusFiles.observeFiles(File(alias, "child"), mapOf("nested.bin" to expected(nested))) }
    }

    @Test
    fun symlinkFilesAndDirectoryEntriesAreNeverFollowed() = withRoot { root ->
        val target = write(root, "target.bin", "target")
        val alias = File(root, "alias.bin")
        Os.symlink(target.absolutePath, alias.absolutePath)
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("target.bin" to expected(target), "alias.bin" to expected(target))) }
        assertTrue(alias.delete())
        val directory = File(root, "real").also { assertTrue(it.mkdir()) }
        val nested = write(directory, "nested.bin", "nested")
        Os.symlink(directory.absolutePath, File(root, "alias-dir").absolutePath)
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("target.bin" to expected(target),
            "real/nested.bin" to expected(nested), "alias-dir/nested.bin" to expected(nested))) }
    }

    @Test
    fun hardlinksAndSpecialNodesAreRejectedBeforeReading() = withRoot { root ->
        val file = write(root, "first.bin", "opaque")
        val linked = File(root, "second.bin")
        Os.link(file.absolutePath, linked.absolutePath)
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("first.bin" to expected(file), "second.bin" to expected(file))) }
        assertTrue(linked.delete())
        val fifo = File(root, "pipe")
        Os.mkfifo(fifo.absolutePath, 384) // 0600, owned temporary FIFO; must not be opened for hashing.
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("first.bin" to expected(file),
            "pipe" to NavigationRandomCorpusFiles.ExpectedFile(digest(byteArrayOf())))) }
    }

    @Test
    fun truncatedAppendedAndSameLengthChangedBytesFailTheirDeclarations() = withRoot { root ->
        val original = "abcdef".toByteArray()
        val file = write(root, "value.bin", "abcdef")
        val expected = mapOf("value.bin" to expected(file))
        for (bytes in listOf(original.copyOf(5), original + byteArrayOf(1), "abcdeg".toByteArray())) {
            file.writeBytes(bytes)
            expectFailure { NavigationRandomCorpusFiles.observeFiles(root, expected) }
        }
        file.writeBytes(original)
        assertEquals(1, NavigationRandomCorpusFiles.observeFiles(root, expected).size)
    }

    @Test
    fun sameCoverBytesAtSeparatePathsAreBothReadAndCounted() = withRoot { root ->
        val first = write(root, "covers/first", "same-cover")
        val second = write(root, "covers/second", "same-cover")
        val declaration = expected(first, declared = false)
        val observed = NavigationRandomCorpusFiles.observeFiles(root, mapOf("covers/first" to declaration, "covers/second" to declaration))
        assertEquals(listOf("covers/first", "covers/second"), observed.map { it.relativePath })
        assertEquals(first.length() + second.length(), observed.sumOf { it.bytes })
        assertEquals(observed[0].sha256, observed[1].sha256)
        second.writeText("same-covex") // Same length; equal declared hashes must not skip the second path.
        expectFailure { NavigationRandomCorpusFiles.observeFiles(root, mapOf("covers/first" to declaration, "covers/second" to declaration)) }
    }

    @Test
    fun finalSortedFileFailureReturnsNoPartialObservationAndAllowsRepair() = withRoot { root ->
        val first = write(root, "a.bin", "first")
        val last = write(root, "z.bin", "last")
        val expected = mapOf("a.bin" to expected(first), "z.bin" to expected(last))
        last.writeText("oops")
        var returned: List<NavigationRandomCorpusFiles.Observation>? = null
        expectFailure { returned = NavigationRandomCorpusFiles.observeFiles(root, expected) }
        assertEquals(null, returned)
        // Repair/re-read demonstrates usable paths, not a proof that no descriptors leaked.
        assertTrue(last.delete())
        write(root, "z.bin", "last")
        assertEquals(2, NavigationRandomCorpusFiles.observeFiles(root, expected).size)
    }

    @Test
    fun fullOpaqueOwnedFixtureBindsMetadataDescriptorsInventoryAndLogicalBytesOnly() = withRoot { root ->
        // The only test creating 13,313 files. None are valid media or placed in a corpus/staging tree.
        val cover = write(root, "art/shared.cover", "opaque-shared-art")
        val coverHash = digest(cover.readBytes())
        val tracks = (0..13_312).map { index ->
            val file = write(root, "audio/track$index.mp3", "opaque-$index")
            NavigationRandomCorpusTrack(index, "${NavigationScaleCatalog.NAMESPACE}:track:$index",
                "audio/track$index.mp3", file.length(), digest(file.readBytes()), hash(index + 50_000),
                "art/shared.cover", coverHash)
        }
        val jsonl = tracks.joinToString("\n") { row(it).toString() }.toByteArray(Charsets.UTF_8)
        File(root, "tracks.jsonl").writeBytes(jsonl)
        val manifest = manifest(jsonl)
        File(root, "manifest.json").writeBytes(manifest)
        val binding = NavigationRandomCorpusBinding(authority, digest(manifest), tracks)
        val result = NavigationRandomCorpusFiles.verify(root, binding)
        assertEquals(root.absolutePath, result.rootPath)
        assertEquals(authority, result.authority)
        assertEquals(digest(manifest), result.declaredManifestSha256)
        assertEquals("filesystem-bytes-only", result.evidenceScope)
        assertEquals(13_316, result.observations.size)
        assertEquals(result.observations.map { it.relativePath }.sorted(), result.observations.map { it.relativePath })
        assertEquals(tracks.sumOf { it.declaredFileBytes } + cover.length() + manifest.size + jsonl.size, result.logicalBytes)
        assertEquals(1, result.observations.count { it.relativePath == "art/shared.cover" })
        unsupported { (result.observations as MutableList<NavigationRandomCorpusFiles.Observation>).clear() }

        // Same hash-bound metadata, different complete descriptor list: not just count/identity matching.
        val differentDescriptors = tracks.toMutableList().also { it[0] = it[0].copy(audioSha256 = hash(900_000)) }
        expectFailure { NavigationRandomCorpusFiles.verify(root, NavigationRandomCorpusBinding(authority, digest(manifest), differentDescriptors)) }
        // Reserved metadata names, including directory-prefix collisions, cannot be artwork paths.
        for (path in listOf("manifest.json", "tracks.jsonl", "manifest.json/cover", "tracks.jsonl/cover")) {
            val collision = tracks.map { it.copy(coverRelativePath = path) }
            expectFailure { NavigationRandomCorpusFiles.verify(root, NavigationRandomCorpusBinding(authority, digest(manifest), collision)) }
        }
        File(root, "manifest.json").appendText(" ")
        expectFailure { NavigationRandomCorpusFiles.verify(root, binding) }
        File(root, "manifest.json").writeBytes(manifest)
        File(root, "tracks.jsonl").appendText(" ")
        expectFailure { NavigationRandomCorpusFiles.verify(root, binding) }
        File(root, "tracks.jsonl").writeBytes(jsonl)
        RandomAccessFile(File(root, "manifest.json"), "rw").use { it.setLength(NavigationRandomCorpusJson.MAX_MANIFEST_BYTES.toLong() + 1) }
        expectFailure { NavigationRandomCorpusFiles.verify(root, binding) }
        File(root, "manifest.json").writeBytes(manifest)
        RandomAccessFile(File(root, "tracks.jsonl"), "rw").use { it.setLength(NavigationRandomCorpusJson.MAX_TRACKS_BYTES.toLong() + 1) }
        expectFailure { NavigationRandomCorpusFiles.verify(root, binding) }
        File(root, "tracks.jsonl").writeBytes(jsonl)
        File(root, "audio/track13312.mp3").writeText("tampered-final-track")
        expectFailure { NavigationRandomCorpusFiles.verify(root, binding) }
    }

    private fun row(track: NavigationRandomCorpusTrack): JSONObject = JSONObject()
        .put("index", track.index).put("document_id", track.documentId).put("relative_path", track.relativeMp3Path)
        .put("file_bytes", track.declaredFileBytes).put("file_sha256", track.fileSha256)
        .put("audio_payload_sha256", track.audioSha256).put("artwork_path", track.coverRelativePath)
        .put("artwork_sha256", track.artworkSha256)

    private fun manifest(jsonl: ByteArray): ByteArray = JSONObject().put("schema_version", 1).put("count", 13_313)
        .put("synthetic", true).put("representative", false).put("real_loads_assumed_slower", true)
        .put("installed", false).put("scanned", false).put("persisted_queue", false)
        .put("tracks_manifest_sha256", digest(jsonl)).toString().toByteArray(Charsets.UTF_8)

    private fun expected(file: File, declared: Boolean = true) =
        NavigationRandomCorpusFiles.ExpectedFile(digest(file.readBytes()), if (declared) file.length() else null)

    private fun write(root: File, relative: String, text: String): File = File(root, relative).also {
        val parent = requireNotNull(it.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        it.writeBytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun withRoot(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val allocated = File.createTempFile("navigation-files-test-", ".owned", cache)
        check(allocated.delete() && allocated.mkdir())
        val root = allocated.canonicalFile
        try {
            block(root)
        } finally {
            removeOwned(root)
        }
    }

    /** lstat cleanup never descends through symlinks, including the deliberately hostile fixtures. */
    private fun removeOwned(file: File) {
        val stat = Os.lstat(file.absolutePath)
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            for (child in requireNotNull(file.listFiles())) removeOwned(child)
        }
        check(file.delete()) { "Could not remove owned fixture: $file" }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected filesystem or argument failure")
        } catch (_: Exception) {
            // ErrnoException/IOException are allowed; assertion Errors must remain visible.
        }
    }

    private fun unsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Returned inventory cannot be changed by its caller.
        }
    }

    private fun hash(value: Int): String = value.toString(16).padStart(64, '0')
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
