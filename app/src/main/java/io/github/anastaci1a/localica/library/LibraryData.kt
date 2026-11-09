package io.github.anastaci1a.localica.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Localica library data files (#720). The format contract, matching rules and per-field decisions live in
 * docs/library-data.md; change both together. Only [LibraryDataField]s are ever imported.
 */
object LibraryDataFormat {
    const val NAME = "localica-library-data"
    const val MAJOR = 1
    const val MINOR = 0
    const val VERSION = "$MAJOR.$MINOR"
    /** Metadata fallback tolerance: other tools often round durations to whole seconds. */
    const val DURATION_TOLERANCE_MS = 1_000L
}

class LibraryDataFormatException(message: String) : Exception(message)

/** One saved place of a song: the music folder (tree URI and display name), the path inside it and the document URI. */
data class LibraryDataLocation(val root: String? = null, val rootName: String? = null, val path: String? = null, val uri: String? = null)

/** Fields Localica owns and imports. Each has a per-field summary row. */
enum class LibraryDataField(val key: String, val label: String) { AddedAt("addedAt", "Date added") }

/** One song record. Identity keys are optional on import; [invalid] names imported fields whose values were rejected. */
data class LibraryDataRecord(
    val id: String? = null,
    val uri: String? = null,
    val locations: List<LibraryDataLocation> = emptyList(),
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val addedAt: Long? = null,
    val invalid: Set<LibraryDataField> = emptySet(),
)

data class LibraryDataDocument(val version: String, val records: List<LibraryDataRecord>, val skippedEntries: Int = 0)

object LibraryDataCodec {
    fun records(snapshot: LibrarySnapshot, sizeOf: (Track) -> Long? = { null }): List<LibraryDataRecord> {
        val names = snapshot.folders.associate { it.uri to it.name }
        return snapshot.tracks.map { track ->
            val places = track.locations.ifEmpty { listOf(TrackLocation("", track.uri, track.relativePath)) }
            LibraryDataRecord(track.id, track.uri, places.map {
                LibraryDataLocation(it.folderUri.ifEmpty { null }, names[it.folderUri], it.relativePath, it.uri)
            }, sizeOf(track), track.durationMs, track.title, track.artist, track.album, track.albumArtist,
                track.trackNumber, track.discNumber, track.addedAt)
        }
    }

    /** Streams one record per line so large libraries never build a single JSON string. */
    fun write(records: List<LibraryDataRecord>, out: Writer, exportedAt: Long, appVersion: String) {
        fun quote(value: String) = JSONObject.quote(value)
        out.write("{\n\"format\": ${quote(LibraryDataFormat.NAME)},\n\"version\": ${quote(LibraryDataFormat.VERSION)},\n")
        out.write("\"exportedAt\": $exportedAt,\n\"app\": ${quote("Localica $appVersion")},\n\"tracks\": [\n")
        records.forEachIndexed { index, record ->
            if (index > 0) out.write(",\n")
            out.write(encode(record).toString())
        }
        out.write("\n]\n}\n")
        out.flush()
    }

    fun encode(record: LibraryDataRecord): JSONObject = JSONObject().apply {
        fun field(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
        field("id", record.id); field("uri", record.uri)
        put("locations", JSONArray().apply { record.locations.forEach { place -> put(JSONObject().apply {
            put("root", place.root ?: JSONObject.NULL); put("rootName", place.rootName ?: JSONObject.NULL)
            put("path", place.path ?: JSONObject.NULL); put("uri", place.uri ?: JSONObject.NULL)
        }) } })
        field("sizeBytes", record.sizeBytes); field("durationMs", record.durationMs)
        field("title", record.title); field("artist", record.artist); field("album", record.album)
        field("albumArtist", record.albumArtist); field("trackNumber", record.trackNumber); field("discNumber", record.discNumber)
        field(LibraryDataField.AddedAt.key, record.addedAt)
    }

    /** Plain or gzip JSON, detected by content rather than file name. */
    fun read(input: InputStream): LibraryDataDocument {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val source = if (gzip) GZIPInputStream(buffered) else buffered
        return decode(source.readBytes().toString(Charsets.UTF_8))
    }

    fun decode(text: String): LibraryDataDocument {
        val root = runCatching { JSONObject(text.removePrefix(Char(0xFEFF).toString())) }.getOrNull()
            ?: throw LibraryDataFormatException("This file isn’t a Localica library data file.")
        if (root.optString("format") != LibraryDataFormat.NAME) throw LibraryDataFormatException("This file isn’t a Localica library data file.")
        val version = root.opt("version")?.toString().orEmpty()
        val major = Regex("""^(\d+)\.(\d+)$""").find(version)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw LibraryDataFormatException("This library data file has no readable version.")
        if (major > LibraryDataFormat.MAJOR) throw LibraryDataFormatException(
            "This file uses library data version $version, which is newer than this Localica supports " +
                "(${LibraryDataFormat.MAJOR}.x). Update Localica to import it. Nothing was changed.")
        if (major < LibraryDataFormat.MAJOR) throw LibraryDataFormatException("This library data version ($version) isn’t supported.")
        val tracks = root.optJSONArray("tracks") ?: throw LibraryDataFormatException("This library data file has no tracks list.")
        var skipped = 0
        val records = (0 until tracks.length()).mapNotNull { index ->
            val item = tracks.optJSONObject(index)
            if (item == null) { skipped++; null } else decode(item)
        }
        return LibraryDataDocument(version, records, skipped)
    }

    private fun decode(item: JSONObject): LibraryDataRecord {
        val invalid = mutableSetOf<LibraryDataField>()
        val added = item.integer(LibraryDataField.AddedAt.key)
        if (added is Read.Invalid || (added is Read.Value && added.value <= 0)) invalid += LibraryDataField.AddedAt
        val places = item.optJSONArray("locations")
        return LibraryDataRecord(
            id = item.text("id"), uri = item.text("uri"),
            locations = if (places == null) emptyList() else (0 until places.length()).mapNotNull { places.optJSONObject(it) }.map {
                LibraryDataLocation(it.text("root"), it.text("rootName"), it.text("path"), it.text("uri"))
            },
            sizeBytes = item.integer("sizeBytes").value, durationMs = item.integer("durationMs").value,
            title = item.text("title"), artist = item.text("artist"), album = item.text("album"),
            albumArtist = item.text("albumArtist"), trackNumber = item.integer("trackNumber").value?.toInt(),
            discNumber = item.integer("discNumber").value?.toInt(),
            addedAt = (added as? Read.Value)?.value?.takeIf { it > 0 }, invalid = invalid)
    }

    private sealed interface Read { data class Value(val value: Long) : Read; data object Absent : Read; data object Invalid : Read }
    private val Read.value: Long? get() = (this as? Read.Value)?.value

    private fun JSONObject.text(key: String): String? = if (!has(key) || isNull(key)) null else (opt(key) as? String)
    private fun JSONObject.integer(key: String): Read {
        if (!has(key) || isNull(key)) return Read.Absent
        return when (val value = opt(key)) {
            is Int -> Read.Value(value.toLong())
            is Long -> Read.Value(value)
            is BigInteger -> if (value.bitLength() < 64) Read.Value(value.toLong()) else Read.Invalid
            is BigDecimal -> runCatching { Read.Value(value.longValueExact()) }.getOrElse { Read.Invalid }
            is Double, is Float -> (value as Number).toDouble().let { if (it % 1.0 == 0.0 && abs(it) < 9.0E15) Read.Value(it.toLong()) else Read.Invalid }
            else -> Read.Invalid
        }
    }
}

enum class LibraryDataMatchStage { Id, Path, Metadata }

/** Per imported field: [records] carrying a valid value = [matched] + [unmatched] + [ambiguous]; [matched] = [changed] + unchanged. */
data class LibraryDataFieldSummary(val field: LibraryDataField, val records: Int, val matched: Int, val changed: Int,
    val unmatched: Int, val ambiguous: Int, val invalid: Int) {
    val unchanged get() = matched - changed
}

data class LibraryImportPlan(
    val records: Int,
    val matched: Map<LibraryDataMatchStage, Int>,
    val unmatched: Int,
    val ambiguous: Int,
    val skippedEntries: Int,
    val fields: List<LibraryDataFieldSummary>,
    /** Track id to imported date added, only where it differs from the saved value. */
    val addedAtChanges: Map<String, Long>,
) {
    val matchedRecords get() = matched.values.sum()
    val changes get() = addedAtChanges.size
}

/**
 * Matches each record to exactly one track: id, then location, then strict title/artist/album/duration.
 * The first stage with any candidate decides; several candidates there, or several records claiming one track,
 * are ambiguous and skipped. Nothing is guessed.
 */
object LibraryDataImport {
    sealed interface Match {
        data class Matched(val trackId: String, val stage: LibraryDataMatchStage) : Match
        data object Unmatched : Match
        data class Ambiguous(val candidates: Int) : Match
    }

    fun plan(document: LibraryDataDocument, snapshot: LibrarySnapshot): LibraryImportPlan {
        val index = Index(snapshot)
        val raw = document.records.map(index::match)
        val claims = raw.filterIsInstance<Match.Matched>().groupingBy { it.trackId }.eachCount()
        val matches = raw.map { if (it is Match.Matched && claims.getValue(it.trackId) > 1) Match.Ambiguous(claims.getValue(it.trackId)) else it }
        val changes = linkedMapOf<String, Long>()
        val fields = LibraryDataField.entries.map { field ->
            var records = 0; var matched = 0; var changed = 0; var unmatched = 0; var ambiguous = 0; var invalid = 0
            document.records.forEachIndexed { position, record ->
                if (field in record.invalid) { invalid++; return@forEachIndexed }
                val value = when (field) { LibraryDataField.AddedAt -> record.addedAt } ?: return@forEachIndexed
                records++
                when (val match = matches[position]) {
                    is Match.Matched -> {
                        matched++
                        val saved = when (field) { LibraryDataField.AddedAt -> index.byId.getValue(match.trackId).addedAt }
                        if (saved != value) { changed++; changes[match.trackId] = value }
                    }
                    Match.Unmatched -> unmatched++
                    is Match.Ambiguous -> ambiguous++
                }
            }
            LibraryDataFieldSummary(field, records, matched, changed, unmatched, ambiguous, invalid)
        }
        return LibraryImportPlan(document.records.size,
            LibraryDataMatchStage.entries.associateWith { stage -> matches.count { it is Match.Matched && it.stage == stage } },
            matches.count { it == Match.Unmatched }, matches.count { it is Match.Ambiguous }, document.skippedEntries, fields, changes)
    }

    fun match(record: LibraryDataRecord, snapshot: LibrarySnapshot): Match = Index(snapshot).match(record)

    private class Index(snapshot: LibrarySnapshot) {
        val byId = snapshot.tracks.associateBy { it.id }
        private val names = snapshot.folders.associate { it.uri to it.name }
        private val byUri = HashMap<String, MutableSet<String>>()
        private val byPath = HashMap<String, MutableList<Pair<TrackLocation, String>>>()
        private val byTitle = HashMap<String, MutableList<Track>>()
        init {
            snapshot.tracks.forEach { track ->
                (track.locations.map { it.uri } + track.uri).forEach { byUri.getOrPut(it) { linkedSetOf() } += track.id }
                track.locations.forEach { byPath.getOrPut(it.relativePath) { mutableListOf() } += it to track.id }
                byTitle.getOrPut(text(track.title) ?: "") { mutableListOf() } += track
            }
        }

        fun match(record: LibraryDataRecord): Match {
            record.id?.let { id -> if (id in byId) return Match.Matched(id, LibraryDataMatchStage.Id) }
            decide(locationCandidates(record), LibraryDataMatchStage.Path)?.let { return it }
            return decide(metadataCandidates(record), LibraryDataMatchStage.Metadata) ?: Match.Unmatched
        }

        private fun decide(candidates: Set<String>, stage: LibraryDataMatchStage): Match? = when (candidates.size) {
            0 -> null
            1 -> Match.Matched(candidates.single(), stage)
            else -> Match.Ambiguous(candidates.size)
        }

        private fun locationCandidates(record: LibraryDataRecord): Set<String> {
            val found = linkedSetOf<String>()
            (listOfNotNull(record.uri) + record.locations.mapNotNull { it.uri }).forEach { found += byUri[it].orEmpty() }
            record.locations.forEach { place ->
                val path = place.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return@forEach
                byPath[path].orEmpty().forEach { (location, id) ->
                    if ((place.root == null || place.root == location.folderUri) &&
                        (place.rootName == null || place.rootName == names[location.folderUri])) found += id
                }
            }
            return found
        }

        private fun metadataCandidates(record: LibraryDataRecord): Set<String> {
            val title = text(record.title) ?: return emptySet()
            val duration = record.durationMs ?: return emptySet()
            val artist = text(record.artist); val album = text(record.album)
            return byTitle[title].orEmpty().filter { track ->
                text(track.artist) == artist && text(track.album) == album &&
                    track.durationMs?.let { abs(it - duration) <= LibraryDataFormat.DURATION_TOLERANCE_MS } == true
            }.mapTo(linkedSetOf()) { it.id }
        }

        /** Strict comparison: exact characters after trimming and Unicode NFC; blank equals missing. Case matters. */
        private fun text(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }
}

/** A saved date added survives every later scan; only new identities take [now] (#720 keeps imported dates). */
internal fun addedDateAfterScan(saved: Long?, now: Long): Long = saved ?: now

internal fun writeLibraryData(snapshot: LibrarySnapshot, out: OutputStream, exportedAt: Long, appVersion: String,
    sizeOf: (Track) -> Long? = { null }): Int {
    val records = LibraryDataCodec.records(snapshot, sizeOf)
    out.bufferedWriter(Charsets.UTF_8).let { LibraryDataCodec.write(records, it, exportedAt, appVersion) }
    return records.size
}
