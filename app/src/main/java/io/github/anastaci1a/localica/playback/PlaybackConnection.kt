package io.github.anastaci1a.localica.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.LocalicaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PlaybackProblem { Connection, Unreadable, Unsupported, Failed }

data class PlaybackState(
    val connected: Boolean = false,
    val trackUri: String? = null,
    val trackId: String? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val fileName: String = "",
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val problem: PlaybackProblem? = null,
    val hasNext: Boolean = false,
)

interface PlaybackActions {
    val state: StateFlow<PlaybackState>
    fun play(track: Track)
    fun toggle()
    fun seekTo(positionMs: Long)
    fun clear()
}

/** Main-thread controller. The service retains playback when this UI connection is released. */
class PlaybackConnection(context: Context) : PlaybackActions, AutoCloseable {
    val queues = (context.applicationContext as LocalicaApplication).queues
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var closed = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                mutableState.value = mutableState.value.copy(connected = false, isPlaying = false,
                    isBuffering = false, problem = PlaybackProblem.Connection)
            }
        }).buildAsync()

    init {
        future.addListener({
            if (!closed) {
                try {
                    controller = future.get().also { it.addListener(listener) }
                    publish()
                } catch (_: Exception) {
                    mutableState.value = PlaybackState(problem = PlaybackProblem.Connection)
                }
            }
        }, ContextCompat.getMainExecutor(context))
        scope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) publish()
                delay(250)
            }
        }
    }

    override fun play(track: Track) { queues.tap(listOf(track), track.id) }
    fun playFromList(tracks: List<Track>, selectedId: String) { queues.tap(tracks, selectedId) }
    fun previous() { queues.previous() }
    fun next() { queues.next() }
    fun retry() { queues.retryPlayback() }

    override fun toggle() { queues.togglePlayback() }
    override fun seekTo(positionMs: Long) { queues.seek(positionMs) }

    override fun clear() { queues.clearPlayback() }

    private fun publish() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            ?: metadata.extras?.takeIf { it.containsKey("durationMs") }?.getLong("durationMs")
        mutableState.value = PlaybackState(
            connected = player.isConnected,
            trackUri = metadata.extras?.getString("trackUri"),
            trackId = metadata.extras?.getString("trackId"),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            albumArtist = metadata.albumArtist?.toString(),
            fileName = metadata.extras?.getString("fileName").orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it },
            durationMs = duration,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
            problem = player.playerError?.toProblem(),
            hasNext = player.hasNextMediaItem(),
        )
    }

    override fun close() {
        closed = true
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
        scope.cancel()
    }
}

private fun PlaybackException.toProblem() = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackProblem.Unreadable
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackProblem.Unsupported
    else -> PlaybackProblem.Failed
}
