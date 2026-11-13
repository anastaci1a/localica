package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.ExpansionSpec
import io.github.anastaci1a.localica.ui.spec.PopupKind

/** #920: a popup's one stored takeover phase (SPEC.md section 2); every takeover flag is a view of it. */
internal enum class PopupTakeover { Closed, Admitting, InWindow, Restoring }
/** The trigger's anchor stores the phase, so it outlives an owner rebuilt for a new kind; shared content reads it here. */
internal interface PopupTakeoverOwner { val takeover: PopupTakeover }

/** #854: the surface kind of the enclosing popup; retained faces inside it (rememberFaceExpansion) inherit its motion. */
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

/** #854: a popup's kind follows its placement and its trigger's role, which bind after the first composition, but it changes only
 * while the popup is not present. One owner then serves a whole presentation: its native gate coroutine, window attachment and faces
 * never hold a disposed owner, and a kind that settles while open (a menu first composed visible before its trigger is measured)
 * applies from the next presentation. */
internal class PopupKindLatch(private var committed: PopupKind) {
    fun settle(derived: PopupKind, present: Boolean): PopupKind = if (present) committed else derived
    fun commit(kind: PopupKind) { committed = kind }
}

/** The kind of [key]'s popup this composition: [derived] while not [present], else the kind its present owner was built with.
 * The latch commits only applied compositions. */
@Composable internal fun settledPopupKind(key: Any, derived: PopupKind, present: Boolean): PopupKind {
    val latch = remember(key) { PopupKindLatch(derived) }
    val kind = latch.settle(derived, present)
    SideEffect { latch.commit(kind) }
    return kind
}

/** The Engine owns construction, spec updates and disposal; hosts borrow its view. */
@Composable internal fun rememberPopupWindowMotion(key: Any, prepared: Boolean, quiet: Boolean, kind: PopupKind = PopupKind.Legacy): PopupWindowMotion {
    val clock = ownerClock()
    val spec = io.github.anastaci1a.localica.ui.spec.LocalUiSpec.current.spec.expansion
    // [kind] is settled (settledPopupKind): it changes only at closed rest, and a changed kind is a new owner, never a retarget.
    val motion = remember(clock, key, kind) { PopupWindowMotion(clock, spec, key, prepared, quiet, kind) }
    SideEffect { motion.updateSpec(spec) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
