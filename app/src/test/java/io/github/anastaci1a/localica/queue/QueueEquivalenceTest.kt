package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.library.TrackSort
import org.junit.Assert.*
import org.junit.Test

class QueueEquivalenceTest {
    private fun song(id: String) = Track("content://music/$id", id, id = id,
        fileName = "$id.mp3", relativePath = "$id.mp3")
    private val tracks = listOf(song("a"), song("b"), song("a"))
    private fun queue(name: String = "Album", tracks: List<Track> = this.tracks,
        origins: List<String?> = emptyList()) = QueueEdits.create(tracks, 0, name, origins)

    @Test fun `equivalence compares ordered duplicate identities rather than sets or new occurrence IDs`() {
        val existing = queue()
        val library = QueueLibrary(listOf(existing))
        assertSame(existing, QueueEdits.equivalent(library, tracks, "Album"))
        assertNotEquals(existing.entries.map { it.id }, queue().entries.map { it.id })
        assertNull(QueueEdits.equivalent(library, listOf(tracks[0], tracks[2], tracks[1]), "Album"))
        assertNull(QueueEdits.equivalent(library, tracks.take(2), "Album"))
        assertNull(QueueEdits.equivalent(library, tracks + tracks.last(), "Album"))
        assertNull(QueueEdits.equivalent(library, tracks.map { it.copy(id = "different:${it.id}") }, "Album"))
    }

    @Test fun `names are exact and never guessed from suffixes or normalized`() {
        for (name in listOf("album", " Album", "Album ", "Album (2)", "Renamed")) {
            assertNull(QueueEdits.equivalent(QueueLibrary(listOf(queue(name))), tracks, "Album"))
        }
        val suffixed = queue("Album (2)")
        assertSame(suffixed, QueueEdits.equivalent(QueueLibrary(listOf(suffixed)), tracks, "Album (2)"))
    }

    @Test fun `playlist origins distinguish each duplicate occurrence and retain unknown provenance`() {
        val origins = listOf("first-playlist", null, "second-playlist")
        val existing = queue(origins = origins)
        val library = QueueLibrary(listOf(existing))
        assertSame(existing, QueueEdits.equivalent(library, tracks, "Album", origins))
        assertNull(QueueEdits.equivalent(library, tracks, "Album", origins.reversed()))
        assertNull(QueueEdits.equivalent(library, tracks, "Album"))
        val unknown = queue()
        assertSame(unknown, QueueEdits.equivalent(QueueLibrary(listOf(unknown)), tracks, "Album", List(3) { null }))
    }

    @Test fun `live metadata statistics and access paths do not change song identity`() {
        val existing = queue()
        val updated = tracks.map { it.copy(title = "New title", artist = "New artist", playCount = 20,
            modifiedAt = 200, uri = "content://new-grant/${it.id}", relativePath = "new/${it.fileName}",
            locations = listOf(TrackLocation("new-root", "content://new-grant/${it.id}", it.fileName))) }
        assertSame(existing, QueueEdits.equivalent(QueueLibrary(listOf(existing)), updated, "Album"))
    }

    @Test fun `matching retains saved queue state without comparing playback to a fresh queue`() {
        val original = queue()
        val ids = original.entries.map { it.id }
        val journey = QueueJourney.start(ids, ids[0], JourneyModes(shuffle = true)).select(ids[1], ids)
        val existing = original.copy(currentEntryId = ids[1], positionMs = 1234, createdAt = 1,
            lastActivatedAt = 20, journey = journey, order = SavedOrder(ids.reversed(), TrackSort.Title, false))
        assertSame(existing, QueueEdits.equivalent(QueueLibrary(listOf(existing)), tracks, "Album"))
    }

    @Test fun `active equivalent wins otherwise first saved match and distinct queues remain untouched`() {
        val first = queue()
        val second = queue()
        val reordered = queue(tracks = listOf(tracks[0], tracks[2], tracks[1]))
        val queues = listOf(reordered, first, second)
        assertSame(second, QueueEdits.equivalent(QueueLibrary(queues, second.id), tracks, "Album"))
        assertSame(first, QueueEdits.equivalent(QueueLibrary(queues, reordered.id), tracks, "Album"))
        assertSame(first, QueueEdits.equivalent(QueueLibrary(queues), tracks, "Album"))
        assertEquals(listOf(reordered, first, second), queues)
    }
}
