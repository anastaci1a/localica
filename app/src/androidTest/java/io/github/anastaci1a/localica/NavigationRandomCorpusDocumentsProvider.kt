package io.github.anastaci1a.localica

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections

/**
 * Test-only provider, closed on creation. The separate protected bridge owns fixed-root requests;
 * there is no provider call/Intent activation IPC. The caller must maintain an immutable root and its ancestor
 * binding; preparation does not establish that prerequisite, a peak budget or target-UID access.
 * A future serving test-APK UID cannot infer access from today's targetContext fixture directory.
 *
 * Preparation verifies all metadata/files before one publication and warms caches. Whole-file
 * agreement does not validate decoded media, payload hashes or APIC. Opens neither copy nor hash
 * bytes. Physical observations are not a race-proof seal; same-second/in-place mutations and later
 * changes still require the external immutability contract. Restart creates a closed instance, not
 * permission to adopt/rebind an existing installation. The repeated-tone QueueScale control stays
 * separate, and none of these direct-instance contracts proves framework permission enforcement.
 */
class NavigationRandomCorpusDocumentsProvider : DocumentsProvider() {
    private data class Published(val graph: Graph, val files: ReadOnlyFiles, val rootPath: String, val manifestSha256: String)
    @Volatile private var published: Published? = null

    /** Same-process source contract only. No supplied Result/graph can bypass full verification. */
    @Synchronized
    internal fun prepare(root: File, binding: NavigationRandomCorpusBinding) {
        check(published == null) { "A provider instance cannot replace its published corpus" }
        val graph = Graph(binding)
        val files = ReadOnlyFiles.observe(root, binding.tracks.associate { it.relativeMp3Path to it.declaredFileBytes })
        NavigationRandomCorpusFiles.verify(root, binding)
        files.requireUnchanged()
        published = Published(graph, files, root.path, binding.declaredManifestSha256)
    }

    /** Narrow local status only, never a host-ownership or target-permission receipt. */
    internal fun verifyReadyPhysicalBinding(root: File, manifestSha256: String) {
        val state = ready()
        check(state.rootPath == root.path && state.manifestSha256 == manifestSha256) { "Published corpus pin differs" }
        state.files.requireUnchanged()
    }

    override fun onCreate(): Boolean = true

    /** Deliberately hidden from ordinary picker fixtures, even after direct preparation. */
    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(columns(projection, ROOT_COLUMNS))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(listOf(ready().graph.document(documentId)), projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        // sortOrder is a hint; this prototype always uses numeric descriptor order and claims no honored sort arguments.
        cursor(ready().graph.children(parentDocumentId), projection)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        published?.graph?.isChild(parentDocumentId, documentId) ?: false

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val state = ready()
        signal?.throwIfCanceled()
        val track = state.graph.track(documentId)
        try {
            return state.files.open(track.relativeMp3Path, mode, signal)
        } catch (failure: ErrnoException) {
            throw missing(documentId, failure)
        } catch (failure: IllegalArgumentException) {
            throw missing(documentId, failure)
        } catch (failure: IOException) {
            throw missing(documentId, failure)
        }
    }

    private fun ready(): Published = published ?: throw FileNotFoundException("Provider is not prepared")

