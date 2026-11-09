package io.github.anastaci1a.localica.toast

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue

/** Build outcomes from committed occurrence changes, including duplicate tracks and new lists. */
object SongToastOutcomes {
    fun queue(before: ListeningQueue?, after: ListeningQueue, playNext: Boolean): ToastMessage? {
        require(before == null || before.id == after.id)
        val existing = before?.entries?.mapTo(hashSetOf()) { it.id }.orEmpty()
        val added = after.entries.filterNot { it.id in existing }
        if (added.isEmpty()) return null
        val command = ToastCommand.OpenQueue(after.id, added.first().id, playNext)
        return message(added.map { it.track }, if (playNext) "playing next" else "added to queue", command)
    }

    fun lists(before: List<ManualPlaylist>, after: List<ManualPlaylist>): List<ToastMessage> {
        val previous = before.associateBy { it.id }
        return after.mapNotNull { list ->
            val existing = previous[list.id]?.entries?.mapTo(hashSetOf()) { it.id }.orEmpty()
            val added = list.entries.filterNot { it.id in existing }
            if (added.isEmpty()) null else message(added.map { it.track }, "added to ${list.name}",
                ToastCommand.OpenList(list.id, added.first().id))
        }
    }

    private fun message(tracks: List<Track>, outcome: String, command: ToastCommand): ToastMessage {
        val subject = if (tracks.size == 1) tracks.single().title else "${tracks.size} songs"
        // Each operation carries an exact first occurrence. Repeated presentation of the SAME
        // committed operation can coalesce; a different append cannot acquire its destination.
        return ToastMessage("$subject $outcome", ToastKind.Success, tap = command,
            artworkUris = tracks.take(3).map { it.uri },
            coalescingKey = ToastKey("song-outcome", outcome, ToastKind.Success, command, null))
    }
}
