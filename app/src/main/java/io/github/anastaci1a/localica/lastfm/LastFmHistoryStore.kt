package io.github.anastaci1a.localica.lastfm

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class LastFmHistoryCheckpoint(val id: String, val username: String, val from: Long, val through: Long,
    val full: Boolean, val sourceFingerprint: String, val localBaseline: Map<String, Long>,
    val nextPage: Int = 1, val totalPages: Int = 1, val tracks: List<LastFmTrack> = emptyList(), val lastFingerprint: String = "",
    val lastTimestamp: Long? = null)

interface LastFmHistoryStore {
    suspend fun read(): LastFmHistoryCheckpoint?
    suspend fun begin(checkpoint: LastFmHistoryCheckpoint)
    suspend fun append(previous: LastFmHistoryCheckpoint, next: LastFmHistoryCheckpoint, page: LastFmRecentPage)
    suspend fun clear(id: String? = null)
}

internal class MemoryLastFmHistoryStore : LastFmHistoryStore {
    private val lock = Mutex()
    private var value: LastFmHistoryCheckpoint? = null
    override suspend fun read() = lock.withLock { value }
    override suspend fun begin(checkpoint: LastFmHistoryCheckpoint) = lock.withLock { value = checkpoint }
    override suspend fun append(previous: LastFmHistoryCheckpoint, next: LastFmHistoryCheckpoint, page: LastFmRecentPage) = lock.withLock {
        check(value?.id == previous.id && value?.nextPage == previous.nextPage) { "Sync progress changed" }
        value = next
    }
    override suspend fun clear(id: String?) = lock.withLock { if (id == null || value?.id == id) value = null }
}

/** Immutable page journal plus an atomic cursor: a page is counted only after its cursor commits. */
class FileLastFmHistoryStore(private val directory: File) : LastFmHistoryStore {
    private val lock = Mutex()
    private fun metadata() = AtomicFile(File(directory, "checkpoint.json"))
    private fun base() = AtomicFile(File(directory, "base.json"))
    private fun page(number: Int) = AtomicFile(File(directory, "page-$number.json"))
    private fun readText(file: AtomicFile) = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    override suspend fun read(): LastFmHistoryCheckpoint? = withContext(Dispatchers.IO) { lock.withLock {
        try {
            val checkpoint = decode(JSONObject(readText(metadata())))
            val initial = LastFmSnapshotCodec.decode(readText(base())).snapshot!!.tracks
            val collected = initial.associateByTo(linkedMapOf()) { historyKey(it.artist, it.title, it.album) }
            var last = ""
            var timestamp: Long? = null
            for (number in 1 until checkpoint.nextPage) {
                val response = LastFmResponses.recent(readText(page(number)))
                require(response.number == number && response.totalPages == checkpoint.totalPages)
                aggregateHistory(collected, response.tracks)
                last = historyFingerprint(response.tracks)
                timestamp = response.tracks.lastOrNull()?.timestampSeconds ?: timestamp
            }
            require(last == checkpoint.lastFingerprint && timestamp == checkpoint.lastTimestamp)
            checkpoint.copy(tracks = collected.values.toList())
        } catch (_: java.io.FileNotFoundException) { null }
        catch (_: Exception) {
            // Corrupt staging is disposable. The separately published archive is never opened here.
            check(!directory.exists() || directory.deleteRecursively()); null
        }
    } }

    override suspend fun begin(checkpoint: LastFmHistoryCheckpoint) = withContext(Dispatchers.IO) { lock.withLock {
        check(!directory.exists() || directory.deleteRecursively()); check(directory.mkdirs())
        write(base(), LastFmSnapshotCodec.encode(SavedLastFm(snapshot = LastFmSnapshot(checkpoint.username, 0, checkpoint.tracks))))
        write(metadata(), encode(checkpoint).toString())
    } }

