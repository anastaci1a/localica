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
    val preparationCurve = Curve.window(0f, .4f)
    private val ranges = requireNotNull(clock.spec.geometry).maxViewportDiagonalPx.let { EdgeRanges(it, it, it, it) }
    private var rectangle by mutableStateOf<MotionRect?>(null)
    val bounds: MotionRectView? get() = rectangle
    private var shown = false
    private var openingEnvelope: Rect? = null
    init { expansion.nativeGate(if (prepared) .25f else 0f) }
    fun show(visible: Boolean) { shown = visible; if (rectangle != null) expansion.target(visible) }
    fun place(home: Rect, envelope: Rect, density: Float) {
        if (shown || openingEnvelope == null) openingEnvelope = envelope
        val basis = ExpansionBasis(home, requireNotNull(openingEnvelope), density)
        if (rectangle == null) {
            expansion.setBasis(basis)
            val rect = MotionRect(clock, home, ranges, expansion)
            expansion.bind(rect, geometryCurve)
            rectangle = rect
        } else expansion.setBasis(basis)
        expansion.target(shown)
    }
    fun updateSpec(spec: ExpansionSpec) = expansion.updateSpec(spec)
    fun dispose() = expansion.dispose()
}
