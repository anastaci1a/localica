package io.github.anastaci1a.localica.library

import io.github.anastaci1a.localica.ui.LibraryDataText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** #720: documented library data files: round trip, matching order, ambiguity, versions and rescans. */
class LibraryDataTest {
    private val music = FolderSelection("tree:music", "Music")
    private val backup = FolderSelection("tree:backup", "Backup")
    private fun song(id: String, title: String, path: String, folder: FolderSelection = music, artist: String? = "Artist",
        album: String? = "Album", duration: Long? = 200_000, added: Long? = 1_000) =
        Track("doc:$id", title, artist, album, duration, path.substringAfterLast('/'), path, id = id, addedAt = added,
            albumArtist = artist, trackNumber = 1, discNumber = 1, locations = listOf(TrackLocation(folder.uri, "doc:$id", path)))
    private val library = LibrarySnapshot(listOf(music, backup), listOf(
        song("a", "Alpha", "Artist/Album/01 Alpha.flac", added = 1_000),
        song("b", "Beta", "Artist/Album/02 Beta.flac", added = 2_000),
        song("c", "Gamma", "Other/03 Gamma.mp3", artist = null, album = null, added = null)))

    private fun export(snapshot: LibrarySnapshot): ByteArray = ByteArrayOutputStream().also {
        assertEquals(snapshot.tracks.size, writeLibraryData(snapshot, it, 99L, "test") { track -> track.id.length.toLong() })
    }.toByteArray()

    private fun document(vararg records: JSONObject, version: String = "1.0") = LibraryDataCodec.decode(JSONObject()
        .put("format", "localica-library-data").put("version", version)
        .put("tracks", org.json.JSONArray().apply { records.forEach { put(it) } }).toString())

    @Test fun exportCarriesIndexedModificationTimeAndNull() {
        val indexed = library.copy(tracks = library.tracks.mapIndexed { i, track -> track.copy(modifiedAt = if (i == 0) 321L else null) })
        val tracks = JSONObject(export(indexed).toString(Charsets.UTF_8)).getJSONArray("tracks")
        assertEquals(321L, tracks.getJSONObject(0).getLong("modifiedAt"))
        assertTrue(tracks.getJSONObject(1).isNull("modifiedAt"))
    }

    @Test fun unchangedExportRoundTripsWithZeroChangesInPlainAndGzipForm() {
        val bytes = export(library)
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        assertEquals("localica-library-data", root.getString("format"))
        assertEquals("1.2", root.getString("version"))
        val first = root.getJSONArray("tracks").getJSONObject(0)
        for (key in listOf("id", "uri", "locations", "sizeBytes", "durationMs", "title", "artist", "album", "albumArtist", "trackNumber", "discNumber", "addedAt", "firstSeenAt", "modifiedAt", "firstSeenModifiedAt"))
            assertTrue("missing $key", first.has(key))
        assertEquals("Music", first.getJSONArray("locations").getJSONObject(0).getString("rootName"))
        assertTrue(root.getJSONArray("tracks").getJSONObject(2).isNull("addedAt"))
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        for (input in listOf(bytes, gzip)) {
            val parsed = LibraryDataCodec.read(ByteArrayInputStream(input))
            assertEquals(library.tracks.map { it.id }, parsed.records.map { it.id })
            val plan = LibraryDataImport.plan(parsed, library)
            assertEquals(0, plan.changes)
            assertEquals(3, plan.matched[LibraryDataMatchStage.Id])
            val added = plan.fields.single { it.field == LibraryDataField.AddedAt }
            assertEquals(LibraryDataFieldSummary(LibraryDataField.AddedAt, 2, 2, 0, 0, 0, 0), added)
        }
    }

    @Test fun modificationEvidenceAndFirstSeenExportButOnlyDateAddedImportsFromEveryVersion() {
        val seen = library.copy(tracks = library.tracks.mapIndexed { i, track ->
            track.copy(firstSeenAt = 500, modifiedAt = 1_700_123_456_789L, firstSeenModifiedAt = if (i == 0) 123L else null)
        })
        val bytes = export(seen)
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        for (input in listOf(bytes, gzip)) {
            val parsed = LibraryDataCodec.read(input.inputStream())
            assertEquals(0, LibraryDataImport.plan(parsed, seen).changes)
        }
        val tracks = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("tracks")
        assertEquals(500L, tracks.getJSONObject(0).getLong("firstSeenAt"))
        assertEquals(1_700_123_456_789L, tracks.getJSONObject(0).getLong("modifiedAt"))
        assertEquals(123L, tracks.getJSONObject(0).getLong("firstSeenModifiedAt"))
        assertTrue(tracks.getJSONObject(1).has("firstSeenModifiedAt") && tracks.getJSONObject(1).isNull("firstSeenModifiedAt"))
        for (version in listOf("1.0", "1.1", "1.2")) {
            val old = document(JSONObject().put("id", "a").put("addedAt", 5), version = version)
            val plan = LibraryDataImport.plan(old, seen)
            assertEquals(version, old.version)
            assertEquals(mapOf("a" to 5L), plan.addedAtChanges)
            assertEquals(listOf(LibraryDataField.AddedAt), plan.fields.map { it.field })
            for (value in listOf<Any>(7L, "forged", JSONObject.NULL, JSONObject().put("bad", true))) {
                val forged = document(JSONObject().put("id", "a").put("addedAt", 5).put("firstSeenAt", value)
                    .put("modifiedAt", value).put("firstSeenModifiedAt", value), version = version)
                assertEquals(plan, LibraryDataImport.plan(forged, seen))
                assertNull(forged.records.single().modifiedAt)
                assertNull(forged.records.single().firstSeenModifiedAt)
            }
        }
    }

