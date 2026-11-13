package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.Step0Trace
import io.github.anastaci1a.localica.ui.step0
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout

/** Optional origin shape; phase state survives visibility-keyed coroutine changes. */
@Stable
internal class GlassOriginPreparation(private val projection: io.github.anastaci1a.localica.ui.engine.OriginPhaseProjection) {
    val progress: Float get()=projection.value
    val separated: Boolean get()=projection.separated
    val active: Boolean get()=projection.active
    fun captured(separated: Boolean)=projection.captured(separated)
    fun bind(value: io.github.anastaci1a.localica.ui.engine.MotionValue,mapping: io.github.anastaci1a.localica.ui.engine.Curve)=projection.bind(value,mapping)
    private var placedProgress by Step0Trace.plain(this, "placedProgress", Float.NaN)
    private var drawnProgress by Step0Trace.plain(this, "drawnProgress", Float.NaN)
    var originReceipt by mutableIntStateOf(0).step0(this, "originReceipt")
        private set
    private var proofFlight: Long? by Step0Trace.plain<Long?>(this, "proofFlight", null)
    private var placedReceipt by Step0Trace.plain(this, "placedReceipt", -1)
    private var drawnReceipt by Step0Trace.plain(this, "drawnReceipt", -1)
    private var presentedReceipt by mutableIntStateOf(-1).step0(this, "presentedReceipt")
    private var presentedProgress by Step0Trace.plain(this, "presentedProgress", Float.NaN)

    // Receipts describe the real origin at this gate generation, not an elapsed frame count.
    fun beginOriginProof(flight: Long) {
        if (proofFlight == flight) return
        proofFlight = flight
        originReceipt++
    }
    fun placed(progress: Float, receipt: Int) { placedProgress = progress; placedReceipt = receipt }
    fun drawn(progress: Float, receipt: Int) { drawnProgress = progress; drawnReceipt = receipt }
    fun presented(progress: Float, receipt: Int) {
        if (placedProgress == progress && drawnProgress == progress && placedReceipt == receipt && drawnReceipt == receipt) {
            presentedProgress=progress; presentedReceipt=receipt
        }
    }
    fun preparedOriginDrawn(progress: Float) = presentedReceipt == originReceipt && presentedProgress == progress
    suspend fun awaitDrawn(mounted: ()->Boolean={ true }) {
        snapshotFlow { !mounted() || preparedOriginDrawn(progress) }.first { it }
    }

    fun reset() {
        projection.clear()
        placedProgress = Float.NaN
        drawnProgress = Float.NaN
        proofFlight = null
        originReceipt++
    }
}

@Composable
internal fun rememberGlassOriginPreparation(): GlassOriginPreparation {
    val projection=io.github.anastaci1a.localica.ui.engine.rememberOriginPhaseProjection()
    return remember(projection) { GlassOriginPreparation(projection) }
}

/** Required for phase-driven origins, including test fixtures. Place on the real
 * header/origin container so the acknowledgment describes its captured geometry. */
@Composable internal fun Modifier.preparedOrigin(preparation: GlassOriginPreparation, progress: Float): Modifier =
    preparedOrigin(preparation) { progress }

/** Capture this actual layout pass; a posted draw receipt publishes outside draw. */
@Composable internal fun Modifier.preparedOrigin(preparation: GlassOriginPreparation, progress: ()->Float): Modifier {
    val receipt=preparation.originReceipt
    val view=androidx.compose.ui.platform.LocalView.current
    val placed=remember(preparation) { floatArrayOf(Float.NaN) }
    return layout { measurable, constraints ->
        val sample=progress()
        val child=measurable.measure(constraints)
        layout(child.width, child.height) {
            child.place(0,0); placed[0]=sample; preparation.placed(sample,receipt)
        }
    }.drawWithContent {
        drawContent()
        val sample=placed[0]
        preparation.drawn(sample,receipt)
        view.post { preparation.presented(sample,receipt) }
    }
}
