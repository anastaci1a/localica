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
import io.github.anastaci1a.localica.playback.PlaybackProblem
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playback.SeekAcknowledgement
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/** A watchdog for rejected/unavailable seeks, never a delay on successful acknowledgement. */
internal const val SeekAcknowledgementTimeoutMs = 2_000L

/** A newer same-connection seek also supersedes this request if flow receipts coalesce. */
internal class PendingSeek(val positionMs: Long, val requestId: Long, val playbackVisit: String?,
    val conditions: SeekConditions? = null) {
    fun acknowledgedBy(receipt: SeekAcknowledgement?): Boolean = receipt != null &&
        receipt.requestId >= requestId && receipt.playbackVisit == playbackVisit
}

/** Duration, connection and problem state a committed seek was issued under. */
internal data class SeekConditions(val durationMs: Long?, val connected: Boolean, val problem: PlaybackProblem?)

internal fun PlaybackState.seekConditions() = SeekConditions(durationMs, connected, problem)

/** Finger preview and committed-but-unacknowledged playback have separate ownership. */
@Stable
internal class PlayerSeekPosition {
    var editing by mutableStateOf<Float?>(null)
        private set
    var pending by mutableStateOf<PendingSeek?>(null)
        private set

    // Range the finger value was measured in; a duration refinement rescales it so the
    // thumb stays at the same fraction (under the finger) instead of resetting.
    private var editingRange by mutableFloatStateOf(0f)

    private fun PlaybackState.range() = (durationMs ?: 0).coerceAtLeast(1).toFloat()

    private fun editingIn(range: Float): Float? = editing?.let {
        // Identical ranges must be exact: a float round trip could turn 95_000 into 94_999.
        if (editingRange <= 0f || editingRange == range) it
        else (it.toDouble() * range / editingRange).toFloat().coerceIn(0f, range)
    }

    /** Slider range for this frame. A drag keeps the range it began in: Slider rebuilds its state and
     *  restarts the drag recognizer when the range changes, which ends the gesture. */
    fun sliderRange(duration: Float): Float = if (editing != null && editingRange > 0f) editingRange else duration

    fun displayed(playback: PlaybackState): Float = editingIn(playback.range()) ?: pending
        ?.takeUnless { it.acknowledgedBy(playback.seekAcknowledgement) ||
            (it.conditions != null && it.conditions != playback.seekConditions()) }
        ?.positionMs?.toFloat() ?: playback.positionMs.toFloat()

    /** [range] is the slider range [value] is expressed in; 0 means do not rescale. */
    fun edit(value: Float, range: Float = 0f) { editing = value; editingRange = range }

    /** A transient duration, connection or problem change ends a committed handoff, never a live scrub. */
    fun conditionsChanged(playback: PlaybackState) {
        if (pending?.conditions?.let { it != playback.seekConditions() } == true) pending = null
    }

    fun finish(playback: PlaybackState, seek: (Long) -> Long?) {
        val range = playback.range()
        val value = editingIn(range) ?: return
        val selected = if (editingRange > 0f && editingRange != range) value.roundToLong() else value.toLong()
        // Keep the finger position until the request identity is returned; dispatch
        // and installation run in one Main turn, even for synchronous test receipts.
        try {
            pending = seek(selected)?.let { PendingSeek(selected, it, playback.playbackVisit, playback.seekConditions()) }
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
    // Only media identity recreates this; connection, duration and problem updates must not
    // reset a scrub (they only end a committed handoff, see conditionsChanged).
    val position = remember(playback.trackId, playback.trackUri, playback.playbackVisit, playbackVisit) { PlayerSeekPosition() }
    val pending = position.pending
    SideEffect { position.observe(playback.seekAcknowledgement); position.conditionsChanged(playback) }
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
        val range = position.sliderRange(duration)
        val shown = position.displayed(playback).coerceIn(0f, duration)
        // The slider works in its (possibly frozen) range; the label always reads in real time.
        val value = (position.editing ?: shown).coerceIn(0f, range)
        PlayerSeekBar(value, { position.edit(it, range) }, valueRange = 0f..range,
            onValueChangeFinished = { position.finish(playback) { target ->
                onSeekRequest?.invoke(target, playback.playbackVisit) ?: run { onSeek(target); null }
            } },
            enabled = position.editing != null || playback.connected && playback.problem == null && (playback.durationMs ?: 0) > 0,
            modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "Seek" },
            cancelBounds = cancelBounds, cancelTarget = cancelTarget, onCancel = position::cancel,
            liveValue = playback.positionMs.toFloat() * range / duration, onDrag = onDrag)
        val ink = LocalPlayerDrawTheme.current?.foreground
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (ink == null) {
                Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(playback.durationMs), style = MaterialTheme.typography.labelSmall)
            } else {
                DrawInkText(formatTime(shown.toLong()), ink, style = MaterialTheme.typography.labelSmall)
                DrawInkText(formatTime(playback.durationMs), ink, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
