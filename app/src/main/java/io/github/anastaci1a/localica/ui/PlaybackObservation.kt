package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.PlaybackState

/** Metadata/transport changes still invalidate the screen; position samples belong to the player. */
internal class PlaybackObservation(source: State<PlaybackState>) {
    val screen: State<PlaybackState> = derivedStateOf(structuralEqualityPolicy()) { source.value.screenState() }
    val sample: () -> PlaybackState = { source.value }
}

@Composable
internal fun rememberPlaybackObservation(source: State<PlaybackState>): PlaybackObservation =
    remember(source) { PlaybackObservation(source) }

internal fun PlaybackState.screenState(): PlaybackState = copy(positionMs = 0, sampledAtMs = 0)

/** Queue admission may precede the native track update; preserve the existing pending-song display. */
internal fun playerDisplayState(playback: PlaybackState, current: Track?): PlaybackState =
    if (current != null && current.id != playback.trackId) playback.copy(
        trackId = current.id, trackUri = current.uri, title = current.title, artist = current.artist,
        albumArtist = current.albumArtist, album = current.album, fileName = current.fileName,
        durationMs = current.durationMs, positionMs = 0) else playback
