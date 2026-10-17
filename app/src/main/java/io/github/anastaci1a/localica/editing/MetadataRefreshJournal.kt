package io.github.anastaci1a.localica.editing

import android.util.AtomicFile
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Include before touching bytes; remove only after every metadata consumer commits. */
internal class MetadataRefreshJournal(
    file: File,
    private val commit: (AtomicFile, ByteArray) -> Unit = ::commitRefreshRecord,
) {
    private val legacy = AtomicFile(file)
    private val directory = File(file.path + ".entries")
    private data class Pending(val sequence: Long, val track: Track)
    private var entries: MutableMap<String, Pending>? = null
    private var nextSequence = 0L

    @Synchronized fun read(): List<Track> = access { loaded().values.sortedBy { it.sequence }.map { it.track } }

    /** A replacement may replay already-refreshed files after interruption, never lose retained ones. */
    @Synchronized fun write(tracks: List<Track>) = access {
        val current = loaded()
        val retained = tracks.distinctBy { it.id }
        retained.forEach { includeLoaded(current, it) }
        val ids = retained.mapTo(HashSet()) { it.id }
        current.keys.filterNot { it in ids }.forEach { id ->
            deleteChecked(record(id))
            current.remove(id)
        }
    }

    @Synchronized fun include(track: Track) = access { includeLoaded(loaded(), track) }

    private fun loaded(): MutableMap<String, Pending> {
        entries?.let { return it }
        val current = linkedMapOf<String, Pending>()
        if (directory.exists()) {
            check(directory.isDirectory) { "Pending metadata records are not a directory." }
            val names = checkNotNull(directory.list()) { "Cannot read pending metadata records." }
            val bases = names.map { it.removeSuffix(".bak").removeSuffix(".new") }.distinct().sorted()
            for (name in bases) {
                check(name.matches(Regex("[0-9a-f]{64}\\.json"))) { "Unrecognized pending metadata record." }
                val atomic = AtomicFile(File(directory, name))
                // A first write interrupted before rename on newer Android never admitted the writer.
                if (!exists(atomic)) continue
                val value = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
                val pending = Pending(value.getLong("sequence"), TrackSnapshot.decode(value.getString("track")))
                check(pending.sequence >= 0 && record(pending.track.id).baseFile.name == name) {
                    "Invalid pending metadata identity."
                }
                check(current.put(pending.track.id, pending) == null) { "Duplicate pending metadata identity." }
            }
        }
        nextSequence = (current.values.maxOfOrNull { it.sequence } ?: -1L).let {
            check(it < Long.MAX_VALUE) { "Pending metadata sequence exhausted." }; it + 1
        }
        if (exists(legacy)) {
            val values = JSONArray(legacy.readFully().toString(Charsets.UTF_8))
            // Read/validate the entire old journal before beginning migration. Existing committed
            // records win on a retry; never replace their newer URI with the old array snapshot.
            val imported = (0 until values.length()).map { TrackSnapshot.decode(values.getString(it)) }
            imported.forEach { if (it.id !in current) includeLoaded(current, it) }
            deleteChecked(legacy)
        }
        entries = current
        return current
    }

    private fun includeLoaded(current: MutableMap<String, Pending>, track: Track) {
        check(nextSequence < Long.MAX_VALUE) { "Pending metadata sequence exhausted." }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create pending metadata records." }
        val pending = Pending(nextSequence, track)
        val bytes = JSONObject().put("sequence", pending.sequence).put("track", TrackSnapshot.encode(track))
            .toString().toByteArray(Charsets.UTF_8)
        commit(record(track.id), bytes)
        current[track.id] = pending
        nextSequence++
    }

    private fun record(id: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory, "$name.json"))
    }

    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun deleteChecked(file: AtomicFile) {
        file.delete()
        check(!exists(file) && !File(file.baseFile.path + ".new").exists()) { "Cannot clear pending metadata record." }
    }

    private inline fun <T> access(block: () -> T): T = try { block() } catch (failure: Exception) {
        // A failed operation may have reached disk: reload before any subsequent mutation/retry.
        entries = null
        throw failure
    }
}

internal fun commitRefreshRecord(file: AtomicFile, bytes: ByteArray) {
    val stream = file.startWrite()
    try {
        stream.write(bytes)
        file.finishWrite(stream)
    }
    catch (failure: Exception) { file.failWrite(stream); throw failure }
    // AtomicFile can log a failed rename rather than throw. Do not admit an audio
    // overwrite unless this process can reopen the exact pending record it committed.
    // Verification failure must retain the committed file, not invoke rollback on a closed write.
    check(file.readFully().contentEquals(bytes)) { "Pending metadata record did not commit." }
}
