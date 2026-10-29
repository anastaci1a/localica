package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory metadata only: no corpus files, staging, scanning, grants or playback. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusJsonTest {
    private val authority = "io.github.localica.random.json.fixture"

    private fun manifest(tracks: ByteArray): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put(MANIFEST_COUNT_KEY, 13_313)
        .put("synthetic", true)
        .put("representative", false)
        .put("real_loads_assumed_slower", true)
        .put("tracks_manifest_sha256", digest(tracks))

    private fun parse(tracks: ByteArray = validTracks, root: JSONObject = manifest(tracks)): NavigationRandomCorpusBinding {
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        return NavigationRandomCorpusJson.parse(bytes, tracks, digest(bytes), authority)
    }

    private fun firstRow(change: (JSONObject) -> Unit): ByteArray {
        val first = JSONObject(validText.substringBefore('\n'))
        change(first)
        return (first.toString() + "\n" + validText.substringAfter('\n')).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun fullMappingBindsRawManifestDigestAndDiscardsUnverifiedMetadata() {
        val root = manifest(validTracks).put("description", "Unverified producer description")
            .put("tags", JSONObject().put("artist", "Not an observed tag"))
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        val binding = NavigationRandomCorpusJson.parse(bytes, validTracks, digest(bytes), authority)
        assertEquals(digest(bytes), binding.declaredManifestSha256)
        assertEquals(authority, binding.authority)
        assertEquals("manifest-shape-and-binding-only", binding.evidenceScope)
        assertEquals(13_313, binding.tracks.size)
        for (index in 0..13_312) {
            val track = binding.tracks[index]
            assertEquals(index, track.index)
            assertEquals("${NavigationScaleCatalog.NAMESPACE}:track:$index", track.documentId)
            assertEquals("not-created/audio/track$index.mp3", track.relativeMp3Path)
            assertEquals(100_000L, track.declaredFileBytes)
            assertEquals(hash(index + 1), track.fileSha256)
            assertEquals(hash(index + 50_000), track.audioSha256)
            assertEquals("not-created/art/shared.cover", track.coverRelativePath)
            assertEquals(hash(800_000), track.artworkSha256)
            assertEquals(NavigationScaleIdentity(authority, track.documentId), binding.plannedIdentityAt(index))
        }
    }

    @Test
    fun inputByteMutationAfterParseCannotChangeReturnedDescriptors() {
        val tracks = validTracks.copyOf()
        val bytes = manifest(tracks).toString().toByteArray(Charsets.UTF_8)
        val originalDigest = digest(bytes)
        val binding = NavigationRandomCorpusJson.parse(bytes, tracks, originalDigest, authority)
        val expected = binding.tracks.toList()
        bytes.fill(0)
        tracks.fill(0)
        assertEquals(originalDigest, binding.declaredManifestSha256)
        assertEquals(expected, binding.tracks)
        try {
            (binding.tracks as MutableList<NavigationRandomCorpusTrack>).clear()
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Returned descriptors remain an immutable snapshot.
        }
    }

    @Test
    fun manifestAndTracksWhitespaceChangesRequireTheirOwnRawByteDigests() {
        val bytes = manifest(validTracks).toString().toByteArray(Charsets.UTF_8)
        val spacedManifest = bytes + " \n".toByteArray(Charsets.UTF_8)
        rejects { NavigationRandomCorpusJson.parse(spacedManifest, validTracks, digest(bytes), authority) }
        assertEquals(13_313, NavigationRandomCorpusJson.parse(spacedManifest, validTracks, digest(spacedManifest), authority).tracks.size)
        val spacedTracks = (" " + validText).toByteArray(Charsets.UTF_8)
        rejects { parse(spacedTracks, manifest(validTracks)) }
        assertEquals(13_313, parse(spacedTracks).tracks.size)
    }

    @Test
    fun lfCrLfAndOneOptionalTerminalNewlineAreAccepted() {
        for (text in listOf(validText, validText + "\n", validText + " \t\r", validText.replace("\n", "\r\n"), validText.replace("\n", "\r\n") + "\r\n")) {
            assertEquals(13_313, parse(text.toByteArray(Charsets.UTF_8)).tracks.size)
        }
    }

    @Test
    fun expectedAndEmbeddedDigestsRejectMalformedOrMismatchedDeclarations() {
        val bytes = manifest(validTracks).toString().toByteArray(Charsets.UTF_8)
        for (bad in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), hash(900_001))) {
            rejects { NavigationRandomCorpusJson.parse(bytes, validTracks, bad, authority) }
            rejects { parse(root = manifest(validTracks).put("tracks_manifest_sha256", bad)) }
        }
        rejects { parse(root = manifest(validTracks).put("tracks_manifest_sha256", 42)) }
    }

    @Test
    fun malformedUtf8IsRejectedEvenWithMatchingRawByteDigests() {
        for (invalid in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xc3.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) {
            val manifestBytes = manifest(validTracks).toString().toByteArray(Charsets.UTF_8) + invalid
            rejects { NavigationRandomCorpusJson.parse(manifestBytes, validTracks, digest(manifestBytes), authority) }
            rejects { parse(validTracks + invalid) }
        }
    }

    @Test
    fun declaredByteBoundsRejectOversizedInputs() {
        assertEquals(64 * 1024, NavigationRandomCorpusJson.MAX_MANIFEST_BYTES)
        assertEquals(32 * 1024 * 1024, NavigationRandomCorpusJson.MAX_TRACKS_BYTES)
        val hugeManifest = ByteArray(NavigationRandomCorpusJson.MAX_MANIFEST_BYTES + 1) { 32 }
        rejects { NavigationRandomCorpusJson.parse(hugeManifest, validTracks, digest(hugeManifest), authority) }
        val hugeTracks = ByteArray(NavigationRandomCorpusJson.MAX_TRACKS_BYTES + 1) { 32 }
        rejects { parse(hugeTracks) }
    }

    @Test
    fun requiredManifestFieldsAndFlagTypesCannotBeCoerced() {
        val required = listOf("schema_version", MANIFEST_COUNT_KEY, "synthetic", "representative", "real_loads_assumed_slower", "tracks_manifest_sha256")
        for (key in required) {
            rejects { parse(root = manifest(validTracks).also { it.remove(key) }) }
            rejects { parse(root = manifest(validTracks).put(key, JSONObject.NULL)) }
        }
        for (key in listOf("schema_version", MANIFEST_COUNT_KEY)) {
            rejects { parse(root = manifest(validTracks).put(key, "1")) }
            rejects { parse(root = manifest(validTracks).put(key, true)) }
            rejects { parse(root = manifest(validTracks).put(key, 2)) }
        }
        for ((key, expected) in listOf("synthetic" to true, "representative" to false, "real_loads_assumed_slower" to true)) {
            rejects { parse(root = manifest(validTracks).put(key, !expected)) }
            rejects { parse(root = manifest(validTracks).put(key, expected.toString())) }
            rejects { parse(root = manifest(validTracks).put(key, 1)) }
        }
    }

    @Test
    fun optionalProducerObservationBooleansAreIgnoredButNotCoerced() {
        for (value in listOf(false, true)) {
            val root = manifest(validTracks).put("installed", value).put("scanned", value).put("persisted_queue", value)
            assertEquals("manifest-shape-and-binding-only", parse(root = root).evidenceScope)
        }
        for (key in listOf("installed", "scanned", "persisted_queue")) {
            for (bad in listOf<Any>("false", 0, JSONObject.NULL)) {
                rejects { parse(root = manifest(validTracks).put(key, bad)) }
            }
        }
    }

    @Test
    fun jsonlRejectsMissingExtraDuplicateAndBlankRecords() {
        val first = validText.substringBefore('\n')
        val rest = validText.substringAfter('\n')
        for (text in listOf("", rest, validText + "\n" + first, first + "\n" + first + "\n" + rest.substringAfter('\n'),
            "\n" + validText, validText + "\n\n", first + "\n \n" + rest, first + "\r" + rest)) {
            rejects { parse(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun malformedRootsUnterminatedJsonTrailingTokensAndLiteralNulFailClosed() {
        val manifest = manifest(validTracks).toString()
        for (text in listOf("[]", "true", "null", "{", manifest + "{}", manifest + "/* trailing */", manifest + '\u0000')) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            rejects { NavigationRandomCorpusJson.parse(bytes, validTracks, digest(bytes), authority) }
        }
        val rest = validText.substringAfter('\n')
        val first = validText.substringBefore('\n')
        for (row in listOf("[]", "true", "null", "{", first + "{}", first + "/* trailing */", first + '\u0000')) {
            rejects { parse((row + "\n" + rest).toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun trackFieldsRequireTheirDeclaredTypesAndPresence() {
        val keys = listOf("index", "document_id", "relative_path", "file_bytes", "file_sha256", "audio_payload_sha256", "artwork_path", "artwork_sha256")
        for (key in keys) {
            rejects { parse(firstRow { it.remove(key) }) }
            rejects { parse(firstRow { it.put(key, JSONObject.NULL) }) }
        }
        for (key in listOf("index", "file_bytes")) {
            rejects { parse(firstRow { it.put(key, "1") }) }
            rejects { parse(firstRow { it.put(key, true) }) }
        }
        for (key in keys.filterNot { it == "index" || it == "file_bytes" }) {
            rejects { parse(firstRow { it.put(key, 1) }) }
        }
        rejects { parse(firstRow { it.put("index", 2_147_483_648L) }) }
        rejects { parse(firstRow { it.put("file_bytes", 0) }) }
        rejects { parse(firstRow { it.put("file_bytes", -1) }) }
        assertEquals(3_000_000_000L, parse(firstRow { it.put("file_bytes", 3_000_000_000L) }).tracks[0].declaredFileBytes)
    }

    @Test
    fun floatingNumericTokensAreRejectedRatherThanCoercedToIntegers() {
        // Raw tokens avoid JSONObject's serialization normalizing integral doubles to integers.
        for ((key, number) in listOf("schema_version" to 1, MANIFEST_COUNT_KEY to 13_313)) {
            val root = manifest(validTracks).toString().replace("\"$key\":$number", "\"$key\":$number.0")
            val bytes = root.toByteArray(Charsets.UTF_8)
            rejects { NavigationRandomCorpusJson.parse(bytes, validTracks, digest(bytes), authority) }
        }
        for ((from, to) in listOf("\"index\":0" to "\"index\":0.0", "\"file_bytes\":100000" to "\"file_bytes\":100000.0")) {
            val row = validText.substringBefore('\n').replace(from, to)
            rejects { parse((row + "\n" + validText.substringAfter('\n')).toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun bindingStillRejectsUnsafePathsInvalidHashesAndDuplicateAudio() {
        rejects { parse(firstRow { it.put("relative_path", "../escape.mp3") }) }
        rejects { parse(firstRow { it.put("file_sha256", "bad") }) }
        rejects { parse(firstRow { it.put("audio_payload_sha256", hash(50_001)) }) }
        rejects { parse(firstRow { it.put("document_id", "wrong:track:0") }) }
        rejects { parse(firstRow { it.put("file_bytes", Long.MAX_VALUE) }) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Parser/decoder/type failures must use the promised argument-error boundary.
        }
    }

    private companion object {
        const val MANIFEST_COUNT_KEY = "count"

        fun hash(value: Int): String = value.toString(16).padStart(64, '0')
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        val validText: String by lazy {
            (0..13_312).joinToString("\n") { index ->
                JSONObject().put("index", index)
                    .put("document_id", "${NavigationScaleCatalog.NAMESPACE}:track:$index")
                    .put("relative_path", "not-created/audio/track$index.mp3")
                    .put("file_bytes", 100_000)
                    .put("file_sha256", hash(index + 1))
                    .put("audio_payload_sha256", hash(index + 50_000))
                    .put("artwork_path", "not-created/art/shared.cover")
                    .put("artwork_sha256", hash(800_000)).toString()
            }
        }
        val validTracks: ByteArray by lazy { validText.toByteArray(Charsets.UTF_8) }
    }
}
