package io.github.anastaci1a.localica.library

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@Entity(tableName = "folders")
data class FolderRecord(@PrimaryKey val uri: String, val name: String)

@Entity(tableName = "tracks")
data class TrackRecord(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val fileName: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val comment: String?,
    val composer: String? = null,
    val lyricist: String? = null,
    val modifiedAt: Long? = null,
    val addedAt: Long? = null,
    val releaseType: String? = null,
    val compilation: Boolean? = null,
    val musicBrainzId: String? = null,
    val firstSeenAt: Long? = null,
)

@Entity(tableName = "folder_tracks", primaryKeys = ["folderUri", "trackId"], foreignKeys = [
    ForeignKey(entity = FolderRecord::class, parentColumns = ["uri"], childColumns = ["folderUri"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackRecord::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("trackId")])
data class FolderTrackRecord(val folderUri: String, val trackId: String, val uri: String, val relativePath: String,
    @ColumnInfo(defaultValue = "0") val metadataFailed: Boolean = false)

/** Average artwork colour per track (#525). No row: not yet computed. NULL colour: no embedded
 * artwork. [sourceModifiedAt] differing from `tracks.modifiedAt` marks the row stale. Kept
 * outside `tracks` so backfill writes never invalidate the catalog observer. */
@Entity(tableName = "track_artwork", foreignKeys = [
    ForeignKey(entity = TrackRecord::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
])
data class TrackArtworkRecord(@PrimaryKey val trackId: String, val sourceModifiedAt: Long?, val color: Int?)

data class ArtworkColorRow(val uri: String, val color: Int?)

data class TrackInFolder(
    @Embedded val location: FolderTrackRecord,
    @Relation(parentColumn = "trackId", entityColumn = "id") val track: TrackRecord,
)

data class FolderWithTracks(
    @Embedded val folder: FolderRecord,
    @Relation(parentColumn = "uri", entityColumn = "folderUri", entity = FolderTrackRecord::class)
    val tracks: List<TrackInFolder>,
)

data class AddedDate(val id: String, val addedAt: Long?, val firstSeenAt: Long?)

@Dao
abstract class LibraryDao {
    @Transaction @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, uri")
    abstract fun observe(): Flow<List<FolderWithTracks>>

    @Transaction @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, uri")
    abstract suspend fun read(): List<FolderWithTracks>

    @Query("SELECT COUNT(DISTINCT folder_tracks.trackId) FROM folder_tracks JOIN folders ON folders.uri = folder_tracks.folderUri")
    protected abstract suspend fun linkedTrackCount(): Int

    /** Every location URI of a track resolves to its colour; read only by the colour index. */
    @Query("SELECT folder_tracks.uri AS uri, track_artwork.color AS color FROM track_artwork " +
        "JOIN folder_tracks ON folder_tracks.trackId = track_artwork.trackId")
    abstract fun observeArtworkColors(): Flow<List<ArtworkColorRow>>

    @Query("SELECT tracks.id AS trackId, tracks.modifiedAt AS modifiedAt, MIN(folder_tracks.uri) AS uri FROM tracks " +
        "JOIN folder_tracks ON folder_tracks.trackId = tracks.id LEFT JOIN track_artwork ON track_artwork.trackId = tracks.id " +
        "WHERE track_artwork.trackId IS NULL OR track_artwork.sourceModifiedAt IS NOT tracks.modifiedAt " +
        "GROUP BY tracks.id LIMIT :limit")
    abstract suspend fun pendingArtwork(limit: Int): List<ArtworkSource>

    @Query("SELECT tracks.id AS trackId, tracks.modifiedAt AS modifiedAt, folder_tracks.uri AS uri FROM folder_tracks " +
        "JOIN tracks ON tracks.id = folder_tracks.trackId WHERE folder_tracks.uri IN (:uris)")
    abstract suspend fun artworkSources(uris: List<String>): List<ArtworkSource>

    @Query("INSERT OR REPLACE INTO track_artwork (trackId, sourceModifiedAt, color) " +
        "SELECT :trackId, :modifiedAt, :color WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId)")
    protected abstract suspend fun saveArtwork(trackId: String, modifiedAt: Long?, color: Int?)

    @Transaction open suspend fun saveArtworkColors(colors: List<Pair<ArtworkSource, Int>>) {
        colors.forEach { (source, color) ->
            saveArtwork(source.trackId, source.modifiedAt, color.takeIf { it != ArtworkColors.MISSING })
        }
    }

    /** Emits after every write to `tracks`: scans, merges, removals and tag edits. */
    @Query("SELECT COUNT(*) FROM tracks") abstract fun trackChanges(): Flow<Int>

    @Transaction
    @Query("SELECT folder_tracks.* FROM folder_tracks JOIN folders ON folders.uri = folder_tracks.folderUri " +
        "WHERE folder_tracks.trackId IN (:ids) ORDER BY folders.name COLLATE NOCASE, folders.uri")
    protected abstract suspend fun playbackRows(ids: Set<String>): List<TrackInFolder>

    /** Fresh count and selected records share this transaction; no observer/revision cache is reused. */
    @Transaction open suspend fun playbackSnapshot(trackIds: Set<String>): LibraryPlaybackRead {
        val ids = trackIds.toSet()
        val total = linkedTrackCount()
        if (ids.isEmpty()) return LibraryPlaybackRead(emptyList(), total)
        fun fullSelection(rows: List<FolderWithTracks>) = rows.toSnapshot().tracks.filter { it.id in ids }
        val selected = if (ids.size > 900) fullSelection(read()) else {
            val tracks = playbackRows(ids).canonicalTracks()
            // The full path's stable sort has no ID tiebreak. Preserve its original tie order.
            if (tracks.zipWithNext().any { (a, b) -> trackOrder.compare(a, b) == 0 }) fullSelection(read()) else tracks
        }
        return LibraryPlaybackRead(selected, total)
    }

    @Upsert protected abstract suspend fun upsertFolder(folder: FolderRecord)
    @Insert protected abstract suspend fun insertTracks(tracks: List<TrackRecord>)
    @Update protected abstract suspend fun updateTracks(tracks: List<TrackRecord>)
    @Query("SELECT id, addedAt, firstSeenAt FROM tracks WHERE id IN (:ids)") protected abstract suspend fun dates(ids: List<String>): List<AddedDate>
    @Transaction open suspend fun updateMetadata(track: TrackRecord) {
        val saved = dates(listOf(track.id)).singleOrNull() ?: return
        updateTracks(listOf(track.copy(addedAt = saved.addedAt, firstSeenAt = saved.firstSeenAt)))
    }
    @Transaction open suspend fun updateMetadataBatch(tracks: List<TrackRecord>) { tracks.forEach { updateMetadata(it) } }
    @Insert protected abstract suspend fun insertLocations(locations: List<FolderTrackRecord>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertNewLocations(locations: List<FolderTrackRecord>)
    @Query("DELETE FROM folder_tracks WHERE folderUri = :uri") protected abstract suspend fun clearLocations(uri: String)
    /** Tracks outlive their folders: the track row owns its dates, memberships only decide visibility (#930). */
    @Query("DELETE FROM folders WHERE uri = :uri") abstract suspend fun removeRoot(uri: String)

    /** Saved ids keep both dates, nulls included; a first sighting sets both to one [now] (#930). */
    private suspend fun saveTracks(folder: FolderRecord, tracks: List<TrackRecord>, locations: List<FolderTrackRecord>) {
        require(locations.all { it.folderUri == folder.uri })
        upsertFolder(folder)
        val now = System.currentTimeMillis()
        tracks.chunked(900).forEach { chunk ->
            val saved = dates(chunk.map { it.id }).associateBy { it.id }
            val (known, fresh) = chunk.partition { it.id in saved }
            updateTracks(known.map { saved.getValue(it.id).run { it.copy(addedAt = addedAt, firstSeenAt = firstSeenAt) } })
            insertTracks(fresh.map { it.copy(addedAt = now, firstSeenAt = now) })
        }
    }

    @Transaction
    open suspend fun replaceRoot(folder: FolderRecord, tracks: List<TrackRecord>, locations: List<FolderTrackRecord>) {
        saveTracks(folder, tracks, locations)
        clearLocations(folder.uri)
        insertLocations(locations)
    }

    /** Keeps existing memberships and their metadata failure state. */
    @Transaction
    open suspend fun mergeRoot(folder: FolderRecord, tracks: List<TrackRecord>, locations: List<FolderTrackRecord>) {
        saveTracks(folder, tracks, locations)
        insertNewLocations(locations)
    }

    @Query("UPDATE tracks SET addedAt = :addedAt WHERE id = :id") protected abstract suspend fun setAddedAt(id: String, addedAt: Long): Int

    /** Library data import (#720): every date in one transaction; returns the rows written. */
    @Transaction open suspend fun applyAddedDates(dates: Map<String, Long>): Int = dates.entries.sumOf { (id, date) -> setAddedAt(id, date) }
}

@Database(entities = [FolderRecord::class, TrackRecord::class, FolderTrackRecord::class, TrackArtworkRecord::class], version = 10, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN firstSeenAt INTEGER")
                db.execSQL("UPDATE tracks SET firstSeenAt = addedAt")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE tracks ADD COLUMN musicBrainzId TEXT") }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN releaseType TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN compilation INTEGER")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_artwork` (`trackId` TEXT NOT NULL, `sourceModifiedAt` INTEGER, `color` INTEGER, " +
                    "PRIMARY KEY(`trackId`), FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            }
        }
        /** Every migration, in order; shared by the app and migration tests. */
        val MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN addedAt INTEGER")
                db.execSQL("UPDATE tracks SET addedAt = ?", arrayOf(System.currentTimeMillis()))
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_tracks ADD COLUMN metadataFailed INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN composer TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lyricist TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN modifiedAt INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE tracks ADD COLUMN comment TEXT") }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE folders_new (uri TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                db.execSQL("INSERT INTO folders_new SELECT uri, name FROM folders")
                db.execSQL("CREATE TABLE tracks_new (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT, durationMs INTEGER, fileName TEXT NOT NULL, albumArtist TEXT, trackNumber INTEGER, discNumber INTEGER, year INTEGER, genre TEXT)")
                db.execSQL("CREATE TABLE locations_new (folderUri TEXT NOT NULL, trackId TEXT NOT NULL, uri TEXT NOT NULL, relativePath TEXT NOT NULL)")
                db.query("SELECT tracks.*, folders.uri AS rootUri FROM tracks JOIN folders ON tracks.folderId = folders.id").use { cursor ->
                    while (cursor.moveToNext()) {
                        fun text(name: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(name))
                        val uri = requireNotNull(text("uri"))
                        val identity = documentIdentity(uri)
                        val durationIndex = cursor.getColumnIndexOrThrow("durationMs")
                        db.execSQL("INSERT INTO tracks_new (id,title,artist,album,durationMs,fileName) VALUES (?,?,?,?,?,?)",
                            arrayOf<Any?>(identity, text("title"), text("artist"), text("album"),
                                if (cursor.isNull(durationIndex)) null else cursor.getLong(durationIndex), text("fileName")))
                        db.execSQL("INSERT INTO locations_new VALUES (?,?,?,?)", arrayOf(text("rootUri"), identity, uri, text("relativePath")))
                    }
                }
                db.execSQL("DROP TABLE tracks")
                db.execSQL("DROP TABLE folders")
                db.execSQL("ALTER TABLE folders_new RENAME TO folders")
                db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
                db.execSQL("CREATE TABLE folder_tracks (folderUri TEXT NOT NULL, trackId TEXT NOT NULL, uri TEXT NOT NULL, relativePath TEXT NOT NULL, PRIMARY KEY(folderUri,trackId), FOREIGN KEY(folderUri) REFERENCES folders(uri) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_folder_tracks_trackId ON folder_tracks(trackId)")
                db.execSQL("INSERT INTO folder_tracks SELECT * FROM locations_new")
                db.execSQL("DROP TABLE locations_new")
            }
        }
    }
}

internal fun documentIdentity(uri: String): String = try {
    val parsed = Uri.parse(uri)
    DocumentsContract.buildDocumentUri(requireNotNull(parsed.authority), DocumentsContract.getDocumentId(parsed)).toString()
} catch (_: IllegalArgumentException) { uri }

interface LibraryStore {
    val snapshots: Flow<LibrarySnapshot>
    /** Fresh metadata for explicit ordering; stores without a separate projection retain their usual source. */
    val sortSnapshots: Flow<LibrarySnapshot> get() = snapshots
    suspend fun current(): LibrarySnapshot
    suspend fun playbackSnapshot(trackIds: Set<String>): LibraryPlaybackRead {
        val full = snapshots.first()
        return LibraryPlaybackRead(full.tracks, full.tracks.size, full)
    }
    suspend fun replaceRoot(result: ScanResult)
    suspend fun mergeRoot(result: ScanResult)
    suspend fun removeRoot(uri: String)
    suspend fun updateMetadata(track: Track)
    suspend fun updateMetadataBatch(tracks: List<Track>) { tracks.forEach { updateMetadata(it) } }
    /** Writes imported dates added atomically; returns how many tracks were updated. */
    suspend fun applyAddedDates(dates: Map<String, Long>): Int = throw UnsupportedOperationException("This library store cannot import data.")
}

class RoomLibraryStore(private val dao: LibraryDao) : LibraryStore {
    private val searchIndex = LibrarySearchIndexBuilder()
    // Room re-queries after any write to these tables, including writes that leave the rows unchanged.
    // Equal snapshots are dropped here, off Main, so an unchanged re-read never republishes the
    // catalog (search index, statistics overlay, every browse query and a full grid recomposition).
    override val sortSnapshots = dao.observe().map {
        io.github.anastaci1a.localica.diagnostics.WorkCounters.libraryReads.incrementAndGet()
        it.toSnapshot()
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    override val snapshots = sortSnapshots.map {
        val context = currentCoroutineContext()
        searchIndex.prepare(it) { context.ensureActive() }
    }.flowOn(Dispatchers.Default)
    override suspend fun current() = dao.read().toSnapshot()
    override suspend fun playbackSnapshot(trackIds: Set<String>) = dao.playbackSnapshot(trackIds)
    override suspend fun replaceRoot(result: ScanResult) = save(result, dao::replaceRoot)
    override suspend fun mergeRoot(result: ScanResult) = save(result, dao::mergeRoot)
    private suspend fun save(result: ScanResult, write: suspend (FolderRecord, List<TrackRecord>, List<FolderTrackRecord>) -> Unit) = with(result) {
        write(FolderRecord(folder.uri, folder.name), tracks.map { it.record() },
            tracks.map { FolderTrackRecord(folder.uri, it.id, it.uri, it.relativePath, it.id in failedMetadataIds) })
    }
    override suspend fun removeRoot(uri: String) = dao.removeRoot(uri)
    override suspend fun applyAddedDates(dates: Map<String, Long>) = if (dates.isEmpty()) 0 else dao.applyAddedDates(dates)
    override suspend fun updateMetadata(track: Track) = updateMetadataBatch(listOf(track))
    override suspend fun updateMetadataBatch(tracks: List<Track>) = dao.updateMetadataBatch(tracks.map { it.record() })
}

/** Metadata only; the DAO owns both dates. */
private fun Track.record() = TrackRecord(id, title, artist, album, durationMs, fileName, albumArtist, trackNumber, discNumber,
    year, genre, comment, composer, lyricist, modifiedAt, releaseType = releaseType, compilation = compilation, musicBrainzId = musicBrainzId)

private fun List<FolderWithTracks>.toSnapshot(): LibrarySnapshot {
    val tracks = flatMap { it.tracks }.canonicalTracks()
    return LibrarySnapshot(map { FolderSelection(it.folder.uri, it.folder.name) }, tracks,
        flatMap { it.tracks }.filter { it.location.metadataFailed }.map {
            MetadataFailure(it.track.id, it.location.folderUri, it.location.relativePath)
        })
}

/** Both read paths preserve identical metadata, first-location choice, location order and track sort. */
private fun List<TrackInFolder>.canonicalTracks(): List<Track> =
    groupBy { it.track.id }.values.map { occurrences ->
        val record = occurrences.first().track
        val locations = occurrences.map { TrackLocation(it.location.folderUri, it.location.uri, it.location.relativePath) }
        val location = locations.first()
        Track(location.uri, record.title, record.artist, record.album, record.durationMs, record.fileName,
            location.relativePath, record.id, record.albumArtist, record.trackNumber, record.discNumber, record.year,
            record.genre, record.comment, locations, record.composer, record.lyricist, record.modifiedAt, record.addedAt,
            releaseType = record.releaseType, compilation = record.compilation, musicBrainzId = record.musicBrainzId, firstSeenAt = record.firstSeenAt)
    }.inCanonicalOrder()
