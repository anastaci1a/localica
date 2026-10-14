package io.github.anastaci1a.localica.editing

import android.util.AtomicFile
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import java.io.File
import org.json.JSONArray

/** Journal before touching bytes; discard only after every metadata consumer commits. */
internal class MetadataRefreshJournal(file: File) {
    private val atomic = AtomicFile(file)
    fun read(): List<Track> {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        val values = JSONArray(atomic.readFully().toString(Charsets.UTF_8))
        return (0 until values.length()).map { TrackSnapshot.decode(values.getString(it)) }
    }
    fun write(tracks: List<Track>) {
        if (tracks.isEmpty()) { atomic.delete(); return }
        atomic.baseFile.parentFile?.mkdirs()
        val bytes = JSONArray(tracks.distinctBy { it.id }.map(TrackSnapshot::encode)).toString().toByteArray()
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    fun include(track: Track) = write(read().filterNot { it.id == track.id } + track)
}
