package io.github.anastaci1a.localica.ui.spec

import androidx.compose.runtime.Immutable
import kotlin.math.PI

/** The two joins of a smoothstep window over a unit phase (start < end); Engine maps it with `Curve.window`. */
@Immutable data class PhaseWindow(val start: Float, val end: Float) {
    init { require(start.isFinite() && end.isFinite() && start < end) { "window needs start < end" } }
}

/**
 * #640 hold-to-focus constants (DESIGN.md §1.2, §1.5, §2; operator answers H2/H3 of 2026-11-07).
 * Durations are milliseconds, lengths are dp, windows are fractions of the hold or card phase.
 * The lift point itself is the system touch-and-hold delay (H2), so it is read from `ViewConfiguration`, not stored here.
 * Spec holds data only: the maps live in `ui.engine` (`LogFollow`, `FocusReducer`).
 */
@Immutable data class FocusSpec(
    /** Nothing visible starts before this; taps (60–120 ms) never flash. */
    val deadZoneMs: Int = 100,
    /** Eased return after a tap, scroll yield or cancel; matches the hover lift. */
    val settleMs: Int = 220,
    /** Uniform growth of the lifted item, bounded by the viewport only. */
    val liftScale: Float = 1.04f,
    /** Lift amount as a window of hold progress h. */
    val lift: PhaseWindow = PhaseWindow(0f, 1f),
    /** Blur front sweep as a window of h; it follows the lift rather than flashing. */
    val blurFront: PhaseWindow = PhaseWindow(.15f, 1f),
    /** Blur strength as a window of h. */
    val blurStrength: PhaseWindow = PhaseWindow(0f, .6f),
    /** Card-only children fade on this window of the card phase. */
    val optionFade: PhaseWindow = PhaseWindow(.35f, 1f),
    /** Stagger between option groups. */
    val optionStaggerMs: Int = 20,
    /** k of the immutable-list follow o(d) = k·ln(1 + d/k) (H3, committed). */
    val followScaleDp: Float = 12f,
    /** The fallback k the issue asks to evaluate against on device. */
    val followFallbackScaleDp: Float = 8f,
    /** Spring-back of the followed item: `Damped` with this response and damping ratio. */
    val followSpringResponseSeconds: Float = .35f,
    val followSpringDampingRatio: Float = .8f,
    /** Mutable lists: blur fades out over [slop, slop + this] of drag distance. */
    val dragBlurFadeDp: Float = 24f,
    /** Blur mask feather F beyond the lifted item's distance field. */
    val maskFeatherDp: Float = 64f,
    /** Dim veil under the blur: light and dark theme (estimates, see DESIGN.md §2.1). */
    val dimLight: Float = .10f,
    val dimDark: Float = .25f,
    /** Scene capture scale and the three pyramid levels (effective dp at full resolution). */
    val captureScale: Float = .25f,
    val pyramidSigmasDp: List<Float> = listOf(4f, 10f, 20f),
    /** Swipe-down dismiss: fraction of card height, or velocity in dp/s. */
    val swipeDismissFraction: Float = .25f,
    val swipeDismissVelocityDpPerSecond: Float = 800f,
) {
    init {
        require(deadZoneMs in 0..1000 && settleMs in 16..2000 && optionStaggerMs in 0..200) { "durations" }
        require(liftScale.isFinite() && liftScale >= 1f && liftScale <= 1.5f) { "liftScale" }
        require(followScaleDp.isFinite() && followScaleDp > 0f && followFallbackScaleDp.isFinite() && followFallbackScaleDp > 0f) { "follow scale" }
        require(followSpringResponseSeconds.isFinite() && followSpringResponseSeconds > 0f && followSpringDampingRatio.isFinite() && followSpringDampingRatio > 0f) { "follow spring" }
        require(dragBlurFadeDp.isFinite() && dragBlurFadeDp > 0f && maskFeatherDp.isFinite() && maskFeatherDp > 0f) { "blur distances" }
        require(dimLight in 0f..1f && dimDark in 0f..1f) { "dim" }
        require(captureScale.isFinite() && captureScale > 0f && captureScale <= 1f) { "captureScale" }
        require(pyramidSigmasDp.isNotEmpty() && pyramidSigmasDp.all { it.isFinite() && it > 0f } && pyramidSigmasDp.zipWithNext().all { (a, b) -> a < b }) { "pyramid sigmas ascend" }
        require(swipeDismissFraction in 0f..1f && swipeDismissVelocityDpPerSecond.isFinite() && swipeDismissVelocityDpPerSecond > 0f) { "swipe dismiss" }
    }

    /** Stiffness of the follow spring-back for a unit mass: ω² with ω = 2π / response. */
    val followSpringStiffness: Float get() = ((2 * PI / followSpringResponseSeconds) * (2 * PI / followSpringResponseSeconds)).toFloat()
}

val StockFocusSpec: FocusSpec = FocusSpec()
