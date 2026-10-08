package io.github.anastaci1a.localica.library

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "folders")
data class FolderRecord(@PrimaryKey val id: Int = 1, val uri: String, val name: String)

@Entity(tableName = "tracks", foreignKeys = [ForeignKey(
    entity = FolderRecord::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("folderId")])
data class TrackRecord(
    @PrimaryKey val uri: String,
    val folderId: Int = 1,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val fileName: String,
    val relativePath: String,
)

data class FolderWithTracks(
    @Embedded val folder: FolderRecord,
    @Relation(parentColumn = "id", entityColumn = "folderId") val tracks: List<TrackRecord>,
)

@Dao
abstract class LibraryDao {
    @Transaction @Query("SELECT * FROM folders WHERE id = 1")
    abstract fun observe(): Flow<FolderWithTracks?>

    @Transaction @Query("SELECT * FROM folders WHERE id = 1")
    abstract suspend fun read(): FolderWithTracks?

    @Query("DELETE FROM folders") protected abstract suspend fun clear()
    @Insert protected abstract suspend fun insertFolder(folder: FolderRecord)
    @Insert protected abstract suspend fun insertTracks(tracks: List<TrackRecord>)

    @Transaction
    open suspend fun replace(folder: FolderRecord, tracks: List<TrackRecord>) {
        clear()
        insertFolder(folder)
        insertTracks(tracks)
    }
}

@Database(entities = [FolderRecord::class, TrackRecord::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

interface LibraryStore {
    val snapshots: Flow<LibrarySnapshot>
    suspend fun current(): LibrarySnapshot
    suspend fun replace(snapshot: LibrarySnapshot)
}

class RoomLibraryStore(private val dao: LibraryDao) : LibraryStore {
    override val snapshots = dao.observe().map { it.toSnapshot() }
    override suspend fun current() = dao.read().toSnapshot()
    override suspend fun replace(snapshot: LibrarySnapshot) {
        val folder = requireNotNull(snapshot.folder)
        dao.replace(FolderRecord(uri = folder.uri, name = folder.name), snapshot.tracks.map {
            TrackRecord(uri = it.uri, title = it.title, artist = it.artist, album = it.album,
                durationMs = it.durationMs, fileName = it.fileName, relativePath = it.relativePath)
        })
    }
}

private fun FolderWithTracks?.toSnapshot(): LibrarySnapshot = this?.let {
    LibrarySnapshot(FolderSelection(folder.uri, folder.name), tracks.map {
        Track(it.uri, it.title, it.artist, it.album, it.durationMs, it.fileName, it.relativePath)
    }.sortedWith(trackOrder))
} ?: LibrarySnapshot()
