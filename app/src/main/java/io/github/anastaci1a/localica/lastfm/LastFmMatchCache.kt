package io.github.anastaci1a.localica.lastfm

import android.util.AtomicFile
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The per-song result of matching one archive against one library (#779). Matching scans the whole
 * archive against the whole library, so its counts are kept across launches, keyed by what the
 * matcher reads: the library's matching fields, the archive identity and the manual mappings.
 * Review lists are not cached; they are rebuilt only when something displays them.
 */
data class LastFmMatchCache(val libraryKey: String, val archiveKey: String, val mappingsKey: String,
    val counts: Map<String, Long>, val recordingGroups: Map<String, String>, val matched: Int, val notInLibrary: Int,
    val dismissed: Int, val buckets: LastFmMatchBuckets) {
    fun matches(key: LastFmMatchKey) = libraryKey == key.library && archiveKey == key.archive && mappingsKey == key.mappings
    fun toMatches(snapshot: LastFmSnapshot) = LastFmMatches(counts, emptyList(), notInLibrary, matched = matched,
        dismissed = dismissed, recordingGroups = recordingGroups, sourceSnapshot = snapshot, complete = false, buckets = buckets)
    companion object {
        fun of(key: LastFmMatchKey, result: LastFmMatches) = LastFmMatchCache(key.library, key.archive, key.mappings, result.counts,
            result.recordingGroups, result.matched, result.notInLibrary, result.dismissed, result.buckets)
    }
}

data class LastFmMatchKey(val library: String, val archive: String, val mappings: String) {
    companion object {
        /** Only fields the matcher reads; play counts, locations and dates never invalidate a match. */
        fun of(catalog: LibrarySnapshot, snapshot: LastFmSnapshot, mappings: Map<String, String>): LastFmMatchKey =
            LastFmMatchKey(libraryKey(catalog.tracks), archiveKey(snapshot), mappingsKey(mappings))
        /** Unambiguous framing: a null marker or a 4-byte big-endian byte length before each field, never delimiters. */
        private fun MessageDigest.frame(value: String?) {
            if (value == null) { update(0xFF.toByte()); return }
            val bytes = value.toByteArray(Charsets.UTF_8)
            update(0); update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            update(bytes)
        }
        fun libraryKey(tracks: List<Track>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.frame(tracks.size.toString())
            tracks.forEach { track ->
                digest.frame(track.id); digest.frame(track.title); digest.frame(track.artist); digest.frame(track.albumArtist)
                digest.frame(track.album); digest.frame(track.durationMs?.toString()); digest.frame(track.musicBrainzId)
            }
            return digest.digest().toHex()
        }
        /** Every remote field the matcher reads plus each song's plays, length-delimited; a matcher change bumps the version. */
        fun archiveKey(snapshot: LastFmSnapshot): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.frame(MATCHER_VERSION); digest.frame(snapshot.username.lowercase()); digest.frame(snapshot.syncedAt.toString())
            digest.frame(snapshot.tracks.size.toString())
            snapshot.tracks.forEach { track ->
                digest.frame(track.artist); digest.frame(track.title); digest.frame(track.album); digest.frame(track.mbid)
                digest.frame(track.durationMs?.toString()); digest.frame(track.plays.toString())
            }
            return digest.digest().toHex()
        }
        /** Bump when LastFmMatchingEngine's output for equal inputs changes. */
        const val MATCHER_VERSION = "match-1"
        fun mappingsKey(mappings: Map<String, String>): String {
            if (mappings.isEmpty()) return "-"
            val digest = MessageDigest.getInstance("SHA-256")
            digest.frame(mappings.size.toString())
            mappings.entries.sortedBy { it.key }.forEach { (key, value) -> digest.frame(key); digest.frame(value) }
            return digest.digest().toHex()
        }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

interface LastFmMatchCacheStore {
    suspend fun read(): LastFmMatchCache?
    suspend fun write(value: LastFmMatchCache?)
}

/** No persistence: every launch matches in full. Tests and the Last.fm settings page rely on this. */
object NoLastFmMatchCacheStore : LastFmMatchCacheStore {
    override suspend fun read(): LastFmMatchCache? = null
    override suspend fun write(value: LastFmMatchCache?) {}
}

class FileLastFmMatchCacheStore(file: File) : LastFmMatchCacheStore {
    private val atomic = AtomicFile(file)
    override suspend fun read(): LastFmMatchCache? = withContext(Dispatchers.IO) {
        try { atomic.openRead().bufferedReader().use { LastFmMatchCacheCodec.decode(it.readText()) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // A missing or obsolete cache only costs one full match.
    }
    override suspend fun write(value: LastFmMatchCache?) = withContext(Dispatchers.IO) {
        if (value == null) { atomic.delete(); return@withContext }
        val output = atomic.startWrite()
        try { output.write(LastFmMatchCacheCodec.encode(value).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}

object LastFmMatchCacheCodec {
    /** Version 2 added the six bucket counters; older entries are rejected (one full counts-only match), never read as zeros. */
    private const val VERSION = 2
    fun encode(value: LastFmMatchCache): String = JSONObject().apply {
        put("version", VERSION); put("libraryKey", value.libraryKey); put("archiveKey", value.archiveKey); put("mappingsKey", value.mappingsKey)
        put("matched", value.matched); put("notInLibrary", value.notInLibrary); put("dismissed", value.dismissed)
        put("buckets", JSONObject().put("matched", value.buckets.matched).put("clear", value.buckets.clear).put("judgments", value.buckets.judgments)
            .put("review", value.buckets.review).put("absent", value.buckets.absent).put("editions", value.buckets.editions))
        put("counts", JSONObject(value.counts)); put("recordingGroups", JSONObject(value.recordingGroups))
    }.toString()
    fun decode(text: String): LastFmMatchCache? = JSONObject(text).run {
        if (optInt("version") != VERSION) return null
        val counts = getJSONObject("counts"); val groups = getJSONObject("recordingGroups"); val buckets = getJSONObject("buckets")
        LastFmMatchCache(getString("libraryKey"), getString("archiveKey"), getString("mappingsKey"),
            counts.keys().asSequence().associateWith { counts.getLong(it).also { count -> require(count >= 0) } },
            groups.keys().asSequence().associateWith { groups.getString(it) },
            getInt("matched"), getInt("notInLibrary"), getInt("dismissed"),
            LastFmMatchBuckets(buckets.getInt("matched"), buckets.getInt("clear"), buckets.getInt("judgments"),
                buckets.getInt("review"), buckets.getInt("absent"), buckets.getInt("editions")))
    }
}
