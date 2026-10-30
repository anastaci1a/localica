package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackObservationPolicyTest {
    private val state = PlaybackState(connected = true, trackUri = "native-uri", trackId = "native-id",
        title = "Native", artist = "Artist", album = "Album", albumArtist = "Album artist", fileName = "native.mp3",
        positionMs = 12345, durationMs = 90000, isPlaying = true, isBuffering = true, ended = true,
        problem = PlaybackProblem.Failed, hasNext = true, playWhenReady = true, sampledAtMs = 67890,
        playbackSpeed = 1.25f, seekAcknowledgement = SeekAcknowledgement(8, 12345, "visit", false), playbackVisit = "visit")

    @Test fun onlyPositionAndSampleTimeAreRemovedFromScreenObservation() {
        val projected = state.screenState()
        assertEquals(state.copy(positionMs = 0, sampledAtMs = 0), projected)
        assertEquals(projected, state.copy(positionMs = 45678, sampledAtMs = 78901).screenState())
        val changes = listOf(state.copy(connected = false), state.copy(trackUri = "other-uri"),
            state.copy(trackId = "other-id"), state.copy(title = "Other"), state.copy(artist = null),
            state.copy(album = null), state.copy(albumArtist = null), state.copy(fileName = "other.mp3"),
            state.copy(durationMs = null), state.copy(isPlaying = false), state.copy(isBuffering = false),
            state.copy(ended = false), state.copy(problem = null), state.copy(hasNext = false),
            state.copy(playWhenReady = false), state.copy(playbackSpeed = .75f),
            state.copy(seekAcknowledgement = SeekAcknowledgement(9, 54321, "new-visit", true)),
            state.copy(playbackVisit = "new-visit"))
        changes.forEach { assertNotEquals(projected, it.screenState()) }
        assertEquals(12345L, state.positionMs)
        assertEquals(67890L, state.sampledAtMs)
    }

    @Test fun admittedQueueTrackKeepsOriginalPendingMetadataAndNativeReceiptRules() {
        val current = Track("queue-uri", "Queue title", artist = "Queue artist", album = "Queue album",
            albumArtist = "Queue album artist", fileName = "queue.mp3", relativePath = "queue.mp3",
            id = "queue-id", durationMs = 43210)
        val expected = state.copy(trackId = current.id, trackUri = current.uri, title = current.title,
            artist = current.artist, albumArtist = current.albumArtist, album = current.album,
            fileName = current.fileName, durationMs = current.durationMs, positionMs = 0)
        assertEquals(expected, playerDisplayState(state, current))
        assertSame(state.seekAcknowledgement, playerDisplayState(state, current).seekAcknowledgement)
        assertEquals(expected.copy(sampledAtMs = 99999), playerDisplayState(state.copy(positionMs = 888, sampledAtMs = 99999), current))
        assertSame(state, playerDisplayState(state, null))
        assertSame(state, playerDisplayState(state, current.copy(id = state.trackId!!)))
    }
}
