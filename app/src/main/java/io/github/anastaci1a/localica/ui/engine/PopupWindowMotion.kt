package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.ExpansionSpec

/** The native presenter owns one expansion and one measured rectangle. A native
 * receipt releases its gate; changing the envelope rebases those same edges. */
@Stable internal class PopupWindowMotion(
    private val clock: UiClock, spec: ExpansionSpec, key: Any, prepared: Boolean, quiet: Boolean
) {
    val expansion = ExpandCollapse(clock, spec, false, key)
    val geometryCurve = if (prepared) Curve.window(.25f, 1f) else if (quiet) Curve.window(.14f, 1f) else Curve.linear(0f, 1f)
    val popupCurve = if (prepared) geometryCurve else Curve.linear(0f, 1f)
    val quietStrength=Curve.window(0f,.14f)
    val quietContent=Curve.window(0f,.25f)
    val foreground=Curve.window(.18f,.83f)
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
    init { expansion.nativeGate(if (prepared) .25f else 0f) }
    fun attached(window: Any, host: ShadowPlane) {
        nativeHost = java.lang.ref.WeakReference(host)
        if (nativeWindow?.get() !== window) {
            firstNativeMeasurement = rectangle != null
            nativeWindow = java.lang.ref.WeakReference(window)
        }
    }
    fun show(visible: Boolean) { shown = visible; if (rectangle != null) expansion.target(visible) }
    fun hold() { holding = true; if (rectangle != null) expansion.hold() }
    fun release() { holding = false }
    fun place(home: Rect, envelope: Rect, density: Float) {
        if (shown || openingEnvelope == null) openingEnvelope = envelope
        val basis = ExpansionBasis(home, requireNotNull(openingEnvelope), density)
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
    fun updateSpec(spec: ExpansionSpec) = expansion.updateSpec(spec)
    fun dispose() = expansion.dispose()
}

/** The Engine owns construction, spec updates and disposal; hosts borrow its view. */
@Composable internal fun rememberPopupWindowMotion(key: Any, prepared: Boolean, quiet: Boolean): PopupWindowMotion {
    val clock = ownerClock()
    val spec = io.github.anastaci1a.localica.ui.spec.LocalUiSpec.current.spec.expansion
    val motion = remember(clock, key) { PopupWindowMotion(clock, spec, key, prepared, quiet) }
    SideEffect { motion.updateSpec(spec) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