    override suspend fun append(previous: LastFmHistoryCheckpoint, next: LastFmHistoryCheckpoint, page: LastFmRecentPage) =
        withContext(Dispatchers.IO) { lock.withLock {
            val current = decode(JSONObject(readText(metadata())))
            check(current.id == previous.id && current.nextPage == previous.nextPage && page.number == previous.nextPage)
            require(next.id == current.id && next.nextPage == current.nextPage + 1)
            write(this@FileLastFmHistoryStore.page(page.number), pageJson(page).toString())
            write(metadata(), encode(next).toString())
        } }

    override suspend fun clear(id: String?) = withContext(Dispatchers.IO) { lock.withLock {
        val current = runCatching { decode(JSONObject(readText(metadata()))) }.getOrNull()
        if (id == null || current?.id == id) { check(!directory.exists() || directory.deleteRecursively()); Unit }
    } }

    private fun encode(value: LastFmHistoryCheckpoint) = JSONObject().apply {
        put("version", 1); put("id", value.id); put("username", value.username)
        put("from", value.from); put("through", value.through); put("full", value.full)
        put("sourceFingerprint", value.sourceFingerprint); put("localBaseline", JSONObject(value.localBaseline))
        put("nextPage", value.nextPage); put("totalPages", value.totalPages); put("lastFingerprint", value.lastFingerprint)
        put("lastTimestamp", value.lastTimestamp)
    }
    private fun decode(json: JSONObject): LastFmHistoryCheckpoint {
        require(json.getInt("version") == 1)
        val baseline = json.getJSONObject("localBaseline")
        return LastFmHistoryCheckpoint(json.getString("id"), json.getString("username"), json.getLong("from"), json.getLong("through"),
            json.getBoolean("full"), json.getString("sourceFingerprint"), baseline.keys().asSequence().associateWith {
                baseline.getLong(it).also { count -> require(count >= 0) }
            }, json.getInt("nextPage"), json.getInt("totalPages"), lastFingerprint = json.getString("lastFingerprint"),
            lastTimestamp = if (json.isNull("lastTimestamp")) null else json.getLong("lastTimestamp")).also {
            require(it.id.isNotBlank() && it.username.isNotBlank() && it.from >= 0 && it.through >= it.from)
            require(it.totalPages in 1..10_000 && it.nextPage in 1..it.totalPages + 1)
        }
    }
    private fun pageJson(page: LastFmRecentPage) = JSONObject().put("recenttracks", JSONObject().apply {
        put("@attr", JSONObject().put("page", page.number).put("totalPages", page.totalPages))
        put("track", JSONArray().apply { page.tracks.forEach { track -> put(JSONObject().apply {
            put("artist", track.artist); put("name", track.title); put("album", track.album); put("mbid", track.mbid)
            put("date", JSONObject().put("uts", track.timestampSeconds))
        }) } })
    })
}

internal fun historyKey(artist: String, title: String, album: String?): String =
    listOf(normalize(artist), normalize(title), normalize(album)).joinToString("") { "${it.length}:$it" }

internal fun aggregateHistory(collected: MutableMap<String, LastFmTrack>, rows: List<LastFmRecentTrack>) {
    rows.forEach { row ->
        val key = historyKey(row.artist, row.title, row.album)
        val old = collected[key]
        collected[key] = if (old == null) LastFmTrack(row.artist, row.title, 1, row.album, row.mbid)
        else old.copy(plays = addPlays(old.plays, 1), mbid = old.mbid.takeIf { normalize(it) == normalize(row.mbid) })
    }
    if (collected.size > 1_000_000) throw LastFmFailure("This Last.fm library is too large to sync.")
}

internal fun historyFingerprint(rows: List<LastFmRecentTrack>): String = digest(rows.joinToString("") { row ->
    listOf(row.artist, row.title, row.album.orEmpty(), row.mbid.orEmpty(), row.timestampSeconds.toString())
        .joinToString("") { "${it.length}:$it" }
})
internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
