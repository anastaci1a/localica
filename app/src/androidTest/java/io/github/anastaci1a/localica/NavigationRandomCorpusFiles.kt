package io.github.anastaci1a.localica

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections

/**
 * Observes an already-owned, immutable staging root, NOT the frozen generation master. The
 * staging layout contains only manifest.json, tracks.jsonl, descriptor MP3s and distinct covers;
 * ancillary generation/audit files are deliberately rejected, never deleted or ignored.
 *
 * Ownership and immutability of the root/ancestor binding must be established and maintained by
 * the caller. Canonical paths, no-follow opens and before/after observations are not a concurrency
 * seal, installation receipt or guarantee against later mutation. Nothing is activated or written.
 * Whole-file hashes do not validate decoded media, audio-payload hashes, tags or embedded artwork.
 * Logical lengths do not measure allocated storage or peak concurrent generated/staged copies.
 */
internal object NavigationRandomCorpusFiles {
    private const val MANIFEST = "manifest.json"
    private const val TRACKS = "tracks.jsonl"
    private const val BUFFER_BYTES = 64 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    internal data class ExpectedFile(val sha256: String, val declaredBytes: Long? = null)
    internal data class Observation(val relativePath: String, val bytes: Long, val sha256: String)

    internal class Result internal constructor(
        val rootPath: String,
        val authority: String,
        val declaredManifestSha256: String,
        observations: List<Observation>,
    ) {
        val evidenceScope: String = "filesystem-bytes-only"
        val observations: List<Observation> = immutable(observations)
        val logicalBytes: Long = observations.fold(0L) { total, item -> checkedAdd(total, item.bytes) }
    }

    /** No partial result, provider capability, decoded-media or operational admission is returned. */
    fun verify(root: File, binding: NavigationRandomCorpusBinding): Result {
        val expected = linkedMapOf<String, ExpectedFile>()
        for (track in binding.tracks) {
            expected[track.relativeMp3Path] = ExpectedFile(track.fileSha256, track.declaredFileBytes)
            expected.putIfAbsent(track.coverRelativePath, ExpectedFile(track.artworkSha256))
        }
        require(expected.keys.none { reservedMetadataPath(it) }) { "Media collides with reserved metadata paths" }
        val paths = expected.keys + setOf(MANIFEST, TRACKS)
        val initial = snapshot(root, paths)
        val manifest = read(root, MANIFEST, initial.entries.getValue(MANIFEST), NavigationRandomCorpusJson.MAX_MANIFEST_BYTES)
        val tracks = read(root, TRACKS, initial.entries.getValue(TRACKS), NavigationRandomCorpusJson.MAX_TRACKS_BYTES)
        val parsed = NavigationRandomCorpusJson.parse(
            manifest.captured!!, tracks.captured!!, binding.declaredManifestSha256, binding.authority,
        )
        require(parsed.tracks == binding.tracks) { "On-disk metadata disagrees with supplied descriptors" }
        expected[MANIFEST] = ExpectedFile(manifest.observation.sha256, manifest.observation.bytes)
        expected[TRACKS] = ExpectedFile(tracks.observation.sha256, tracks.observation.bytes)
        // Re-read metadata too: the exact bytes parsed above must survive the full observation pass.
        val observations = observe(root, expected, initial)
        return Result(root.path, binding.authority, binding.declaredManifestSha256, observations)
    }

    /** Low-level byte/inventory helper, also exercised with tiny opaque fixtures; NOT corpus admission. */
    internal fun observeFiles(root: File, expectedFiles: Map<String, ExpectedFile>): List<Observation> {
        val expected = expectedFiles.toMap()
        validateExpectations(expected)
        return observe(root, expected, snapshot(root, expected.keys))
    }

    private fun observe(root: File, expected: Map<String, ExpectedFile>, initial: Snapshot): List<Observation> {
        validateExpectations(expected)
        val observations = ArrayList<Observation>(expected.size)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        for ((path, declaration) in expected.toSortedMap()) {
            val stamp = initial.entries.getValue(path)
            require(declaration.declaredBytes == null || stamp.size == declaration.declaredBytes) { "Declared length mismatch: $path" }
            val observed = read(root, path, stamp, buffer = buffer).observation
            require(observed.sha256 == declaration.sha256) { "File SHA256 mismatch: $path" }
            total = checkedAdd(total, observed.bytes)
            observations += observed
        }
        require(snapshot(root, expected.keys) == initial) { "Filesystem changed during verification" }
        return immutable(observations)
    }

    private fun validateExpectations(expected: Map<String, ExpectedFile>) {
        validatePaths(expected.keys)
        expected.forEach { (path, value) ->
            require(SHA256.matches(value.sha256)) { "Invalid expected SHA256: $path" }
            require(value.declaredBytes == null || value.declaredBytes >= 0L) { "Negative expected length: $path" }
        }
    }

    private fun reservedMetadataPath(path: String): Boolean =
        listOf(MANIFEST, TRACKS).any { path == it || path.startsWith("$it/") || it.startsWith("$path/") }

