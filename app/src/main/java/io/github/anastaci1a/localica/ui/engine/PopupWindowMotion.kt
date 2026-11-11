package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.ExpansionSpec
import io.github.anastaci1a.localica.ui.spec.PopupKind
import io.github.anastaci1a.localica.ui.spec.springOvershoot

/** #854: the surface kind of the enclosing popup; retained faces and presences inside it inherit its motion. */
internal val LocalPopupKind = staticCompositionLocalOf { PopupKind.Legacy }

/** The native presenter owns one expansion and one measured rectangle. A native
 * receipt releases its gate; changing the envelope rebases those same edges. */
@Stable internal class PopupWindowMotion(
    private val clock: UiClock, spec: ExpansionSpec, key: Any, prepared: Boolean, quiet: Boolean, val kind: PopupKind = PopupKind.Legacy
) {
    val expansion = ExpandCollapse(clock, spec, false, key).also { it.usePopupLaw(clock.spec.popupMotion, kind) }
    private val model = clock.spec.popupMotion
    /** Whether this window runs the #854 spring model rather than the Legacy v0.33.21 popup law. */
    val spring = expansion.popupLaw != null
    private val gate = if (!prepared) 0f else if (spring) model.preparedRamp else .25f
    // #854 §1.5: prepared origins ramp C1 off the gate and pass the overshoot; quiet row menus leave the trigger at once.
    val geometryCurve = if (prepared) (if (spring) Curve.ramp(model.preparedRamp, model.rampBlend) else Curve.window(.25f, 1f))
        else if (quiet && !spring) Curve.window(.14f, 1f) else Curve.linear(0f, 1f)
    /** The largest reachable edge overshoot as a fraction of edge travel (§1.7): the geometry at the phase peak 1 + κ, so a
     * prepared ramp's gain is included. 0 for Legacy. */
    val overshoot = if (spring) geometryCurve.at(1f + springOvershoot(model.open.zeta.toDouble()).toFloat()) - 1f else 0f
    val popupCurve = if (prepared) geometryCurve else Curve.linear(0f, 1f)
    val quietStrength=Curve.window(0f,.14f)
    val quietContent=Curve.window(0f,.25f)
    val foreground=Curve.window(.18f,.83f)
    /** Detached Pages and anchorless dialogs fade their layer late in the same phase, following v0.33's perceived fade:
     * opaque above .85, visibly fading through the middle, and gone below .05. */
    val detachedOpacity=Curve.window(.05f,.85f)
    val homeWeight=Curve.constant(1f)+Curve.window(0f,.5f).scaled(-1f)
    val face=Curve.linear(1f,1f-1f/.55f)
    val preparationCurve = Curve.window(0f, .25f).scaled(.68359375f) +
        Curve.window(.25f, .75f).scaled(.31640625f)
    private val ranges = requireNotNull(clock.spec.geometry).maxViewportDiagonalPx.let { EdgeRanges(it, it, it, it) }
    private var rectangle by mutableStateOf<MotionRect?>(null)
    val bounds: MotionRectView? get() = rectangle
    private var shown = false
    private var holding = false
    private var openingEnvelope: Rect? = null
    private var nativeWindow: java.lang.ref.WeakReference<Any>? = null
    private var firstNativeMeasurement = false
    private var nativeHost: java.lang.ref.WeakReference<ShadowPlane>? = null
    init { expansion.nativeGate(gate) }
    fun attached(window: Any, host: ShadowPlane) {
        nativeHost = java.lang.ref.WeakReference(host)
        if (nativeWindow?.get() !== window) {
            firstNativeMeasurement = rectangle != null
            nativeWindow = java.lang.ref.WeakReference(window)
        }
    }
    fun show(visible: Boolean) { shown = visible; if (rectangle != null) expansion.target(visible) }
    fun hold() { holding = true; if (rectangle != null) expansion.hold() }
    fun release() { holding = false; expansion.release() }
    fun place(home: Rect, envelope: Rect, density: Float, detached: Boolean = false) {
        if (shown || openingEnvelope == null) openingEnvelope = envelope
        val capturedEnvelope = requireNotNull(openingEnvelope)
        // A detached window can present beyond its root viewport; its duration never exceeds the admitted travel.
        val basis = ExpansionBasis(home, capturedEnvelope, density, if (detached) minOf(maxOf(capturedEnvelope.width,
            capturedEnvelope.height), requireNotNull(clock.spec.geometry).maxViewportDiagonalPx) else null)
        if (rectangle == null) {
            expansion.setBasis(basis)
            val rect = MotionRect(clock, home, ranges, expansion)
            expansion.bind(rect, geometryCurve)
            rectangle = rect
        } else {
            val initialized = !holding && firstNativeMeasurement && nativeHost?.get()?.let { expansion.initializeNativeHome(basis, it) } == true
            if (!initialized) expansion.setBasis(basis)
        }
        firstNativeMeasurement = false
        expansion.target(shown)
        if (holding) expansion.hold()
    }
    fun updateSpeed(speed: Float) = expansion.updateSpeed(speed)
    fun updateSpec(spec: ExpansionSpec) = expansion.updateSpec(spec)
    fun dispose() = expansion.dispose()
}

/** The Engine owns construction, spec updates and disposal; hosts borrow its view. */
@Composable internal fun rememberPopupWindowMotion(key: Any, prepared: Boolean, quiet: Boolean, kind: PopupKind = PopupKind.Legacy): PopupWindowMotion {
    val clock = ownerClock()
    val spec = io.github.anastaci1a.localica.ui.spec.LocalUiSpec.current.spec.expansion
    // A kind follows its trigger's role, which binds after the first composition; a changed kind is a new owner, never a retarget.
    val motion = remember(clock, key, kind) { PopupWindowMotion(clock, spec, key, prepared, quiet, kind) }
    SideEffect { motion.updateSpec(spec) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
