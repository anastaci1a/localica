package io.github.anastaci1a.localica.ui

import kotlin.math.exp
import kotlin.math.sqrt

/** Final physical extents, shared by either orientation; no raw strain/depth reconstruction. */
internal data class SelectorCrossFrame(val shell: Float, val lens: Float, val corner: Float = 0f, val outerCorner: Float = 0f) {
    init {
        require(shell.isFinite() && lens.isFinite() && shell > 0f && lens > 0f && lens <= shell)
        require(corner.isFinite() && outerCorner.isFinite() && corner >= 0f && outerCorner >= 0f)
    }

    fun toward(other: SelectorCrossFrame, fraction: Float): SelectorCrossFrame {
        val t = fraction.coerceIn(0f, 1f)
        if (t == 0f) return this
        if (t == 1f) return other
        return SelectorCrossFrame(shell + (other.shell - shell) * t, lens + (other.lens - lens) * t,
            corner + (other.corner - corner) * t, outerCorner + (other.outerCorner - outerCorner) * t)
    }
}

/**
 * One critical cross-axis return for the enclosing wall and its lens. Their common progress
 * preserves containment. Axial momentum and optical depth remain owned by GlassMomentum.
 * Release deliberately dissipates velocity outside the monotone return cone; it never snaps
 * the visible extents. The caller owns interruption and supplies motion-speed-scaled seconds.
 */
internal class SelectorCrossReturn(
    val start: SelectorCrossFrame,
    val rest: SelectorCrossFrame,
    shellVelocity: Float = 0f,
    lensVelocity: Float = 0f,
) {
    private val omega = sqrt(380f)
    private val shellDelta = start.shell - rest.shell
    private val lensDelta = start.lens - rest.lens
    private val squaredDistance = shellDelta * shellDelta + lensDelta * lensDelta +
        (start.corner - rest.corner) * (start.corner - rest.corner) +
        (start.outerCorner - rest.outerCorner) * (start.outerCorner - rest.outerCorner)
    internal val progressVelocity = if (squaredDistance == 0f) 0f else {
        val shellSpeed = shellVelocity.takeIf { it.isFinite() } ?: 0f
        val lensSpeed = lensVelocity.takeIf { it.isFinite() } ?: 0f
        ((shellSpeed * shellDelta + lensSpeed * lensDelta) / squaredDistance).coerceIn(-omega, 0f)
    }

    fun progress(seconds: Float): Float {
        require(seconds.isFinite() && seconds >= 0f)
        if (squaredDistance == 0f) return 0f
        return ((1f + (progressVelocity + omega) * seconds) * exp(-omega * seconds)).coerceIn(0f, 1f)
    }

    fun frame(seconds: Float): SelectorCrossFrame = rest.toward(start, progress(seconds))
}

