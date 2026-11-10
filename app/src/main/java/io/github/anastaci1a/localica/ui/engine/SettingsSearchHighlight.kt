package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*

/** The incoming settings pulse uses Compose's exact tween evaluator, without a Compose animation driver.
 * All phase boundaries, hold deadlines and retirement belong to the window's registered Engine owner.
 * Faster retains the incoming timing. Entering Reduced cancels admitted work without restarting it.
 */
@Stable
internal class SettingsSearchHighlight(private val clock: UiClock) : State<Float>, ActiveMotion {
    private enum class Phase { Idle, AwaitOnset, Onset, Hold, AwaitFade, Fade }
    private var phase by mutableStateOf(Phase.Idle)
    private var started = clock.stampNow
    private var admittedReduced = false
    private var reduced = false
    private var disposed = false
    private val onset = TargetBasedAnimation(tween<Float>(150), Float.VectorConverter, 0f, .18f)
    private val fade = TargetBasedAnimation(tween<Float>(600), Float.VectorConverter, .18f, 0f)
    override val id = clock.allocateId()
    override var presented = false
        private set
    override val settled: Boolean get() = phase == Phase.Idle
    private fun elapsed(stamp: FrameStamp) = (stamp.nanos - started.nanos).coerceAtLeast(0L)
    override val value: Float
        get() {
            presented = true
            return when (phase) {
                Phase.Idle, Phase.AwaitOnset -> 0f
                Phase.Onset -> onset.getValueFromNanos(elapsed(clock.stamp))
                Phase.Hold, Phase.AwaitFade -> .18f
                Phase.Fade -> fade.getValueFromNanos(elapsed(clock.stamp))
            }
        }
    fun start(reduced: Boolean) {
        clock.checkMutation()
        check(!disposed)
        admittedReduced = reduced
        started = clock.stampNow
        phase = if (reduced) Phase.Hold else Phase.AwaitOnset
        clock.register(this)
    }
    fun access(reduced: Boolean) {
        clock.checkMutation()
        if (reduced && !this.reduced && !settled) reduce(clock.stampNow)
        this.reduced = reduced
    }
    fun cancel() {
        clock.checkMutation()
        clock.unregister(this)
        phase = Phase.Idle
    }
    override fun endsAt(stamp: FrameStamp): Boolean = when (phase) {
        Phase.Idle -> false
        Phase.AwaitOnset, Phase.AwaitFade -> true
        Phase.Onset -> onset.isFinishedFromNanos(elapsed(stamp))
        Phase.Hold -> elapsed(stamp) >= if (admittedReduced) 1_200_000_000L else 450_000_000L
        Phase.Fade -> fade.isFinishedFromNanos(elapsed(stamp))
    }
    override fun finish(stamp: FrameStamp) {
        clock.checkMutation()
        phase = when (phase) {
            Phase.AwaitOnset -> Phase.Onset
            Phase.Onset -> Phase.Hold
            Phase.Hold -> if (admittedReduced) Phase.Idle else Phase.AwaitFade
            Phase.AwaitFade -> Phase.Fade
            Phase.Fade, Phase.Idle -> Phase.Idle
        }
        started = stamp
        if (!settled) clock.register(this)
    }
    override fun reduce(stamp: FrameStamp) = cancel()
    fun dispose() {
        cancel()
        disposed = true
    }
}

/** Highlight changes alone admit or cancel a pulse; policy changes never replay a retired pulse. */
@Composable
internal fun rememberSettingsSearchHighlight(
    highlighted: Boolean,
    reduced: Boolean,
    onHighlight: () -> Unit,
): State<Float> {
    val clock = LocalUiClock.current
    val owner = remember(clock) { SettingsSearchHighlight(clock) }
    val currentOnHighlight = rememberUpdatedState(onHighlight)
    SideEffect { owner.access(reduced) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            currentOnHighlight.value()
            owner.start(reduced)
        } else owner.cancel()
    }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return owner
}
