package io.github.anastaci1a.localica.editing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.documentIdentity
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.RecoveryAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest

interface EditableDocuments {
    fun writable(track: Track): String?
    fun open(uri: String): InputStream
    fun replace(uri: String, source: File)
}

class AndroidEditableDocuments(context: Context) : EditableDocuments {
    private val resolver = context.contentResolver
    override fun writable(track: Track): String? = track.locations.firstOrNull { location ->
        resolver.persistedUriPermissions.any { it.uri.toString() == location.folderUri && it.isWritePermission } &&
            runCatching { resolver.query(Uri.parse(location.uri), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
            } == true }.getOrDefault(false)
    }?.uri
    override fun open(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri)) ?: error("File is unavailable.")
    override fun replace(uri: String, source: File) {
        // Require a seekable descriptor; a streaming provider cannot safely truncate/verify an overwrite.
        resolver.openFileDescriptor(Uri.parse(uri), "rw")?.use { descriptor ->
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            FileInputStream(source).use { input ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    Os.ftruncate(descriptor.fileDescriptor, 0)
                    input.copyTo(output)
                    output.flush(); output.fd.sync()
                }
            }
        } ?: error("Provider cannot open this file for writing.")
    }
}

/** The existing single journal owns restart recovery; native admission also tracks verified bytes. */
class RecoverableTagWriter(private val directory: File, private val documents: EditableDocuments,
    private val readers: NativeReaders = NativeReaders()) {
    private val gate = Mutex()
    private val journal get() = AtomicFile(File(directory, "pending.json"))
    private var unsafe: Recovery? = null
    private var verified: Recovery? = null
    data class Recovery(val track: Track, val uri: String, val original: File, val hash: String)

    /** Startup and every later probe share the same mutex as outcome publication. */
    suspend fun initialize() = withContext(Dispatchers.IO) { gate.withLock { inspect() }; Unit }
    suspend fun pending(): Recovery? = withContext(Dispatchers.IO) { gate.withLock { inspect() } }

    private fun inspect(): Recovery? = try {
        val pending = readPending()
        check(pending != null || unsafe == null) { "The tag recovery record disappeared before byte verification." }
        check(unsafe == null || (pending != null && sameRecord(pending, unsafe))) {
            "The retained recovery record changed before byte verification."
        }
        when {
            pending == null || sameRecord(pending, verified) -> readers.publishRecovery(RecoveryAdmission.Clear)
            else -> {
                unsafe = pending
                readers.publishRecovery(RecoveryAdmission.Pending(documentIdentity(pending.uri)))
            }
        }
        pending
    } catch (failure: Exception) {
        val message = "Tag recovery could not be read. Retry Tag editing jobs and recovery in Settings; keep the retained original. ${failure.message.orEmpty()}"
        val remembered = unsafe
        if (remembered != null && !journal.baseFile.exists() &&
            !File(directory, "pending.json.bak").exists() && !File(directory, "pending.json.new").exists()) {
            // The running writer still knows the only affected source. Do not disable unrelated
            // playback just because its retained record is unexpectedly unavailable.
            readers.publishRecovery(RecoveryAdmission.Pending(documentIdentity(remembered.uri), message))
        } else readers.publishRecovery(RecoveryAdmission.Unknown(message))
        throw failure
    }

    private fun readPending(): Recovery? {
        // A .new artifact is ambiguous on newer Android. Do not claim checked storage-commit or
        // power-loss durability from AtomicFile.finishWrite returning normally.
        check(!File(directory, "pending.json.new").exists()) { "An unfinished recovery journal needs verification." }
        if (!journal.baseFile.exists() && !File(directory, "pending.json.bak").exists()) return null
        val json = JSONObject(journal.readFully().toString(Charsets.UTF_8))
        val track = TrackSnapshot.decode(json.getString("track"))
        val uri = json.getString("uri")
        val original = File(directory, json.getString("original"))
        val hash = json.getString("hash")
        check(uri.isNotBlank() && track.id.isNotBlank() && documentIdentity(uri) == documentIdentity(track.id)) {
            "Recovery source identity does not match its song."
        }
        check(original.parentFile?.canonicalFile == directory.canonicalFile && original.name.startsWith("original.")) {
            "Recovery copy location is invalid."
        }
        check(hash.matches(Regex("[0-9a-f]{64}"))) { "Recovery verification hash is invalid." }
        // Missing/bad backup does not make the known source identity ambiguous. Keep it selectively
        // blocked; recover() verifies the retained bytes before any replacement.
        return Recovery(track, uri, original, hash)
    }

    fun canWrite(track: Track) = documents.writable(track) != null

    suspend fun edit(track: Track, patch: TagPatch): Track = withContext(Dispatchers.IO) { gate.withLock {
        check(inspect() == null) { "Recover the interrupted tag edit before making another change." }
        patch.validate()
        val extension = track.fileName.substringAfterLast('.', "").lowercase()
        require(extension in TagCodec.extensions) { "${extension.ifEmpty { "This format" }} is read-only for tag editing." }
        val uri = documents.writable(track) ?: error("Grant write access to this song’s folder first.")
        check(documentIdentity(uri) == documentIdentity(track.id)) { "The writable document does not match this song’s playback source." }
        directory.mkdirs()
        // Unjournaled files are preparation leftovers; no document has been touched yet.
        directory.listFiles()?.forEach { it.delete() }
        val original = File(directory, "original.$extension")
        val candidate = File(directory, "edited.$extension")
        documents.open(uri).use { input -> FileOutputStream(original).use { output ->
            input.copyTo(output); output.fd.sync()
        } }
        val originalHash = hash(original.inputStream())
        original.copyTo(candidate, overwrite = true)
        TagCodec.edit(candidate, patch)
        currentCoroutineContext().ensureActive()
        check(hash(documents.open(uri)) == originalHash) { "The song changed during editing. Reload it and try again." }
        val recovery = Recovery(track, uri, original, originalHash)
        unsafe = recovery
        verified = null
        readers.publishRecovery(RecoveryAdmission.Pending(documentIdentity(uri)))
        try { writeJournal(recovery) }
        catch (failure: Exception) {
            // This transaction has not entered replace; its original document is still untouched.
            markVerified(recovery)
            throw failure
        }
        // Cancellation must wait until the document is verified or rollback has finished. Pending
        // admission was published while the caller still owns its drained temporary reader lease.
        withContext(NonCancellable) {
            try {
                documents.replace(uri, candidate)
                check(hash(documents.open(uri)) == hash(candidate.inputStream())) { "Saved file did not verify." }
                markVerified(recovery)
            } catch (failure: Exception) {
                try { restore(recovery) }
                catch (rollback: Exception) {
                    failure.addSuppressed(rollback)
                    throw IOException("Write interrupted. The original is retained; use Recover tag edit in Settings.", failure)
                }
                throw IOException("Write failed; the original was restored. ${failure.message.orEmpty()}", failure)
            }
        }
        track.copy(uri = uri)
    } }

    suspend fun complete() = withContext(Dispatchers.IO) { gate.withLock {
        val pending = inspect()
        check(pending == null || sameRecord(pending, verified)) { "Unverified recovery cannot be discarded." }
        clear()
    } }
    suspend fun recover(): Track = withContext(Dispatchers.IO + NonCancellable) { gate.withLock {
        val recovery = inspect() ?: error("No interrupted edit.")
        restore(recovery)
        recovery.track
    } }
    private fun sameRecord(left: Recovery, right: Recovery?) = right != null &&
        left.uri == right.uri && left.original == right.original && left.hash == right.hash

    private fun markVerified(recovery: Recovery) {
        verified = recovery
        unsafe = null
        readers.publishRecovery(RecoveryAdmission.Clear)
    }
    private fun restore(recovery: Recovery) {
        check(recovery.original.isFile) { "Recovery copy is missing. Keep the recovery record and restore this song from another backup." }
        check(hash(recovery.original.inputStream()) == recovery.hash) { "Recovery copy failed verification; original retained." }
        if (hash(runCatching { documents.open(recovery.uri) }.getOrElse { ByteArrayInputStream(byteArrayOf()) }) != recovery.hash) {
            documents.replace(recovery.uri, recovery.original)
        }
        check(hash(documents.open(recovery.uri)) == recovery.hash) { "Recovery could not be verified." }
        // Byte safety is published before metadata refresh, which has its own durable retry journal.
        markVerified(recovery)
        clear()
    }
    private fun writeJournal(recovery: Recovery) {
        val json = JSONObject().put("track", TrackSnapshot.encode(recovery.track)).put("uri", recovery.uri)
            .put("original", recovery.original.name).put("hash", recovery.hash)
        val output = journal.startWrite()
        try { output.write(json.toString().toByteArray()); journal.finishWrite(output) }
        catch (failure: Exception) { journal.failWrite(output); throw failure }
    }
    private fun clear() { journal.delete(); directory.listFiles()?.forEach { it.delete() } }
    private fun hash(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = it.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
