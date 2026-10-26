package io.github.anastaci1a.localica.queue

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal val MediaItem.queueVisit: String? get() = mediaMetadata.extras?.getString("queueVisit")
internal val MediaItem.listeningQueue: String? get() = mediaMetadata.extras?.getString("queueId")

/** Resolved locations stay reusable when a selection lies outside the bounded native timeline. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QueueTimeline {
    var queueId: String? = null
        private set
    private var templates = emptyMap<String, MediaItem>()
    private val decorated = mutableMapOf<String, MediaItem>()

    fun resolve(id: String, items: List<MediaItem>) {
        val next = items.associateBy { it.mediaId }
        if (queueId != id) decorated.clear()
        else decorated.entries.removeAll { (_, item) ->
            // A visit can survive physical reordering. Reuse its decoration only
            // while the exact resolved occurrence template is still authoritative.
            templates[item.mediaId] == null || templates[item.mediaId] !== next[item.mediaId]
        }
        queueId = id
        templates = next
    }

    fun contains(queue: ListeningQueue) = queueId == queue.id && queue.entries.all { it.id in templates }

    fun sync(player: Player, queue: ListeningQueue, seek: Boolean, replace: Boolean,
        window: List<QueueVisit> = requireNotNull(queue.journey).window()): Boolean {
        val journey = requireNotNull(queue.journey)
        val wanted = window.map { it.token }.toSet()
        decorated.keys.retainAll(wanted)
        val items = window.map { visit -> decorated.getOrPut(visit.token) {
            val item = templates.getValue(visit.entryId)
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                putString("queueId", queue.id)
                putString("queueVisit", visit.token)
            }
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        } }
        val index = window.indexOfFirst { it.token == journey.current?.token }.coerceAtLeast(0)
        if (replace) {
            player.setMediaItems(items, index, queue.positionMs)
            player.prepare()
            return true
        }
        var changed = false
        for (old in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(old).queueVisit !in wanted) {
                player.removeMediaItem(old)
                changed = true
            }
        }
        items.forEachIndexed { desired, item ->
            val existing = if (desired < player.mediaItemCount && player.getMediaItemAt(desired).queueVisit == item.queueVisit) desired
                else (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).queueVisit == item.queueVisit }
            if (existing == null) { player.addMediaItem(desired, item); changed = true }
            else {
                if (existing != desired) { player.moveMediaItem(existing, desired); changed = true }
                val old = player.getMediaItemAt(desired)
                if (old.localConfiguration?.uri != item.localConfiguration?.uri || old.mediaMetadata != item.mediaMetadata) {
                    player.replaceMediaItem(desired, item)
                    changed = true
                }
            }
        }
        if (seek || player.currentMediaItem?.queueVisit != journey.current?.token) player.seekTo(index, queue.positionMs)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        return changed
    }
}
