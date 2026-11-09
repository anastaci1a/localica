package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.spec.MarqueeSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

/** A finite reading pass on one ordinary observed MotionFloat and the existing root UiClock.
 * Reduced motion keeps this essential text traversal; entry/exit/controls retain their usual policy.
 * Pause joins the stock ControlOff halt on the same coordinate. There is no private frame loop. */
@Stable internal class ReadingMarquee(private val clock: UiClock, key: Any, range: Float,
    private val specification: MarqueeSpec, private val speedPxPerSecond: Float,
    private val pending: () -> Unit, private val completed: () -> Unit) {
    private val position = MotionFloat(clock, 0f, range, bounds = 0.0..range.toDouble(),
        key = key to "reading-offset", essentialReading = true)
    private var overflow = -1f
    private var paused = true
    private var complete = false
    private var disposed = false
    private var initialWaitDone = false
    private var waitRemaining = specification.pauseMs * 1_000_000L
    private var waitStart: Long? = null
    var waitingAllowed by mutableStateOf(false)
        private set
    val offset: Float get() = position.value()
    val finished: Boolean get() = complete

    init {
        require(speedPxPerSecond.isFinite() && speedPxPerSecond > 0)
        position.onRest = {
            if (!paused && initialWaitDone && position.target == overflow && !complete) {
                complete = true; completed()
            }
        }
    }

    fun configure(overflowPx: Float, paused: Boolean) {
        check(!disposed)
        require(overflowPx.isFinite() && overflowPx >= 0 && overflowPx <= position.range)
        val discovering = overflow < 0f
        val changed = overflow != overflowPx
        val wasPaused = this.paused
        overflow = overflowPx
        this.paused = paused
        if (overflow == 0f) {
            waitingAllowed = false
            if (position.target != 0f || !position.settled || position.value() != 0f) {
                if (complete) { complete = false; pending() }
                position.retarget(0f, motionProfileId(MotionProfile.ControlOff))
                return
            }
            if (!complete) { complete = true; completed() }
            return
        }
        // A replacement reader starts incomplete even if its predecessor completed this toast gate.
        // Revoke that old deadline on first overflow discovery, through the fenced callback.
        if ((discovering || complete) && (changed || abs(position.target - overflow) > 0f)) { complete = false; pending() }
        waitingAllowed = !paused && !complete && !initialWaitDone
        if (paused) {
            if (!wasPaused) position.retarget(position.sampleAt(clock.stampNow).value, motionProfileId(MotionProfile.ControlOff))
        } else if (initialWaitDone && !complete && (changed || wasPaused || position.settled)) startReading()
    }

    fun waitStarted(): Long {
        check(!disposed)
        waitStart = clock.stampNow.nanos
        return waitRemaining
    }
    fun waitCancelled() {
        if (disposed) return
        waitStart?.let { waitRemaining = (waitRemaining - (clock.stampNow.nanos - it)).coerceAtLeast(0) }
        waitStart = null
    }
    fun waitFinished() {
        if (disposed) return
        waitRemaining = 0; waitStart = null; initialWaitDone = true; waitingAllowed = false
        if (!paused && overflow > 0f && !complete) startReading()
    }
    private fun startReading() {
        val stamp = clock.stampNow
        val from = position.sampleAt(stamp)
        val duration = maxOf(specification.returnMs / 1000.0, abs(overflow - from.value) / speedPxPerSecond.toDouble())
        val next = Eased(from.value.toDouble(), from.velocity.toDouble(), overflow.toDouble(), specification.easing,
            duration, bounds = 0.0..position.range.toDouble())
        position.commit(position.prepareSegment(next, overflow, stamp, specification))
    }
    fun dispose() { if (!disposed) { disposed = true; position.onRest = null; position.dispose() } }
}

@Composable internal fun rememberReadingMarquee(key: Any, fullTextWidth: Float, speedPxPerSecond: Float,
    pending: () -> Unit, completed: () -> Unit): ReadingMarquee {
    val clock = ownerClock()
    val specification = clock.spec.motion.getValue(MotionProfile.Marquee) as MarqueeSpec
    val onPending = rememberUpdatedState(pending); val onCompleted = rememberUpdatedState(completed)
    val owner = remember(clock, key, fullTextWidth, speedPxPerSecond, specification) {
        ReadingMarquee(clock, key, maxOf(1f, fullTextWidth), specification, speedPxPerSecond,
            { onPending.value() }, { onCompleted.value() })
    }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    LaunchedEffect(owner, owner.waitingAllowed) {
        if (owner.waitingAllowed) {
            try {
                delay(ceil(owner.waitStarted() / 1_000_000.0).toLong())
                owner.waitFinished()
            } finally { owner.waitCancelled() }
        }
    }
    return owner
}