    @Test fun matchingUsesIdThenLocationThenStrictMetadata() {
        fun stage(record: JSONObject) = LibraryDataImport.match(document(record).records.single(), library)
        assertEquals(LibraryDataImport.Match.Matched("b", LibraryDataMatchStage.Id),
            stage(JSONObject().put("id", "b").put("uri", "doc:a").put("title", "Alpha")))
        assertEquals(LibraryDataImport.Match.Matched("a", LibraryDataMatchStage.Path),
            stage(JSONObject().put("id", "gone").put("uri", "doc:a")))
        assertEquals(LibraryDataImport.Match.Matched("b", LibraryDataMatchStage.Path),
            stage(JSONObject().put("locations", org.json.JSONArray().put(JSONObject().put("rootName", "Music").put("path", "Artist/Album/02 Beta.flac")))))
        assertEquals(LibraryDataImport.Match.Unmatched,
            stage(JSONObject().put("locations", org.json.JSONArray().put(JSONObject().put("rootName", "Backup").put("path", "Artist/Album/02 Beta.flac")))))
        assertEquals(LibraryDataImport.Match.Matched("a", LibraryDataMatchStage.Metadata),
            stage(JSONObject().put("title", " Alpha ").put("artist", "Artist").put("album", "Album").put("durationMs", 201_000)))
        assertEquals(LibraryDataImport.Match.Matched("c", LibraryDataMatchStage.Metadata),
            stage(JSONObject().put("title", "Gamma").put("artist", "").put("durationMs", 199_500)))
        // Strict: case, duration beyond one second, missing duration and a different album never match.
        for (miss in listOf(JSONObject().put("title", "alpha").put("artist", "Artist").put("album", "Album").put("durationMs", 200_000),
            JSONObject().put("title", "Alpha").put("artist", "Artist").put("album", "Album").put("durationMs", 201_001),
            JSONObject().put("title", "Alpha").put("artist", "Artist").put("album", "Album"),
            JSONObject().put("title", "Alpha").put("artist", "Artist").put("album", "Other").put("durationMs", 200_000)))
            assertEquals(miss.toString(), LibraryDataImport.Match.Unmatched, stage(miss))
        // Unicode NFC: a decomposed é equals the precomposed tag.
        val accented = library.copy(tracks = library.tracks + song("e", "Café", "e.mp3"))
        assertEquals(LibraryDataImport.Match.Matched("e", LibraryDataMatchStage.Metadata), LibraryDataImport.match(
            document(JSONObject().put("title", "Café").put("artist", "Artist").put("album", "Album").put("durationMs", 200_000)).records.single(), accented))
    }

    @Test fun ambiguousCandidatesAndDuplicateClaimsAreSkippedAndCounted() {
        val twins = library.copy(tracks = library.tracks + song("a2", "Alpha", "Artist/Album/01 Alpha.flac", folder = backup, added = 5))
        val plan = LibraryDataImport.plan(document(
            // Same path in two folders, no folder named: ambiguous at the location stage, never falls through.
            JSONObject().put("locations", org.json.JSONArray().put(JSONObject().put("path", "Artist/Album/01 Alpha.flac"))).put("addedAt", 10),
            // Identical metadata in two folders.
            JSONObject().put("title", "Alpha").put("artist", "Artist").put("album", "Album").put("durationMs", 200_000).put("addedAt", 11),
            // Two records claiming the same song: both skipped.
            JSONObject().put("id", "b").put("addedAt", 12),
            JSONObject().put("uri", "doc:b").put("addedAt", 13),
            // Unmatched, and a matched record with an invalid date.
            JSONObject().put("id", "missing").put("title", "Nope").put("durationMs", 1).put("addedAt", 14),
            JSONObject().put("id", "c").put("addedAt", "yesterday"),
            JSONObject().put("id", "a").put("addedAt", 1.5),
            JSONObject().put("id", "a2").put("addedAt", 99),
        ), twins)
        assertEquals(8, plan.records)
        assertEquals(4, plan.ambiguous)
        assertEquals(1, plan.unmatched)
        assertEquals(3, plan.matched[LibraryDataMatchStage.Id])
        assertEquals(mapOf("a2" to 99L), plan.addedAtChanges)
        assertEquals(LibraryDataFieldSummary(LibraryDataField.AddedAt, records = 6, matched = 1, changed = 1, unmatched = 1, ambiguous = 4, invalid = 2),
            plan.fields.single())
        val lines = LibraryDataText.preview(plan)
        assertTrue(lines.toString(), lines.any { "Unmatched: 1 · Ambiguous: 4" in it })
        assertTrue(lines.toString(), lines.any { it.startsWith("Date added: 1 matched, 1 changed, 0 unchanged, 1 unmatched, 4 ambiguous, 2 invalid") })
    }

