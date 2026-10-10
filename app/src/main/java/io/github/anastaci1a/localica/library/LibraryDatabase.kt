package io.github.anastaci1a.localica.library

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
)

@Entity(tableName = "folder_tracks", primaryKeys = ["folderUri", "trackId"], foreignKeys = [
    ForeignKey(entity = FolderRecord::class, parentColumns = ["uri"], childColumns = ["folderUri"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackRecord::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("trackId")])
data class FolderTrackRecord(val folderUri: String, val trackId: String, val uri: String, val relativePath: String)

data class TrackInFolder(
    @Embedded val location: FolderTrackRecord,
    @Relation(parentColumn = "trackId", entityColumn = "id") val track: TrackRecord,
)

data class FolderWithTracks(
    @Embedded val folder: FolderRecord,
    @Relation(parentColumn = "uri", entityColumn = "folderUri", entity = FolderTrackRecord::class)
    val tracks: List<TrackInFolder>,
)

@Dao
abstract class LibraryDao {
    @Transaction @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, uri")
    abstract fun observe(): Flow<List<FolderWithTracks>>

    @Transaction @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, uri")
    abstract suspend fun read(): List<FolderWithTracks>

    @Upsert protected abstract suspend fun upsertFolder(folder: FolderRecord)
    @Upsert protected abstract suspend fun upsertTracks(tracks: List<TrackRecord>)
    @Update abstract suspend fun updateMetadata(track: TrackRecord)
    @Insert protected abstract suspend fun insertLocations(locations: List<FolderTrackRecord>)
    @Query("DELETE FROM folder_tracks WHERE folderUri = :uri") protected abstract suspend fun clearLocations(uri: String)
    @Query("DELETE FROM folders WHERE uri = :uri") protected abstract suspend fun deleteFolder(uri: String)
    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT trackId FROM folder_tracks)") protected abstract suspend fun pruneTracks()

    @Transaction
    open suspend fun replaceRoot(folder: FolderRecord, tracks: List<TrackRecord>, locations: List<FolderTrackRecord>) {
        require(locations.all { it.folderUri == folder.uri })
        upsertFolder(folder)
        upsertTracks(tracks)
        clearLocations(folder.uri)
        insertLocations(locations)
        pruneTracks()
    }

    @Transaction
    open suspend fun removeRoot(uri: String) {
        deleteFolder(uri)
        pruneTracks()
    }
}

@Database(entities = [FolderRecord::class, TrackRecord::class, FolderTrackRecord::class], version = 3, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
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
    suspend fun current(): LibrarySnapshot
    suspend fun replaceRoot(result: ScanResult)
    suspend fun removeRoot(uri: String)
    suspend fun updateMetadata(track: Track)
}

class RoomLibraryStore(private val dao: LibraryDao) : LibraryStore {
    override val snapshots = dao.observe().map { it.toSnapshot() }.flowOn(Dispatchers.Default)
    override suspend fun current() = dao.read().toSnapshot()
    override suspend fun replaceRoot(result: ScanResult) {
        dao.replaceRoot(FolderRecord(result.folder.uri, result.folder.name), result.tracks.map {
            TrackRecord(it.id, it.title, it.artist, it.album, it.durationMs, it.fileName,
                it.albumArtist, it.trackNumber, it.discNumber, it.year, it.genre, it.comment)
        }, result.tracks.map { FolderTrackRecord(result.folder.uri, it.id, it.uri, it.relativePath) })
    }
    override suspend fun removeRoot(uri: String) = dao.removeRoot(uri)
    override suspend fun updateMetadata(track: Track) = dao.updateMetadata(TrackRecord(track.id, track.title,
        track.artist, track.album, track.durationMs, track.fileName, track.albumArtist, track.trackNumber,
        track.discNumber, track.year, track.genre, track.comment))
}

private fun List<FolderWithTracks>.toSnapshot(): LibrarySnapshot {
    val tracks = flatMap { it.tracks }.groupBy { it.track.id }.values.map { occurrences ->
        val record = occurrences.first().track
        val locations = occurrences.map { TrackLocation(it.location.folderUri, it.location.uri, it.location.relativePath) }
        val location = locations.first()
        Track(location.uri, record.title, record.artist, record.album, record.durationMs, record.fileName,
            location.relativePath, record.id, record.albumArtist, record.trackNumber, record.discNumber, record.year,
            record.genre, record.comment, locations)
    }.sortedWith(trackOrder)
    return LibrarySnapshot(map { FolderSelection(it.folder.uri, it.folder.name) }, tracks)
}
