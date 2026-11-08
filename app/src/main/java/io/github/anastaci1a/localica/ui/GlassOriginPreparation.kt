package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout

/** Optional origin shape; phase state survives visibility-keyed coroutine changes. */
@Stable
internal class GlassOriginPreparation {
    private var phase by mutableStateOf<io.github.anastaci1a.localica.ui.engine.MotionValue?>(null)
    private var curve = io.github.anastaci1a.localica.ui.engine.Curve.window(0f, .4f)
    val progress: Float get() = phase?.let { curve.at(it.value()) } ?: 0f
    fun bind(value: io.github.anastaci1a.localica.ui.engine.MotionValue, mapping: io.github.anastaci1a.localica.ui.engine.Curve) { phase = value; curve = mapping }
    private var placedProgress = Float.NaN
    private var drawnProgress = Float.NaN
    var originReceipt by mutableIntStateOf(0)
        private set
    private var proofFlight: Long? = null
    private var placedReceipt = -1
    private var drawnReceipt = -1

    // Receipts describe the real origin at this gate generation, not an elapsed frame count.
    fun beginOriginProof(flight: Long) {
        if (proofFlight == flight) return
        proofFlight = flight
        originReceipt++
    }
    fun placed(progress: Float, receipt: Int) { placedProgress = progress; placedReceipt = receipt }
    fun drawn(progress: Float, receipt: Int) { drawnProgress = progress; drawnReceipt = receipt }
    fun preparedOriginDrawn(progress: Float) = placedProgress == progress && drawnProgress == progress &&
        placedReceipt == originReceipt && drawnReceipt == originReceipt

    fun reset() {
        phase = null
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
