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

    override fun onCreate() {
        super.onCreate()
        val app = application as LocalicaApplication
        val sources = app.nativeReaders.track(DefaultDataSource.Factory(this))
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = Player.REPEAT_MODE_OFF
            shuffleModeEnabled = false
        }
        engine = player
        statistics = io.github.anastaci1a.localica.statistics.PlaybackStatistics(player, (application as LocalicaApplication).statistics::record)
        (application as LocalicaApplication).queues.attach(player)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val controls = object : ForwardingPlayer(player) {
            override fun seekToNext() { (application as LocalicaApplication).queues.next() }
            override fun seekToNextMediaItem() { (application as LocalicaApplication).queues.next() }
            override fun seekToPrevious() { (application as LocalicaApplication).queues.previous() }
            override fun seekToPreviousMediaItem() { (application as LocalicaApplication).queues.previous() }
            override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
                val queues = (application as LocalicaApplication).queues
                queues.settings(queues.state.value.settings.copy(shuffle = shuffleModeEnabled))
            }
            override fun setRepeatMode(repeatMode: Int) {
                val queues = (application as LocalicaApplication).queues
                queues.settings(queues.state.value.settings.copy(advancedRepeat = null,
                    repeat = when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> io.github.anastaci1a.localica.queue.QueueRepeat.All
                        Player.REPEAT_MODE_ONE -> io.github.anastaci1a.localica.queue.QueueRepeat.One
                        else -> io.github.anastaci1a.localica.queue.QueueRepeat.Off
                    }))
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

    override fun onDestroy() {
        statistics?.close(); statistics = null
        engine?.let { (application as LocalicaApplication).queues.detach(it); it.release() }
        engine = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
