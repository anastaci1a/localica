package io.github.anastaci1a.localica.editing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
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

/** A durable original and journal precede every destructive byte write; rollback is verified too. */
class RecoverableTagWriter(private val directory: File, private val documents: EditableDocuments) {
    private val gate = Mutex()
    private val journal get() = AtomicFile(File(directory, "pending.json"))
    data class Recovery(val track: Track, val uri: String, val original: File, val hash: String)
    fun pending(): Recovery? {
        if (!journal.baseFile.exists() && !File(directory, "pending.json.bak").exists()) return null
        val json = JSONObject(journal.readFully().toString(Charsets.UTF_8))
        return Recovery(TrackSnapshot.decode(json.getString("track")), json.getString("uri"),
            File(directory, json.getString("original")), json.getString("hash"))
    }
    fun canWrite(track: Track) = documents.writable(track) != null

    suspend fun edit(track: Track, patch: TagPatch): Track = withContext(Dispatchers.IO) { gate.withLock {
        check(pending() == null) { "Recover the interrupted tag edit before making another change." }
        patch.validate()
        val extension = track.fileName.substringAfterLast('.', "").lowercase()
        require(extension in TagCodec.extensions) { "${extension.ifEmpty { "This format" }} is read-only for tag editing." }
        val uri = documents.writable(track) ?: error("Grant write access to this song’s folder first.")
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
        writeJournal(recovery)
        // Cancellation must wait until the document is verified or rolled back.
        withContext(NonCancellable) {
            try {
                documents.replace(uri, candidate)
                check(hash(documents.open(uri)) == hash(candidate.inputStream())) { "Saved file did not verify." }
            } catch (failure: Exception) {
                try { restore(recovery) }
                catch (_: Exception) { error("Write interrupted. The original is retained; use Recover tag edit in Settings.") }
                throw IOException("Write failed; the original was restored. ${failure.message.orEmpty()}", failure)
            }
        }
        track.copy(uri = uri)
    } }
    suspend fun complete() = withContext(Dispatchers.IO) { gate.withLock { clear() } }
    suspend fun recover(): Track = withContext(Dispatchers.IO + NonCancellable) { gate.withLock {
        val recovery = pending() ?: error("No interrupted edit.")
        restore(recovery)
        recovery.track
    } }
    private fun restore(recovery: Recovery) {
        check(hash(recovery.original.inputStream()) == recovery.hash) { "Recovery copy failed verification; original retained." }
        if (hash(runCatching { documents.open(recovery.uri) }.getOrElse { ByteArrayInputStream(byteArrayOf()) }) != recovery.hash) {
            documents.replace(recovery.uri, recovery.original)
        }
        check(hash(documents.open(recovery.uri)) == recovery.hash) { "Recovery could not be verified." }
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
