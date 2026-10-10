package io.github.anastaci1a.localica.playlist

import androidx.room.*
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.TrackSnapshot

@Entity(tableName = "playlists")
data class PlaylistRecord(@PrimaryKey val id: String, val name: String, val createdAt: Long)
@Entity(tableName = "playlist_entries", foreignKeys = [ForeignKey(entity = PlaylistRecord::class,
    parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)], indices = [Index("playlistId")])
data class PlaylistEntryRecord(@PrimaryKey val id: String, val playlistId: String, val ordinal: Int, val track: String)
data class StoredPlaylist(@Embedded val playlist: PlaylistRecord,
    @Relation(parentColumn = "id", entityColumn = "playlistId") val entries: List<PlaylistEntryRecord>)

@Dao
abstract class PlaylistDao {
    @Transaction @Query("SELECT * FROM playlists ORDER BY createdAt, id") protected abstract suspend fun readAll(): List<StoredPlaylist>
    @Upsert protected abstract suspend fun upsert(playlist: PlaylistRecord)
    @Insert protected abstract suspend fun insert(entries: List<PlaylistEntryRecord>)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") protected abstract suspend fun clear(id: String)
    @Query("DELETE FROM playlists WHERE id = :id") protected abstract suspend fun delete(id: String)
    @Transaction open suspend fun read(): List<ManualPlaylist> = readAll().map { stored ->
        ManualPlaylist(stored.playlist.id, stored.playlist.name, stored.playlist.createdAt,
            stored.entries.sortedBy { it.ordinal }.map { SongRow(it.id, TrackSnapshot.decode(it.track)) })
    }
    @Transaction open suspend fun save(before: List<ManualPlaylist>, after: List<ManualPlaylist>) {
        val previous = before.associateBy { it.id }
        val retained = after.map { it.id }.toSet()
        before.filterNot { it.id in retained }.forEach { delete(it.id) }
        after.forEach { playlist ->
            val old = previous[playlist.id]
            if (old != playlist) {
                upsert(PlaylistRecord(playlist.id, playlist.name, playlist.createdAt))
                if (old?.entries != playlist.entries) {
                    clear(playlist.id)
                    insert(playlist.entries.mapIndexed { index, row ->
                        PlaylistEntryRecord(row.id, playlist.id, index, TrackSnapshot.encode(row.track)) })
                }
            }
        }
    }
}
@Database(entities = [PlaylistRecord::class, PlaylistEntryRecord::class], version = 1, exportSchema = true)
abstract class PlaylistDatabase : RoomDatabase() { abstract fun playlists(): PlaylistDao }
