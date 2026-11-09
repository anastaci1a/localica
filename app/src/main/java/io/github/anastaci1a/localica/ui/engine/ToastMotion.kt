package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import kotlin.math.max
import kotlin.math.min

internal enum class ToastMotionEndpoint { Visible, Exited }
internal data class ToastButtonProjection(val alpha: Float, val offsetY: Float, val uncovered: Boolean)

/** Spatial projection only. Finite overlap also handles a release trajectory passing the resting
 * leading edge; original control bounds never include their projected downward displacement. */
internal fun toastButtonProjection(toast: Rect, original: Rect, downwardTravelPx: Float): ToastButtonProjection {
    require(toast.width > 0 && original.width > 0 && downwardTravelPx.isFinite() && downwardTravelPx >= 0)
    val overlap = (min(toast.right, original.right) - max(toast.left, original.left)).coerceAtLeast(0f)
    val covered = (overlap / original.width).coerceIn(0f, 1f)
    return ToastButtonProjection(1f - covered, downwardTravelPx * covered, covered == 0f)
}

/** One physical horizontal coordinate, owned by the existing Activity UiClock. Entry, input,
 * reversal and release all join this same MotionFloat; no button has a trajectory or timer. */
@Stable internal class ToastMotion(
    private val clock: UiClock,
    key: Any,
    initialBodyWidthPx: Float,
    initialRestingLeftPx: Float,
    private var shadowClearancePx: Float,
    private val endpoint: (ToastMotionEndpoint) -> Unit,
) {
    private var bodyWidthPx by mutableFloatStateOf(initialBodyWidthPx)
    private var restingLeftPx by mutableFloatStateOf(initialRestingLeftPx)
    private val hiddenLeftPx get() = -bodyWidthPx - shadowClearancePx
    private val profile = motionProfileId(MotionProfile.Floating)
    private val position = MotionFloat(clock, hiddenLeftPx,
        requireNotNull(clock.spec.geometry).maxViewportDiagonalPx, key = key to "toast-left")
    private var exiting = false
    private var contacting = false
    val settled: Boolean get() = position.settled
    val leftPx: Float get() = position.value()
    val widthPx: Float get() = bodyWidthPx

    init {
        require(initialBodyWidthPx.isFinite() && initialBodyWidthPx > 0)
        require(initialRestingLeftPx.isFinite() && shadowClearancePx.isFinite() && shadowClearancePx >= 0)
        position.onRest = { if (!contacting) endpoint(if (exiting) ToastMotionEndpoint.Exited else ToastMotionEndpoint.Visible) }
    }

    /** Root geometry changes keep the physical x and velocity; only the existing destination moves. */
    fun place(widthPx: Float, restingLeftPx: Float, shadowClearancePx: Float = this.shadowClearancePx) {
        require(widthPx.isFinite() && widthPx > 0 && restingLeftPx.isFinite())
        require(shadowClearancePx.isFinite() && shadowClearancePx >= 0)
        if (bodyWidthPx == widthPx && this.restingLeftPx == restingLeftPx && this.shadowClearancePx == shadowClearancePx) return
        bodyWidthPx = widthPx
        this.restingLeftPx = restingLeftPx
        this.shadowClearancePx = shadowClearancePx
        if (!contacting) position.retarget(if (exiting) hiddenLeftPx else restingLeftPx, profile)
    }

    fun enter() { if (!exiting && !contacting) position.retarget(restingLeftPx, profile) }
    fun exit() {
        if (exiting) return
        val alreadyExited = position.settled && position.target == hiddenLeftPx
        exiting = true
        contacting = false
        if (alreadyExited) endpoint(ToastMotionEndpoint.Exited)
        else position.retarget(hiddenLeftPx, profile)
    }

    /** Call after the queue invalidates its dwell ticket, before awaiting horizontal slop. */
    fun contact(): Boolean {
        if (exiting || contacting) return false
        contacting = true
        val stamp = clock.stampNow
        position.drive(position.sampleAt(stamp).value, stamp)
        return true
    }

    fun dragBy(deltaPx: Float) {
        require(deltaPx.isFinite())
        if (!contacting || exiting) return
        val stamp = clock.stampNow
        val value = (position.sampleAt(stamp).value + deltaPx).coerceIn(hiddenLeftPx, restingLeftPx)
        position.drive(value, stamp)
    }

    /** The shared input adapter decides dismissal from its measured distance/velocity policy. */
    fun release(dismiss: Boolean, velocityPxPerSecond: Float) {
        require(velocityPxPerSecond.isFinite())
        if (!contacting || exiting) return
        contacting = false
        exiting = dismiss
        // Root Reduced policy can settle a driven coordinate while contact remains held.
        // Rejoin direct input on that same owner before accepting the release velocity.
        if (position.settled) {
            val stamp = clock.stampNow
            position.drive(position.sampleAt(stamp).value, stamp)
        }
        position.release(if (dismiss) hiddenLeftPx else restingLeftPx, profile, velocityPxPerSecond)
    }

    fun bodyAt(bottomPx: Float, heightPx: Float): Rect {
        require(bottomPx.isFinite() && heightPx.isFinite() && heightPx > 0)
        return Rect(leftPx, bottomPx - heightPx, leftPx + bodyWidthPx, bottomPx)
    }

    fun dispose() = position.dispose()
}

/** Components borrow the owner; construction, root clock lookup and disposal stay in Engine. */
@Composable internal fun rememberToastMotion(key: Any, widthPx: Float, restingLeftPx: Float,
    shadowClearancePx: Float, endpoint: (ToastMotionEndpoint) -> Unit): ToastMotion {
    val clock = ownerClock()
    val callback = rememberUpdatedState(endpoint)
    val motion = remember(clock, key) {
        ToastMotion(clock, key, widthPx, restingLeftPx, shadowClearancePx) { callback.value(it) }
    }
    SideEffect { motion.place(widthPx, restingLeftPx, shadowClearancePx) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
