package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playback.SeekAcknowledgement
import kotlinx.coroutines.delay

/** A watchdog for rejected/unavailable seeks, never a delay on successful acknowledgement. */
internal const val SeekAcknowledgementTimeoutMs = 2_000L

/** A newer same-connection seek also supersedes this request if flow receipts coalesce. */
internal class PendingSeek(val positionMs: Long, val requestId: Long, val playbackVisit: String?) {
    fun acknowledgedBy(receipt: SeekAcknowledgement?): Boolean = receipt != null &&
        receipt.requestId >= requestId && receipt.playbackVisit == playbackVisit
}

/** Finger preview and committed-but-unacknowledged playback have separate ownership. */
@Stable
internal class PlayerSeekPosition {
    var editing by mutableStateOf<Float?>(null)
        private set
    var pending by mutableStateOf<PendingSeek?>(null)
        private set

    fun displayed(playback: PlaybackState): Float = editing ?: pending
        ?.takeUnless { it.acknowledgedBy(playback.seekAcknowledgement) }
        ?.positionMs?.toFloat() ?: playback.positionMs.toFloat()

    fun edit(value: Float) { editing = value }

    fun finish(playback: PlaybackState, seek: (Long) -> Long?) {
        val selected = editing?.toLong() ?: return
        // Keep the finger position until the request identity is returned; dispatch
        // and installation run in one Main turn, even for synchronous test receipts.
        try {
            pending = seek(selected)?.let { PendingSeek(selected, it, playback.playbackVisit) }
        } finally {
            editing = null
        }
    }

    fun observe(receipt: SeekAcknowledgement?) {
        if (pending?.acknowledgedBy(receipt) == true) pending = null
    }

    fun expire(expected: PendingSeek) { if (pending === expected) pending = null }
    // Cancelling a newer contact must not revoke an earlier committed seek.
    fun cancel() { editing = null }
}

/** Keep the committed handoff alive while the seek row is temporarily out of view. */
@Composable
internal fun rememberPlayerSeekPosition(playback: PlaybackState, playbackVisit: String? = null): PlayerSeekPosition {
    val position = remember(playback.trackId, playback.trackUri, playback.playbackVisit, playbackVisit,
        playback.durationMs, playback.connected, playback.problem) { PlayerSeekPosition() }
    val pending = position.pending
    SideEffect { position.observe(playback.seekAcknowledgement) }
    LaunchedEffect(position, pending) {
        if (pending != null) {
            delay(SeekAcknowledgementTimeoutMs)
            position.expire(pending)
        }
    }
    return position
}

/** Share one visual position between Slider, its accessible value, and the elapsed label. */
@Composable
internal fun PlayerSeekControls(playback: PlaybackState, onSeek: (Long) -> Unit,
    playbackVisit: String? = null, cancelBounds: Rect? = null,
    onDrag: (Boolean, Boolean) -> Unit = { _, _ -> },
    onSeekRequest: ((Long, String?) -> Long)? = null,
    position: PlayerSeekPosition = rememberPlayerSeekPosition(playback, playbackVisit),
    cancelTarget: (() -> Rect?)? = null) {
    // A same-file repeat/duplicate visit is still a new playback generation. Recreate
    // the gesture too: a trailing callback from the old media must not seek the new one.
    key(position) {
        val duration = (playback.durationMs ?: 0).coerceAtLeast(1).toFloat()
        val value = position.displayed(playback).coerceIn(0f, duration)
        PlayerSeekBar(value, position::edit, valueRange = 0f..duration,
            onValueChangeFinished = { position.finish(playback) { target ->
                onSeekRequest?.invoke(target, playback.playbackVisit) ?: run { onSeek(target); null }
            } },
            enabled = playback.connected && playback.problem == null && (playback.durationMs ?: 0) > 0,
            modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" },
            cancelBounds = cancelBounds, cancelTarget = cancelTarget, onCancel = position::cancel,
            liveValue = playback.positionMs.toFloat(), onDrag = onDrag)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(value.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(playback.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}
