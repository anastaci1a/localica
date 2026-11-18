package io.github.anastaci1a.localica.ui.spec

/** iOS 26.6.2 _UIFlexInteraction springs/opacities; dp substitutes for pt. Small includes Ultra-small glow; response is seconds, damping is a ratio. */
internal data class FlexSpec(val damping: Float, val response: Float,
    val trackingDamping: Float, val trackingResponse: Float, val bigOpacity: Float = 1f, val littleOpacity: Float = 1f,
    val scaleGain: Float? = null) {
    companion object {
        val Small = FlexSpec(.375f, .4f, .625f, .262f)
        fun dynamic(longSide: Float, shortSide: Float = longSide): FlexSpec {
            if (longSide < 120f) return Small
            val t = ((longSide - 44f) / 116f).coerceIn(0f, 1f)
            val u = ((shortSide - 44f) / 116f).coerceIn(0f, 1f)
            return FlexSpec(.375f + .225f * t, .4f - .04f * t, .625f, .262f + .052f * t, 1f - u, .3f - .1f * u)
        }
    }
}

/** Responses are extracted iOS defaults; spatial values/filter (uniform SrcOver) are provisional. */
internal object FlexGlow {
    const val Show = .1f
    const val Hide = .5f
    const val Follow = .15f
    const val Gain = .12f
    const val ExtentScale = .75f
    const val ExtentCap = 80f
    val Falloff = listOf(0f to 1f, 1f to 0f)
}

internal object FlexPress {
    const val Hang = .22
    fun response(held: Boolean, large: Boolean) = if (large) .25 else if (held) .27 else .5
    fun damping(held: Boolean, large: Boolean) = if (large) 1.0 else if (held) .625 else .7
    fun impulse(held: Boolean, large: Boolean) = if (large) 0.0 else if (held) .032 else .016
    fun hang(drag: Double, elapsed: Double) = maxOf(0.0, Hang * drag - elapsed)
}
