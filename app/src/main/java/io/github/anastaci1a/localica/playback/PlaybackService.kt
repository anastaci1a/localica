package io.github.anastaci1a.localica.playback

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
        statistics = io.github.anastaci1a.localica.statistics.PlaybackStatistics(player, app.listening)
        (application as LocalicaApplication).queues.attach(player)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val controls = QueueTransportPlayer(player, (application as LocalicaApplication).queues)
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
        val checkpoint = (application as LocalicaApplication).queues.detach(closingPlayer)
        // No controller can restart this dying player while the final write is pending.
        closingSession?.release()
        finishPlaybackShutdown(checkpoint) {
            try { closingPlayer.release() } finally { afterRelease() }
        }
    }
}

/** Overrides route queue-owned transport commands through QueueController; other Player calls remain delegated. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QueueTransportPlayer(player: Player, private val queues: io.github.anastaci1a.localica.queue.QueueController) : ForwardingPlayer(player) {
    override fun play() = queues.playbackRequested(true)
    override fun pause() = queues.playbackRequested(false)
    override fun setPlayWhenReady(playWhenReady: Boolean) = queues.playbackRequested(playWhenReady)

    override fun seekToNext() { queues.next() }
    override fun seekToNextMediaItem() { queues.next() }
    override fun seekToPrevious() { queues.previous() }
    override fun seekToPreviousMediaItem() { queues.previous() }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        queues.settings { it.copy(shuffle = shuffleModeEnabled) }
    }
    override fun setRepeatMode(repeatMode: Int) {
        queues.settings { it.copy(advancedRepeat = null,
            repeat = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> io.github.anastaci1a.localica.queue.QueueRepeat.All
                Player.REPEAT_MODE_ONE -> io.github.anastaci1a.localica.queue.QueueRepeat.One
                else -> io.github.anastaci1a.localica.queue.QueueRepeat.Off
            }) }
    }
}
