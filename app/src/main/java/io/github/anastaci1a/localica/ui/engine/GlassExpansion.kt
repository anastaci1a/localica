package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*

/** Legacy contact readers borrow the popup phase; they never own its trajectory. */
@Stable internal class GlassExpansion {
    private var owner by mutableStateOf<ExpandCollapse?>(null)
    private var curve = Curve.linear(0f, 1f)
    val value: Float get() = owner?.let { curve.at(it.phase.value()).coerceIn(0f,1f) } ?: 0f
    val velocity: Float get() = owner?.let { curve.slope(it.phase.value()) * it.phase.velocity() } ?: 0f
    val targetValue: Float get() = owner?.let { curve.at(if (it.expanded) 1f else 0f) } ?: 0f
    val isRunning: Boolean get() = owner?.retirementReady == false
    fun bind(value: ExpandCollapse, mapping: Curve) { owner = value; curve = mapping }
    fun unbind(value: ExpandCollapse) { if (owner === value) { owner = null } }
}
