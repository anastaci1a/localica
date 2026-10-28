package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout

/** Optional origin shape; phase state survives visibility-keyed coroutine changes. */
@Stable
internal class GlassOriginPreparation {
    private var motion by mutableStateOf(Animatable(0f))
    private var phasedProgress by mutableStateOf<Float?>(null)
    val phase = LyricsSourcePhaseController()
    val progress: Float get() = phasedProgress ?: motion.value.coerceIn(0f, 1f)
    private var placedProgress = Float.NaN
    private var drawnProgress = Float.NaN
    var originReceipt by mutableIntStateOf(0)
        private set
    private var proofFlight: Pair<Long, Long>? = null
    private var placedReceipt = -1
    private var drawnReceipt = -1

    fun publish(frame: LyricsSourcePhaseController.Frame) { phasedProgress = frame.preparation }
    // Called by the real header's layout/draw modifiers with composition-captured
    // progress, not the mutable latest desired value.
    fun beginOriginProof(frame: LyricsSourcePhaseController.Frame) {
        val flight = frame.generation to frame.crossing
        if (proofFlight == flight) return
        proofFlight = flight
        originReceipt++
    }
    fun placed(progress: Float, receipt: Int) { placedProgress = progress; placedReceipt = receipt }
    fun drawn(progress: Float, receipt: Int) { drawnProgress = progress; drawnReceipt = receipt }
    fun preparedOriginDrawn(progress: Float) = placedProgress == progress && drawnProgress == progress &&
        placedReceipt == originReceipt && drawnReceipt == originReceipt

    suspend fun settle(prepared: Boolean, reduced: Boolean, speed: Float) {
        phasedProgress = null
        val target = if (prepared) 1f else 0f
        if (reduced) motion.snapTo(target)
        else motion.animateTo(target, glassMorphSpring(speed))
    }

    fun reset() {
        phase.reset()
        phasedProgress = null
        motion = Animatable(0f)
        placedProgress = Float.NaN
        drawnProgress = Float.NaN
        proofFlight = null
        originReceipt++
    }
}

@Composable
internal fun rememberGlassOriginPreparation() = remember { GlassOriginPreparation() }

/** Required for phase-driven origins, including test fixtures. Place on the real
 * header/origin container so the acknowledgment describes its captured geometry. */
internal fun Modifier.preparedOrigin(preparation: GlassOriginPreparation, progress: Float): Modifier {
    val receipt = preparation.originReceipt
    return layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, child.height) {
            child.place(0, 0)
            preparation.placed(progress, receipt)
        }
    }.drawWithContent {
        drawContent()
        preparation.drawn(progress, receipt)
    }
}
