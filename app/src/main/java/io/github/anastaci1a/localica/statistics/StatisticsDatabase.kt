package io.github.anastaci1a.localica.statistics

import androidx.room.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

/** No foreign keys: removing a folder, playlist, or queue must not erase listening history. */
@Entity(tableName = "qualified_plays", indices = [Index("trackId"), Index("playlistId")])
data class QualifiedPlay(@PrimaryKey val id: String = UUID.randomUUID().toString(), val trackId: String,
    val playlistId: String?, val playedAt: Long = System.currentTimeMillis())
data class PlayCount(val id: String, val count: Long)

@Dao
interface StatisticsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun record(play: QualifiedPlay)
    @Query("SELECT trackId AS id, COUNT(*) AS count FROM qualified_plays GROUP BY trackId")
    fun tracks(): Flow<List<PlayCount>>
    @Query("SELECT playlistId AS id, COUNT(*) AS count FROM qualified_plays WHERE playlistId IS NOT NULL GROUP BY playlistId")
    fun playlists(): Flow<List<PlayCount>>
}
@Database(entities = [QualifiedPlay::class], version = 1, exportSchema = true)
abstract class StatisticsDatabase : RoomDatabase() { abstract fun statistics(): StatisticsDao }

data class ListeningStatistics(val tracks: Map<String, Long> = emptyMap(), val playlists: Map<String, Long> = emptyMap()) {
    fun applyTo(snapshot: LibrarySnapshot): LibrarySnapshot {
        var changed: MutableList<Track>? = null
        snapshot.tracks.forEachIndexed { index, track ->
            val count = tracks[track.id] ?: 0
            if (track.playCount != count) {
                val updated = changed ?: snapshot.tracks.toMutableList().also { changed = it }
                updated[index] = track.copy(playCount = count)
            }
        }
        if (changed == null && snapshot.playlistPlays == playlists && snapshot.trackPlays == tracks) return snapshot
        return snapshot.copy(tracks = changed ?: snapshot.tracks, playlistPlays = playlists, trackPlays = tracks)
    }
}

/** Both the statistics map preparation and track overlay stay off the UI collector. */
internal fun Flow<LibrarySnapshot>.withListeningStatistics(statistics: Flow<ListeningStatistics>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default): Flow<LibrarySnapshot> =
    combine(this, statistics) { snapshot, counts -> counts.applyTo(snapshot) }.flowOn(dispatcher)

/** App-owned writes survive service teardown. Retry the same event ID without double counting. */
class StatisticsRepository(private val dao: StatisticsDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    val counts: Flow<ListeningStatistics> = combine(dao.tracks(), dao.playlists()) { tracks, playlists ->
        ListeningStatistics(tracks.associate { it.id to it.count }, playlists.associate { it.id to it.count })
    }
    private val pending = Channel<QualifiedPlay>(Channel.UNLIMITED)
    init {
        scope.launch {
            for (play in pending) {
                var retryDelay = 1000L
                while (isActive) {
                    try { dao.record(play); break }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        android.util.Log.w("ListeningStatistics", "Could not save qualified play; retrying", failure)
                        delay(retryDelay); retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                    }
                }
            }
        }
    }
    fun record(source: ListeningSource) { pending.trySend(QualifiedPlay(trackId = source.trackId, playlistId = source.playlistId)) }
}