    @Test fun newerMajorVersionsAndForeignFilesAreRefusedWhileUnknownFieldsAreIgnored() {
        val newer = runCatching { document(JSONObject().put("id", "a").put("addedAt", 5), version = "2.0") }.exceptionOrNull()
        assertTrue(newer is LibraryDataFormatException)
        assertTrue(newer!!.message, "newer" in newer.message!! && "Update Localica" in newer.message!!)
        for (text in listOf("[]", "not json", """{"format":"other","version":"1.0","tracks":[]}""",
            """{"format":"localica-library-data","tracks":[]}""", """{"format":"localica-library-data","version":"0.9","tracks":[]}""",
            """{"format":"localica-library-data","version":"1.0"}"""))
            assertTrue(text, runCatching { LibraryDataCodec.decode(text) }.exceptionOrNull() is LibraryDataFormatException)
        val future = LibraryDataCodec.decode("""{"format":"localica-library-data","version":"1.7","later":{"x":1},"tracks":[
            {"id":"a","addedAt":7,"rating":5,"locations":[{"path":"p","extra":true}]}, 3]}""")
        assertEquals(1, future.skippedEntries)
        assertEquals(LibraryDataRecord(id = "a", locations = listOf(LibraryDataLocation(path = "p")), addedAt = 7), future.records.single())
        assertEquals(mapOf("a" to 7L), LibraryDataImport.plan(future, library).addedAtChanges)
    }

    @Test fun importedDateAddedSurvivesFullAndQuickRescans() = runTest {
        val store = ScanStore()
        val files = FakeDocuments().apply { entries["root"] = listOf(audio("one", "one.mp3"), audio("two", "two.mp3")) }
        val repository = LibraryRepository(store, TreeScanner(files), GrantedFolders(), StandardTestDispatcher(testScheduler))
        repository.scan("root")
        val first = store.current().tracks.associate { it.id to it.addedAt }
        assertTrue(first.values.all { it != null })
        // An external tool edits the exported file; the edited file is imported.
        val exported = JSONObject(export(store.current()).toString(Charsets.UTF_8))
        val tracks = exported.getJSONArray("tracks")
        for (i in 0 until tracks.length()) tracks.getJSONObject(i).let { if (it.getString("id") == "one") it.put("addedAt", 42L) }
        val edited = LibraryDataCodec.decode(exported.toString())
        assertEquals(mapOf("one" to 42L), repository.previewLibraryData(edited).addedAtChanges)
        assertEquals(1, repository.importLibraryData(edited).changes)
        assertEquals(42L, store.current().tracks.single { it.id == "one" }.addedAt)
        files.tags["one"] = AudioMetadata(title = "Retagged")
        repository.scan("root")
        repository.scanNewFiles("root")
        val after = store.current().tracks.associateBy { it.id }
        assertEquals("Retagged", after.getValue("one").title)
        assertEquals(42L, after.getValue("one").addedAt)
        assertEquals(first["two"], after.getValue("two").addedAt)
        assertEquals(0, repository.previewLibraryData(edited).changes)
    }
}

/** Mirrors the Room store's presence-aware date carry: saved ids keep their date, nulls included. */
private class ScanStore : LibraryStore {
    override val snapshots = MutableStateFlow(LibrarySnapshot())
    private var clock = 1_000L
    override suspend fun current() = snapshots.value
    override suspend fun replaceRoot(result: ScanResult) {
        val before = snapshots.value
        val saved = before.tracks.associate { it.id to it.addedAt }
        val now = clock++
        snapshots.value = LibrarySnapshot(before.folders.filterNot { it.uri == result.folder.uri } + result.folder,
            before.tracks.filterNot { it.locations.any { place -> place.folderUri == result.folder.uri } } + result.tracks.map {
                it.copy(addedAt = saved.getOrDefault(it.id, now), locations = listOf(TrackLocation(result.folder.uri, it.uri, it.relativePath)))
            })
    }
    override suspend fun mergeRoot(result: ScanResult) {
        val before = snapshots.value
        val known = before.tracks.map { it.id }.toSet()
        val now = clock++
        snapshots.value = before.copy(tracks = before.tracks + result.tracks.filterNot { it.id in known }.map {
            it.copy(addedAt = now, locations = listOf(TrackLocation(result.folder.uri, it.uri, it.relativePath)))
        })
    }
    override suspend fun removeRoot(uri: String) = error("unused")
    override suspend fun updateMetadata(track: Track) = error("unused")
    override suspend fun applyAddedDates(dates: Map<String, Long>): Int {
        snapshots.value = snapshots.value.copy(tracks = snapshots.value.tracks.map { track -> dates[track.id]?.let { track.copy(addedAt = it) } ?: track })
        return dates.size
    }
}

private class GrantedFolders : FolderPermissions {
    override fun hasReadAccess(uri: String) = true
    override fun acquire(uri: String) = Unit
    override fun release(uri: String) = Unit
}
