package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*

/** D30: a read-only analytic projection of the owner's phase and its U02-bound physical rectangle. */
@Stable internal class SurfacePhase(val expansion: ExpandCollapse,curve: Curve,val bounds: MotionRectView,ownership: Curve) {
    var curve by mutableStateOf(curve)
    var ownershipCurve by mutableStateOf(ownership)
    private val phase get()=expansion.phase
    val opacity=projection { this.curve }
    val ownership=projection { ownershipCurve }
    private fun projection(mapping: ()->Curve): MotionValue=object: MotionValue {
        private fun mapped(s: Kinematic)=Kinematic(mapping().at(s.value),mapping().slope(s.value)*s.velocity)
        override fun value()=mapping().at(phase.value())
        override fun velocity()=sample().velocity
        override fun sample()=mapped(phase.sample())
        override fun valueAt(stamp: FrameStamp)=mapping().at(phase.valueAt(stamp))
        override fun velocityAt(stamp: FrameStamp)=sampleAt(stamp).velocity
        override fun sampleAt(stamp: FrameStamp)=mapped(phase.sampleAt(stamp))
        override val target get()=mapping().at(phase.target)
        override val settled get()=expansion.retirementReady
    }
}
