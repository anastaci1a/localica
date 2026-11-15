package io.github.anastaci1a.localica.ui.spec

/** iOS 26.6.2 _UIFlexInteraction springs/opacities; dp substitutes for pt. Small includes Ultra-small glow; response is seconds, damping is a ratio. */
internal data class FlexSpec(val damping: Float, val response: Float,
    val trackingDamping: Float, val trackingResponse: Float, val bigOpacity: Float = 1f, val littleOpacity: Float = 1f) {
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

/** Firmware responses/geometry; ideal Gaussian, Plus lift and polarity-selected dodge approximate backdrop-aware YCC (saturation omitted). */
internal object FlexGlow {
    const val Show = .1f
    const val Hide = .5f
    const val Follow = .15f
    const val Lift = .05f
    const val Travel = 50f
    val Dodge = listOf(.45f, .85f)
    val Phase = listOf(1f to 1f, .5f to 2f, 0f to 4f)
    const val ExtentScale = .75f
    const val ExtentCap = 80f
    val Falloff = listOf(.3935f, .3573f, .2671f, .1638f, .0819f, .0332f, .0108f, 0f).mapIndexed { i, alpha -> i / 7f to alpha }
}
