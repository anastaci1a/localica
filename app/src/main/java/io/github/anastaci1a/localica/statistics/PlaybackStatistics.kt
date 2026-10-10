package io.github.anastaci1a.localica.statistics

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*

/** Main-thread observer of the real service player, independent of Activity and queue cursor saves. */
class PlaybackStatistics(private val player: Player, record: (ListeningSource) -> Unit) : Player.Listener {
    private val counter = ListeningCounter(record)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init {
        player.addListener(this)
        update()
        scope.launch { while (isActive) { delay(1000); update() } }
    }
    private fun update(restart: Boolean = false) {
        val item = player.currentMediaItem
        val extras = item?.mediaMetadata?.extras
        val source = extras?.getString("trackId")?.let {
            ListeningSource(requireNotNull(item).mediaId, it, extras.getString("sourcePlaylistId"))
        }
        val duration = player.duration.takeIf { it > 0 }
            ?: extras?.getLong("durationMs")?.takeIf { it > 0 }
        counter.update(SystemClock.elapsedRealtime(), source, duration, player.isPlaying, restart)
    }
    override fun onIsPlayingChanged(isPlaying: Boolean) = update()
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = update()
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = update()
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Automatic transitions include repeat-one. A deliberate restart at zero begins a new traversal;
        // all other seeks retain elapsed listening and never add the skipped media position.
        update(restart = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
            (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.positionMs > 0 && newPosition.positionMs == 0L))
    }
    fun close() {
        counter.sample(SystemClock.elapsedRealtime())
        player.removeListener(this)
        scope.cancel()
    }
}
