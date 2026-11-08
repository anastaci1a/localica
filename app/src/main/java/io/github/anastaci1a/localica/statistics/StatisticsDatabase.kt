package io.github.anastaci1a.localica.statistics

import androidx.room.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
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
    @Query("SELECT trackId AS id, COUNT(*) AS count FROM qualified_plays WHERE playedAt >= :from AND playedAt <= :through GROUP BY trackId")
    fun tracksBetween(from: Long, through: Long): Flow<List<PlayCount>>
    @Query("SELECT playlistId AS id, COUNT(*) AS count FROM qualified_plays WHERE playlistId IS NOT NULL GROUP BY playlistId")
    fun playlists(): Flow<List<PlayCount>>
}
@Database(entities = [QualifiedPlay::class], version = 1, exportSchema = true)
abstract class StatisticsDatabase : RoomDatabase() { abstract fun statistics(): StatisticsDao }

/** Both the statistics map preparation and track overlay stay off the UI collector. Each published
 * snapshot also carries its prepared ID lookup, so synchronous UI projections (queue opening) never build it on Main. */
internal fun Flow<LibrarySnapshot>.withListeningStatistics(statistics: Flow<ListeningStatistics>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default): Flow<LibrarySnapshot> =
    combine(this, statistics) { snapshot, counts -> counts.applyTo(snapshot).also { it.tracksById } }.flowOn(dispatcher)

/** App-owned writes survive service teardown. Retry the same event ID without double counting. */
class StatisticsRepository(private val dao: StatisticsDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val identities: ListeningIdentityStore? = null) {
    val counts: Flow<ListeningStatistics> = combine(dao.tracks(), dao.playlists()) { tracks, playlists ->
        ListeningStatistics(tracks.associate { it.id to it.count }, playlists.associate { it.id to it.count })
    }.distinctUntilChanged()
    private sealed interface Write {
        data class Identity(val value: ListeningIdentity?) : Write
        data class Play(val value: QualifiedPlay) : Write
    }
    private val pending = Channel<Write>(Channel.UNLIMITED)
    fun restoredIdentity(): ListeningIdentity? = identities?.read()
    fun checkpoint(identity: ListeningIdentity?) { pending.trySend(Write.Identity(identity)) }
    init {
        scope.launch {
            for (play in pending) {
                var retryDelay = 1000L
                while (isActive) {
                    try {
                        when (play) {
                            is Write.Identity -> identities?.write(play.value)
                            is Write.Play -> dao.record(play.value)
                        }
                        break
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        android.util.Log.w("ListeningStatistics", "Could not save qualified play; retrying", failure)
                        delay(retryDelay); retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                    }
                }
            }
        }
    }
    fun record(source: ListeningSource) = record(source, UUID.randomUUID().toString())
    fun record(source: ListeningSource, eventId: String) {
        pending.trySend(Write.Play(QualifiedPlay(id = eventId, trackId = source.trackId, playlistId = source.playlistId)))
    }
}

class LocalPlayCountSource(private val dao: StatisticsDao) : PlayCountSource {
    override fun counts(window: ListeningWindow): Flow<Map<String, Long>> =
        dao.tracksBetween(window.from, window.through).map { rows ->
            rows.associate { it.id to it.count }
        }
}
