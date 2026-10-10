package io.github.anastaci1a.localica.queue

import androidx.room.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "queues")
data class QueueRecord(@PrimaryKey val id: String, val name: String, val createdAt: Long,
    val currentEntryId: String?, val positionMs: Long, val shuffle: Boolean, val repeat: String)
@Entity(tableName = "queue_entries", foreignKeys = [ForeignKey(entity = QueueRecord::class,
    parentColumns = ["id"], childColumns = ["queueId"], onDelete = ForeignKey.CASCADE)], indices = [Index("queueId")])
data class EntryRecord(@PrimaryKey val id: String, val queueId: String, val ordinal: Int, val track: String)
@Entity(tableName = "queue_state")
data class ActiveQueueRecord(@PrimaryKey val id: Int = 1, val queueId: String?)
data class StoredQueue(@Embedded val queue: QueueRecord,
    @Relation(parentColumn = "id", entityColumn = "queueId") val entries: List<EntryRecord>)

@Dao
abstract class QueueDao {
    @Transaction @Query("SELECT * FROM queues ORDER BY createdAt, id") abstract suspend fun readQueues(): List<StoredQueue>
    @Query("SELECT * FROM queue_state WHERE id = 1") abstract suspend fun readActive(): ActiveQueueRecord?
    @Upsert protected abstract suspend fun upsertQueue(queue: QueueRecord)
    @Upsert protected abstract suspend fun upsertActive(active: ActiveQueueRecord)
    @Insert protected abstract suspend fun insertEntries(entries: List<EntryRecord>)
    @Query("DELETE FROM queue_entries WHERE queueId = :id") protected abstract suspend fun clearEntries(id: String)
    @Query("DELETE FROM queues WHERE id = :id") protected abstract suspend fun deleteQueue(id: String)

    @Transaction open suspend fun read(): QueueLibrary {
        val queues = readQueues().map { stored -> stored.queue.let { q ->
            ListeningQueue(q.id, q.name, q.createdAt, stored.entries.sortedBy { it.ordinal }.map {
                QueueEntry(it.id, TrackSnapshot.decode(it.track)) }, q.currentEntryId, q.positionMs, q.shuffle,
                QueueRepeat.entries.firstOrNull { it.name == q.repeat } ?: QueueRepeat.Off)
        } }
        return QueueLibrary(queues, readActive()?.queueId?.takeIf { id -> queues.any { it.id == id } })
    }

    /** Cursor updates write only queue metadata; edits replace only the changed queue's entries. */
    @Transaction open suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        before.queues.filter { old -> after.queues.none { it.id == old.id } }.forEach { deleteQueue(it.id) }
        for (queue in after.queues) {
            val previous = before.queues.firstOrNull { it.id == queue.id }
            if (previous == queue) continue
            upsertQueue(QueueRecord(queue.id, queue.name, queue.createdAt, queue.currentEntryId,
                queue.positionMs, queue.shuffle, queue.repeat.name))
            if (previous?.entries != queue.entries) {
                clearEntries(queue.id)
                insertEntries(queue.entries.mapIndexed { index, entry ->
                    EntryRecord(entry.id, queue.id, index, TrackSnapshot.encode(entry.track)) })
            }
        }
        upsertActive(ActiveQueueRecord(queueId = after.activeId))
    }
}

@Database(entities = [QueueRecord::class, EntryRecord::class, ActiveQueueRecord::class], version = 1, exportSchema = true)
abstract class QueueDatabase : RoomDatabase() { abstract fun queues(): QueueDao }

/** A queue can outlive its library roots. Its metadata and granted locations remain a snapshot. */
internal object TrackSnapshot {
    fun encode(track: Track): String = JSONObject().apply {
        put("id", track.id); put("uri", track.uri); put("title", track.title); put("artist", track.artist)
        put("album", track.album); put("albumArtist", track.albumArtist); put("duration", track.durationMs)
        put("file", track.fileName); put("path", track.relativePath); put("track", track.trackNumber)
        put("disc", track.discNumber); put("year", track.year); put("genre", track.genre); put("comment", track.comment)
        put("locations", JSONArray().apply { track.locations.forEach { place -> put(JSONObject().apply {
            put("root", place.folderUri); put("uri", place.uri); put("path", place.relativePath)
        }) } })
    }.toString()
    fun decode(text: String): Track = JSONObject(text).run {
        fun optional(key: String) = if (isNull(key)) null else getString(key)
        val places = optJSONArray("locations") ?: JSONArray()
        Track(id = getString("id"), uri = getString("uri"), title = getString("title"), artist = optional("artist"),
            album = optional("album"), albumArtist = optional("albumArtist"), durationMs = optional("duration")?.toLong(),
            fileName = getString("file"), relativePath = getString("path"), trackNumber = optional("track")?.toInt(),
            discNumber = optional("disc")?.toInt(), year = optional("year")?.toInt(), genre = optional("genre"),
            comment = optional("comment"), locations = (0 until places.length()).map { places.getJSONObject(it).run {
                TrackLocation(getString("root"), getString("uri"), getString("path")) } })
    }
}
