package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*

/** D30: a read-only analytic projection of the owner's phase and its U02-bound physical rectangle. */
@Stable internal class SurfacePhase(val expansion: ExpandCollapse,curve: Curve,val bounds: MotionRectView) {
    var curve by mutableStateOf(curve)
    private val phase get()=expansion.phase
    val opacity=object: MotionValue {
        private fun mapped(s: Kinematic)=Kinematic(this@SurfacePhase.curve.at(s.value),this@SurfacePhase.curve.slope(s.value)*s.velocity)
        override fun value()=this@SurfacePhase.curve.at(phase.value())
        override fun velocity()=sample().velocity
        override fun sample()=mapped(phase.sample())
        override fun valueAt(stamp: FrameStamp)=this@SurfacePhase.curve.at(phase.valueAt(stamp))
        override fun velocityAt(stamp: FrameStamp)=sampleAt(stamp).velocity
        override fun sampleAt(stamp: FrameStamp)=mapped(phase.sampleAt(stamp))
        override val target get()=this@SurfacePhase.curve.at(phase.target)
        override val settled get()=expansion.retirementReady
    }
}
