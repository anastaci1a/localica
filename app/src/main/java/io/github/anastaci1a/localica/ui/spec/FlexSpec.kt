package io.github.anastaci1a.localica.ui.spec

import androidx.compose.ui.unit.dp

/** [distance] is the contact translation threshold in dp. */
internal data class FlexSpec(val damping: Float, val response: Float,
    val trackingDamping: Float, val trackingResponse: Float, val bigOpacity: Float = 1f, val littleOpacity: Float = 1f,
    val scaleGain: Float? = null, val lift: FlexPress = FlexPress(), val distance: Float = 2000f) {
    companion object {
        val Small = FlexSpec(.375f, .4f, .625f, .262f)
        fun dynamic(longSide: Float, shortSide: Float = longSide): FlexSpec {
            if (longSide < 120f) return Small
            val t = ((longSide - 44f) / 116f).coerceIn(0f, 1f)
            val u = ((shortSide.toDouble() - 44.0) / 116.0).coerceIn(0.0, 1.0)
            return FlexSpec(.375f + .225f * t, .4f - .04f * t, .625f, .262f + .052f * t, (1.0 - u).toFloat(), ((1.0 - u) * .3 + u * .2).toFloat(),
                lift = FlexPress.Large, distance = (6000.0 + 18000.0 * u).toFloat())
        }
    }
}

internal object FlexGlow {
    val ThumbSize = 28.dp
    val SwitchInset = 16.dp
    const val Show = .1f
    const val Hide = .5f
    const val Travel = 50f
    const val InitialResponse = .15f
    const val TrackingResponse = .0015f
    const val SmoothingResponse = 1f
    const val ExtentScale = .75f
    const val ExtentCap = 80f
    fun radius(shortPixels: Float, density: Float) = minOf(ExtentScale * shortPixels / density, ExtentCap) * density
}

internal data class FlexPress(val response: Double = .27, val damping: Double = .625, val impulse: Double = .032,
    val releaseResponse: Double = .5, val releaseDamping: Double = .7, val releaseImpulse: Double = .016,
    val hang: Double = Hang, val gain: Float = .12f) {
    companion object {
        const val Hang = .22
        val Large = FlexPress(.25, 1.0, 0.0, .25, 1.0, 0.0)
        val None = FlexPress(impulse = 0.0, releaseImpulse = 0.0, hang = 0.0, gain = 0f)
    }
}