    private fun validatePaths(paths: Set<String>): Set<String> {
        val directories = hashSetOf("")
        for (path in paths) {
            require(path.isNotEmpty() && path.split('/').all { COMPONENT.matches(it) }) { "Unsafe relative file path: $path" }
            var slash = path.indexOf('/')
            while (slash >= 0) {
                val prefix = path.substring(0, slash)
                require(prefix !in paths) { "File is also a directory: $prefix" }
                directories += prefix
                slash = path.indexOf('/', slash + 1)
            }
        }
        return directories
    }

    private data class Stamp(
        val device: Long, val inode: Long, val mode: Int, val links: Long, val size: Long,
        val modifiedSeconds: Long, val changedSeconds: Long,
    )

    private fun stamp(value: StructStat) = Stamp(
        value.st_dev, value.st_ino, value.st_mode, value.st_nlink, value.st_size,
        value.st_mtime, value.st_ctime,
    )

    private data class DirectoryIdentity(val device: Long, val inode: Long, val mode: Int)
    private data class Snapshot(val ancestors: Map<String, DirectoryIdentity>, val entries: Map<String, Stamp>)

    private fun snapshot(root: File, files: Set<String>): Snapshot {
        val directories = validatePaths(files)
        require(root.isAbsolute && root.path == root.canonicalPath) { "Require an absolute canonical root without symlink ancestors" }
        // Unrelated siblings may change ancestor timestamps/sizes without changing our path binding.
        val ancestors = linkedMapOf<String, DirectoryIdentity>()
        var ancestor: File? = root
        while (ancestor != null) {
            val state = stamp(Os.lstat(ancestor.path))
            require(OsConstants.S_ISDIR(state.mode)) { "Root/ancestor is not a no-follow directory: $ancestor" }
            ancestors[ancestor.path] = DirectoryIdentity(state.device, state.inode, state.mode)
            ancestor = ancestor.parentFile
        }
        val entries = linkedMapOf("" to stamp(Os.lstat(root.path)))
        val seenFiles = HashSet<String>()
        val physicalFiles = HashSet<Pair<Long, Long>>()
        val pending = ArrayDeque<File>()
        pending.add(root)
        val rootPath = root.toPath()
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = directory.listFiles() ?: throw IOException("Cannot enumerate owned directory: $directory")
            for (child in children) {
                val childPath = child.toPath().normalize()
                require(childPath.startsWith(rootPath) && childPath != rootPath) { "Path escapes root" }
                val relative = rootPath.relativize(childPath).joinToString("/")
                val state = stamp(Os.lstat(child.path))
                when {
                    OsConstants.S_ISDIR(state.mode) -> {
                        require(relative in directories) { "Unexpected directory: $relative" }
                        pending.add(child)
                    }
                    OsConstants.S_ISREG(state.mode) -> {
                        require(relative in files) { "Unexpected regular file: $relative" }
                        require(state.links == 1L && physicalFiles.add(state.device to state.inode)) { "Hardlinked/aliased regular file: $relative" }
                        require(state.size >= 0L) { "Negative file length: $relative" }
                        seenFiles += relative
                    }
                    else -> throw IllegalArgumentException("Symlink or special filesystem node: $relative")
                }
                require(entries.put(relative, state) == null) { "Duplicate inventory path: $relative" }
            }
        }
        require(seenFiles == files) { "Missing expected regular files" }
        return Snapshot(ancestors, entries)
    }

    private data class Read(val observation: Observation, val captured: ByteArray?)

    private fun read(root: File, relative: String, initial: Stamp, captureLimit: Int? = null,
        buffer: ByteArray = ByteArray(BUFFER_BYTES)): Read {
        require(OsConstants.S_ISREG(initial.mode) && initial.links == 1L) { "Require an unaliased regular file" }
        if (captureLimit != null) require(initial.size <= captureLimit.toLong()) { "Metadata byte limit: $relative" }
        val file = File(root, relative)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        var failure: Throwable? = null
        try {
            require(stamp(Os.fstat(fd)) == initial) { "Opened file identity changed: $relative" }
            val digest = MessageDigest.getInstance("SHA-256")
            val captured = if (captureLimit != null) ByteArrayOutputStream(minOf(initial.size, BUFFER_BYTES.toLong()).toInt()) else null
            var count = 0L
            while (true) {
                val length = Os.read(fd, buffer, 0, buffer.size)
                if (length == 0) break
                require(length > 0) { "File read made no progress: $relative" }
                count = checkedAdd(count, length.toLong())
                require(count <= initial.size) { "File grew during read: $relative" }
                digest.update(buffer, 0, length)
                captured?.write(buffer, 0, length)
            }
            require(count == initial.size) { "Incomplete file read: $relative" }
            require(stamp(Os.fstat(fd)) == initial && stamp(Os.lstat(file.path)) == initial) { "File changed during read: $relative" }
            val hash = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            return Read(Observation(relative, count, hash), captured?.toByteArray())
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            // FileInputStream(FileDescriptor) does not own/close an externally opened Android FD.
            try {
                Os.close(fd)
            } catch (closeFailure: Throwable) {
                if (failure == null) throw closeFailure
                failure.addSuppressed(closeFailure)
            }
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0L && right >= 0L && right <= Long.MAX_VALUE - left) { "Observed byte total overflows Long" }
        return left + right
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
