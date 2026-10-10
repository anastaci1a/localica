package io.github.anastaci1a.localica.queue

import android.content.Context

class QueuePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("queue-settings", Context.MODE_PRIVATE)
    fun read() = QueueSettings(
        songTap = SongTap.entries.firstOrNull { it.name == preferences.getString("tap", null) } ?: SongTap.NewQueue,
        switching = QueueSwitch.entries.firstOrNull { it.name == preferences.getString("switch", null) } ?: QueueSwitch.Paused,
        maximum = preferences.getInt("maximum", 0).takeIf { it > 0 },
    )
    fun write(settings: QueueSettings) {
        preferences.edit().putString("tap", settings.songTap.name).putString("switch", settings.switching.name)
            .putInt("maximum", settings.maximum ?: 0).apply()
    }
}
