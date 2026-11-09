package io.github.anastaci1a.localica.lastfm

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface LastFmStore {
    suspend fun read(): SavedLastFm
    suspend fun write(value: SavedLastFm)
}

/** A dedicated atomic snapshot; it never opens the local qualified-play database. */
class FileLastFmStore(file: File) : LastFmStore {
    private val atomic = AtomicFile(file)
    override suspend fun read(): SavedLastFm = withContext(Dispatchers.IO) {
        try { atomic.openRead().bufferedReader().use { LastFmSnapshotCodec.decode(it.readText()) } }
        catch (_: java.io.FileNotFoundException) { SavedLastFm() }
    }
    override suspend fun write(value: SavedLastFm) = withContext(Dispatchers.IO) {
        val output = atomic.startWrite()
        try { output.write(LastFmSnapshotCodec.encode(value).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}

object LastFmSnapshotCodec {
    fun encode(value: SavedLastFm): String = JSONObject().apply {
        put("username", value.username); put("useCounts", value.useCounts); put("attemptedAt", value.attemptedAt); put("active", value.active)
        put("keepSyncingInBackground", value.keepSyncingInBackground); put("activeAttempt", value.activeAttempt)
        put("mappings", JSONObject(value.mappings))
        value.snapshot?.let { snapshot -> put("snapshot", JSONObject().apply {
            put("username", snapshot.username); put("syncedAt", snapshot.syncedAt)
            put("localBaseline", snapshot.localBaseline?.let(::JSONObject)); put("refreshBoundarySeconds", snapshot.refreshBoundarySeconds)
            put("tracks", JSONArray().apply { snapshot.tracks.forEach { track -> put(JSONObject().apply {
                put("artist", track.artist); put("title", track.title); put("plays", track.plays)
                put("album", track.album); put("mbid", track.mbid); put("durationMs", track.durationMs)
            }) } })
        }) }
    }.toString()
    fun decode(text: String): SavedLastFm = JSONObject(text).run {
        val username = getString("username")
        val snapshot = optJSONObject("snapshot")?.run {
            require(getString("username").isNotBlank())
            val tracks = getJSONArray("tracks")
            LastFmSnapshot(getString("username"), getLong("syncedAt"), (0 until tracks.length()).map { index ->
                tracks.getJSONObject(index).run {
                    val plays = getLong("plays"); require(plays >= 0)
                    LastFmTrack(getString("artist"), getString("title"), plays,
                        if (isNull("album")) null else optString("album").takeIf(String::isNotBlank),
                        if (isNull("mbid")) null else optString("mbid").takeIf(String::isNotBlank),
                        if (isNull("durationMs")) null else getLong("durationMs").takeIf { it > 0 })
                }
            }, optJSONObject("localBaseline")?.let { baseline ->
                baseline.keys().asSequence().associateWith { baseline.getLong(it).also { count -> require(count >= 0) } }
            }, if (isNull("refreshBoundarySeconds")) null else getLong("refreshBoundarySeconds"))
        }
        val mappings = optJSONObject("mappings") ?: JSONObject()
        SavedLastFm(username, optBoolean("useCounts", false), snapshot,
            mappings.keys().asSequence().associateWith { mappings.getString(it) },
            if (isNull("attemptedAt")) null else getLong("attemptedAt"), optBoolean("active", true), optBoolean("keepSyncingInBackground", true),
            if (isNull("activeAttempt")) null else getString("activeAttempt"))
    }
}
