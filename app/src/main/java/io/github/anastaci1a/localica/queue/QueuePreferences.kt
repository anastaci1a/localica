package io.github.anastaci1a.localica.queue

import android.content.Context
import io.github.anastaci1a.localica.library.SongListView

class QueuePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("queue-settings", Context.MODE_PRIVATE)
    fun read() = QueueSettings(
        songTap = SongTap.entries.firstOrNull { it.name == preferences.getString("tap", null) } ?: SongTap.NewQueue,
        switching = QueueSwitch.entries.firstOrNull { it.name == preferences.getString("switch-browse", null) } ?: QueueSwitch.Browse,
        tapOverrides = SongListView.entries.mapNotNull { view ->
            SongTap.entries.firstOrNull { it.name == preferences.getString("tap.${view.name}", null) }?.let { view to it }
        }.toMap(),
        maximum = preferences.getInt("maximum", 0).takeIf { it > 0 },
        preloadNext = preferences.getBoolean("preload-next", true),
    )
    fun write(settings: QueueSettings) {
        val edit = preferences.edit()
        SongListView.entries.forEach { edit.putString("tap.${it.name}", settings.tapOverrides[it]?.name) }
        edit.putString("tap", settings.songTap.name).putString("switch-browse", settings.switching.name)
            .putInt("maximum", settings.maximum ?: 0).putBoolean("preload-next", settings.preloadNext).apply()
    }
}
