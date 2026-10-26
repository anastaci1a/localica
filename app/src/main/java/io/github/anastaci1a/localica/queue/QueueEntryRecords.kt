package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot

/** Reuse only full-value-equal track encodings within this conversion; occurrences remain independent. */
internal fun queueEntryRecords(
    queueId: String,
    entries: List<QueueEntry>,
    encode: (Track) -> String = TrackSnapshot::encode,
): List<EntryRecord> {
    val encoded = HashMap<Track, String>()
    return entries.mapIndexed { ordinal, entry ->
        EntryRecord(entry.id, queueId, ordinal, encoded.getOrPut(entry.track) { encode(entry.track) },
            entry.sourcePlaylistId)
    }
}
