package io.github.anastaci1a.localica.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.anastaci1a.localica.library.Track
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
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val fileName: String = "",
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val problem: PlaybackProblem? = null,
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

    override fun play(track: Track) {
        controller?.run {
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleModeEnabled = false
            val extras = Bundle().apply {
                putString("fileName", track.fileName)
                track.durationMs?.let { putLong("durationMs", it) }
            }
            // Replaces the timeline; there is never an implicit queue or automatic next track.
            setMediaItem(MediaItem.Builder().setMediaId(track.uri).setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setAlbumTitle(track.album).setExtras(extras).build()).build())
            prepare()
            play()
        }
        publish()
    }

    override fun toggle() {
        controller?.run {
            if (currentMediaItem == null) return
            if (playWhenReady && playbackState != Player.STATE_ENDED && playerError == null) {
                pause()
            } else {
                if (playbackState == Player.STATE_ENDED) seekTo(0)
                if (playbackState == Player.STATE_IDLE || playerError != null) prepare()
                play()
            }
        }
        publish()
    }

    override fun seekTo(positionMs: Long) {
        controller?.let { player ->
            val duration = state.value.durationMs ?: return
            player.seekTo(positionMs.coerceIn(0, duration))
        }
        publish()
    }

    override fun clear() {
        controller?.run { stop(); clearMediaItems() }
        publish()
    }

    private fun publish() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            ?: metadata.extras?.takeIf { it.containsKey("durationMs") }?.getLong("durationMs")
        mutableState.value = PlaybackState(
            connected = player.isConnected,
            trackUri = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            fileName = metadata.extras?.getString("fileName").orEmpty(),
            positionMs = player.currentPosition.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it },
            durationMs = duration,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
            problem = player.playerError?.toProblem(),
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
