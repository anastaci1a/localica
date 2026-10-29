package io.github.anastaci1a.localica

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Hash-bound metadata declarations only. Copies caller bytes before hashing/decoding; neither
 * expected digests nor matching metadata bytes authenticate the producer or verify referenced
 * media, tags, artwork, paths, permissions, installation ownership, scans, queues or a byte ledger.
 * Extra descriptive fields/tags are discarded rather than promoted to verified media facts.
 * installed/scanned/persisted_queue, when present, are producer-time Boolean observations only:
 * both true and false are allowed and neither becomes current operational state in the result.
 *
 * Uses Android's platform-lenient JSONTokener, not a strict RFC JSON validator. Internal comments,
 * alternate quoting/separators/numeric syntax and duplicate-key overwrite retain platform behavior.
 * Raw scalar types, required declarations, UTF-8, literal NUL and trailing non-whitespace are
 * checked explicitly. JSONL splits on LF; CRLF and JSON whitespace around each object are allowed,
 * with an optional final LF but no blank records. A lone CR does not separate two records.
 */
internal object NavigationRandomCorpusJson {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_TRACKS_BYTES = 32 * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val MANIFEST_COUNT_KEY = "count"

    fun parse(manifestBytes: ByteArray, tracksBytes: ByteArray,
        expectedManifestSha256: String, authority: String): NavigationRandomCorpusBinding {
        require(manifestBytes.size in 1..MAX_MANIFEST_BYTES) { "Manifest metadata byte limit" }
        require(tracksBytes.size in 1..MAX_TRACKS_BYTES) { "Tracks metadata byte limit" }
        val manifestSnapshot = manifestBytes.copyOf()
        val tracksSnapshot = tracksBytes.copyOf()
        require(SHA256.matches(expectedManifestSha256)) { "Expected manifest SHA256 must be lowercase 64-hex" }
        require(sha256(manifestSnapshot) == expectedManifestSha256) { "Manifest metadata bytes do not match expected SHA256" }
        val manifest = parseObject(decodeUtf8(manifestSnapshot))
        require(integer(manifest, "schema_version") == 1L) { "Unsupported manifest schema" }
        require(integer(manifest, MANIFEST_COUNT_KEY) == NavigationScaleCatalog.TRACK_COUNT.toLong()) { "Unexpected declared track count" }
        require(boolean(manifest, "synthetic")) { "Require the synthetic corpus declaration" }
        require(!boolean(manifest, "representative")) { "This corpus is not representative" }
        require(boolean(manifest, "real_loads_assumed_slower")) { "Require the real-load limitation" }
        for (key in listOf("installed", "scanned", "persisted_queue")) {
            if (manifest.has(key)) boolean(manifest, key)
        }
        val tracksHash = string(manifest, "tracks_manifest_sha256")
        require(SHA256.matches(tracksHash)) { "Tracks metadata SHA256 must be lowercase 64-hex" }
        require(sha256(tracksSnapshot) == tracksHash) { "Tracks metadata bytes do not match manifest SHA256" }
        val text = decodeUtf8(tracksSnapshot)
        val rows = ArrayList<NavigationRandomCorpusTrack>(NavigationScaleCatalog.TRACK_COUNT)
        var start = 0
        while (start < text.length) {
            require(rows.size < NavigationScaleCatalog.TRACK_COUNT) { "Too many JSONL records" }
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val line = text.substring(start, end)
            require(line.isNotBlank()) { "Blank JSONL record" }
            val row = parseObject(line)
            val index = integer(row, "index")
            require(index in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Track index outside Int range" }
            rows += NavigationRandomCorpusTrack(
                index = index.toInt(),
                documentId = string(row, "document_id"),
                relativeMp3Path = string(row, "relative_path"),
                declaredFileBytes = integer(row, "file_bytes"),
                fileSha256 = string(row, "file_sha256"),
                audioSha256 = string(row, "audio_payload_sha256"),
                coverRelativePath = string(row, "artwork_path"),
                artworkSha256 = string(row, "artwork_sha256"),
            )
            start = if (newline < 0) text.length else newline + 1
        }
        return NavigationRandomCorpusBinding(authority, expectedManifestSha256, rows)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("Metadata is not valid UTF-8", failure)
    }

    private fun parseObject(text: String): JSONObject {
        require('\u0000' !in text) { "Literal NUL is not JSON whitespace or content" }
        try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            require(value is JSONObject) { "Require one JSON object" }
            // nextClean skips comments; next alone confuses an actual NUL with end-of-input.
            while (tokener.more()) require(tokener.next() in " \t\r\n") { "Trailing JSON content" }
            return value
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Malformed metadata JSON", failure)
        }
    }

    private fun value(objectValue: JSONObject, key: String): Any = try {
        objectValue.get(key)
    } catch (failure: JSONException) {
        throw IllegalArgumentException("Missing metadata field: $key", failure)
    }

    private fun string(objectValue: JSONObject, key: String): String {
        val value = value(objectValue, key)
        require(value is String) { "Require string metadata field: $key" }
        return value
    }

    private fun boolean(objectValue: JSONObject, key: String): Boolean {
        val value = value(objectValue, key)
        require(value is Boolean) { "Require Boolean metadata field: $key" }
        return value
    }

    private fun integer(objectValue: JSONObject, key: String): Long = when (val value = value(objectValue, key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Require integral Int/Long metadata field: $key")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