    private fun cursor(rows: List<Row>, projection: Array<out String>?): Cursor {
        val columns = columns(projection, DOCUMENT_COLUMNS)
        val result = MatrixCursor(columns, rows.size)
        for (row in rows) result.addRow(columns.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.documentId
                Document.COLUMN_DISPLAY_NAME -> row.displayName
                Document.COLUMN_MIME_TYPE -> row.mimeType
                Document.COLUMN_FLAGS -> 0
                Document.COLUMN_SIZE -> row.bytes
                else -> null
            }
        }.toTypedArray())
        return result
    }

    internal data class Row(val documentId: String, val displayName: String, val mimeType: String, val bytes: Long?)

    /** Pure descriptor graph; constructing it neither observes files nor publishes provider state. */
    internal class Graph(binding: NavigationRandomCorpusBinding) {
        init { require(binding.authority == AUTHORITY) { "Unexpected random-corpus provider authority" } }
        private val tracks = binding.tracks.associateBy { it.documentId }
        private val rows = immutable(binding.tracks.map {
            Row(it.documentId, it.relativeMp3Path.substringAfterLast('/'), "audio/mpeg", it.declaredFileBytes)
        })
        private val byId = rows.associateBy { it.documentId }
        private val overlap = immutable(rows.take(NavigationScaleCatalog.QUEUE_ENTRY_COUNT))

        fun track(id: String): NavigationRandomCorpusTrack = tracks[id] ?: throw FileNotFoundException(id)

        fun document(id: String): Row = when (id) {
            ROOT -> Row(ROOT, "Generated corpus", Document.MIME_TYPE_DIR, null)
            OVERLAP -> Row(OVERLAP, "Overlap (first 100)", Document.MIME_TYPE_DIR, null)
            else -> byId[id] ?: throw FileNotFoundException(id)
        }

        fun children(parent: String): List<Row> = when (parent) {
            ROOT -> rows
            OVERLAP -> overlap
            else -> throw FileNotFoundException(parent)
        }

        fun isChild(parent: String, id: String): Boolean {
            val track = tracks[id] ?: return false
            return parent == ROOT || (parent == OVERLAP && track.index < NavigationScaleCatalog.QUEUE_ENTRY_COUNT)
        }
    }

    /**
     * Tiny-fixture handle/physical-identity helper, NOT hash verification or corpus admission.
     * It cannot publish a provider. Only prepare combines it with the complete frozen verifier.
     */
    internal class ReadOnlyFiles private constructor(
        private val root: File,
        private val lengths: Map<String, Long>,
        private val captured: PhysicalSnapshot,
    ) {
        private data class FileState(
            val device: Long, val inode: Long, val mode: Int, val links: Long, val bytes: Long,
            val modifiedSeconds: Long, val changedSeconds: Long,
        )
        private data class DirectoryState(val device: Long, val inode: Long, val mode: Int)
        private data class PhysicalSnapshot(val directories: Map<String, DirectoryState>, val files: Map<String, FileState>)

        fun requireUnchanged() {
            require(capture(root, lengths) == captured) { "Prepared physical file binding changed" }
        }

        fun open(relativePath: String, mode: String, signal: CancellationSignal? = null): ParcelFileDescriptor {
            signal?.throwIfCanceled()
            if (mode != "r") throw FileNotFoundException("Read-only provider mode required")
            val expected = captured.files[relativePath] ?: throw FileNotFoundException(relativePath)
            val file = File(root, relativePath)
            checkParents(file)
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            var duplicate: ParcelFileDescriptor? = null
            var primary: Throwable? = null
            try {
                require(state(Os.fstat(fd)) == expected && state(Os.lstat(file.path)) == expected) { "Opened file differs from prepared identity" }
                checkParents(file)
                signal?.throwIfCanceled()
                // Each request opens afresh. Only this temporary handle and its returned dup share an offset.
                duplicate = ParcelFileDescriptor.dup(fd)
                signal?.throwIfCanceled()
                require(state(Os.fstat(fd)) == expected && state(Os.lstat(file.path)) == expected) { "File changed while opening" }
                checkParents(file)
                signal?.throwIfCanceled()
            } catch (failure: Throwable) {
                primary = failure
                try { duplicate?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            } finally {
                try {
                    Os.close(fd)
                } catch (closing: Throwable) {
                    if (primary != null) {
                        primary.addSuppressed(closing)
                    } else {
                        try { duplicate?.close() } catch (secondary: Throwable) { closing.addSuppressed(secondary) }
                        throw closing
                    }
                }
            }
            return requireNotNull(duplicate)
        }

        private fun checkParents(file: File) {
            require(root.isAbsolute && root.path == root.canonicalPath) { "Root path binding changed" }
            var parent = file.parentFile
            while (parent != null) {
                require(directory(Os.lstat(parent.path)) == captured.directories[parent.path]) { "Prepared ancestor changed" }
                parent = parent.parentFile
            }
        }

        companion object {
            private val COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

            fun observe(root: File, expectedLengths: Map<String, Long>): ReadOnlyFiles {
                val lengths = expectedLengths.toMap()
                return ReadOnlyFiles(root, lengths, capture(root, lengths))
            }

            private fun capture(root: File, lengths: Map<String, Long>): PhysicalSnapshot {
                require(root.isAbsolute && root.path == root.canonicalPath) { "Require an absolute canonical owned root" }
                val directories = linkedMapOf<String, DirectoryState>()
                val files = linkedMapOf<String, FileState>()
                val identities = HashSet<Pair<Long, Long>>()
                fun parents(start: File) {
                    var parent: File? = start
                    while (parent != null && parent.path !in directories) {
                        directories[parent.path] = directory(Os.lstat(parent.path))
                        parent = parent.parentFile
                    }
                }
                parents(root)
                for ((relative, bytes) in lengths.toSortedMap()) {
                    require(relative.isNotEmpty() && relative.split('/').all { COMPONENT.matches(it) }) { "Unsafe relative file path" }
                    require(bytes >= 0L) { "Negative declared length" }
                    val file = File(root, relative)
                    require(file.toPath().normalize().startsWith(root.toPath())) { "File outside owned root" }
                    parents(requireNotNull(file.parentFile))
                    val value = state(Os.lstat(file.path))
                    require(OsConstants.S_ISREG(value.mode) && value.links == 1L && value.bytes == bytes) { "Not the expected unaliased regular file" }
                    require(identities.add(value.device to value.inode)) { "Aliased physical file paths" }
                    files[relative] = value
                }
                return PhysicalSnapshot(directories, files)
            }

            private fun state(stat: StructStat) = FileState(
                stat.st_dev, stat.st_ino, stat.st_mode, stat.st_nlink, stat.st_size, stat.st_mtime, stat.st_ctime,
            )

            private fun directory(stat: StructStat): DirectoryState {
                require(OsConstants.S_ISDIR(stat.st_mode)) { "Symlink or non-directory ancestor" }
                return DirectoryState(stat.st_dev, stat.st_ino, stat.st_mode)
            }
        }
    }

    companion object {
        const val AUTHORITY = "io.github.anastaci1a.localica.test.navigation.random"
        const val ROOT = NavigationScaleCatalog.ROOT_DOCUMENT_ID
        const val OVERLAP = NavigationScaleCatalog.OVERLAP_DOCUMENT_ID
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        private fun columns(projection: Array<out String>?, defaults: Array<String>): Array<String> =
            projection?.map { it }?.toTypedArray() ?: defaults.copyOf()
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun missing(id: String, cause: Exception) = FileNotFoundException("Cannot open document: $id").apply { initCause(cause) }
    }
}
