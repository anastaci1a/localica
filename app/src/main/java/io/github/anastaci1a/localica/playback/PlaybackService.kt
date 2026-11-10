package io.github.anastaci1a.localica.playback

import kotlinx.coroutines.*
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.anastaci1a.localica.MainActivity
import io.github.anastaci1a.localica.LocalicaApplication

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var statistics: io.github.anastaci1a.localica.statistics.PlaybackStatistics? = null
    private var session: MediaSession? = null
    private var engine: ExoPlayer? = null
    private var shutdownStarted = false
    private val readiness = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val app = application as LocalicaApplication
        val sources = app.nativeReaders.track(DefaultDataSource.Factory(this))
        val player = ExoPlayer.Builder(this, PlaybackRenderersFactory(this)).setMediaSourceFactory(DefaultMediaSourceFactory(sources, PlaybackExtractors(decodedEditBinding = true))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleModeEnabled = false
        }
        engine = player
        app.preferences.launchWhenReady(readiness) {
            statistics = io.github.anastaci1a.localica.statistics.PlaybackStatistics(player, app.listening)
            app.queues.attach(player)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A cold system controller gets a session immediately; its commands suspend in arrival order.
        val controls = QueueTransportPlayer(player) { command ->
            app.preferences.launchWhenReady(readiness) {
                command(app.queues)
            }
        }
        session = MediaSession.Builder(this, controls)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (controller.packageName == packageName || controller.isTrusted) {
                        super.onConnect(session, controller)
                    } else MediaSession.ConnectionResult.reject()
                }
            }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Preserve Media3's ongoing-playback policy, but await our cursor before its stopSelf.
        if (!isPlaybackOngoing || sessions.none { it.player.isPlaying }) {
            beginShutdown { pauseAllPlayersAndStopSelf() }
        }
    }

    override fun onDestroy() {
        // Android cannot await onDestroy. Retain cleanup ownership independently of its lifecycle.
        beginShutdown()
        super.onDestroy()
    }

    private fun beginShutdown(afterRelease: () -> Unit = {}) {
        if (shutdownStarted) return
        shutdownStarted = true
        readiness.cancel()
        statistics?.close(); statistics = null
        val closingPlayer = engine
        val closingSession = session
        engine = null
        session = null
        if (closingPlayer == null) {
            closingSession?.release()
            afterRelease()
            return
        }
        closingPlayer.pause()
        val app = application as LocalicaApplication
        val checkpoint = if (app.preferences.ready.value) app.queues.detach(closingPlayer)
            else CompletableDeferred(Unit)
        // No controller can restart this dying player while the final write is pending.
        closingSession?.release()
        finishPlaybackShutdown(checkpoint) {
            try { closingPlayer.release() } finally { afterRelease() }
        }
    }
}

/** Overrides route queue-owned transport commands through QueueController; other Player calls remain delegated. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QueueTransportPlayer(player: Player,
    private val dispatch: ((io.github.anastaci1a.localica.queue.QueueController) -> Unit) -> Unit) : ForwardingPlayer(player) {
    constructor(player: Player, queues: io.github.anastaci1a.localica.queue.QueueController) : this(player, { it(queues) })
    override fun play() = dispatch { it.playbackRequested(true) }
    override fun pause() = dispatch { it.playbackRequested(false) }
    override fun setPlayWhenReady(playWhenReady: Boolean) = dispatch { it.playbackRequested(playWhenReady) }

    override fun seekToNext() { dispatch { it.next() } }
    override fun seekToNextMediaItem() { dispatch { it.next() } }
    override fun seekToPrevious() { dispatch { it.previous() } }
    override fun seekToPreviousMediaItem() { dispatch { it.previous() } }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        dispatch { queues -> queues.settings { it.copy(shuffle = shuffleModeEnabled) } }
    }
    override fun setRepeatMode(repeatMode: Int) {
        dispatch { queues -> queues.settings { it.copy(advancedRepeat = null,
            repeat = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> io.github.anastaci1a.localica.queue.QueueRepeat.All
                Player.REPEAT_MODE_ONE -> io.github.anastaci1a.localica.queue.QueueRepeat.One
                else -> io.github.anastaci1a.localica.queue.QueueRepeat.Off
            }) } }
    }
}
