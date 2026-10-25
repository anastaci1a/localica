package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.enumValue
import android.content.Context
import io.github.anastaci1a.localica.library.SongListView

class QueuePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("queue-settings", Context.MODE_PRIVATE)
    fun read() = QueueSettings(
        songTap = preferences.enumValue<SongTap>("tap") ?: SongTap.NewQueue,
        switching = preferences.enumValue<QueueSwitch>("switch-browse") ?: QueueSwitch.Browse,
        tapOverrides = SongListView.entries.mapNotNull { view ->
            preferences.enumValue<SongTap>("tap.${view.name}")?.let { view to it }
        }.toMap(),
        maximum = preferences.getInt("maximum", 0).takeIf { it > 0 },
        preloadNext = preferences.getBoolean("preload-next", true),
        boundaryDeclick = preferences.getBoolean("boundary-declick", false),
        shuffle = preferences.getBoolean("shuffle", false),
        repeat = preferences.enumValue<QueueRepeat>("repeat") ?: QueueRepeat.Off,
        previousBehavior = preferences.enumValue<PreviousBehavior>("previous-behavior")
            ?: PreviousBehavior.RestartAfterThreshold,
        previousThresholdSeconds = preferences.getInt("previous-threshold-seconds", 5).coerceIn(1, 60),
        advancedRepeat = if (preferences.getBoolean("advanced-repeat", false)) AdvancedRepeat(
            preferences.enumValue<SongEnd>("song-end") ?: SongEnd.Next,
            preferences.enumValue<QueueEnd>("queue-end") ?: QueueEnd.Stop,
            preferences.getBoolean("queue-reset-first", false), preferences.getBoolean("queue-reshuffle", true)) else null,
    )
    fun migratePlaybackModes(active: ListeningQueue?) {
        if (!preferences.contains("global-playback-modes")) write(read().copy(shuffle = active?.shuffle ?: false, repeat = active?.repeat ?: QueueRepeat.Off))
    }
    fun write(settings: QueueSettings) {
        val edit = preferences.edit()
        edit.putBoolean("advanced-repeat", settings.advancedRepeat != null)
        settings.advancedRepeat?.let {
            edit.putString("song-end", it.songEnd.name).putString("queue-end", it.queueEnd.name)
                .putBoolean("queue-reset-first", it.resetToFirst).putBoolean("queue-reshuffle", it.reshuffle)
        }
        SongListView.entries.forEach { edit.putString("tap.${it.name}", settings.tapOverrides[it]?.name) }
        edit.putString("tap", settings.songTap.name).putString("switch-browse", settings.switching.name)
            .putInt("maximum", settings.maximum ?: 0).putBoolean("preload-next", settings.preloadNext)
            .putBoolean("boundary-declick", settings.boundaryDeclick)
            .putString("previous-behavior", settings.previousBehavior.name).putInt("previous-threshold-seconds", settings.previousThresholdSeconds)
            .putBoolean("global-playback-modes", true).putBoolean("shuffle", settings.shuffle).putString("repeat", settings.repeat.name).apply()
    }
}
