package io.github.anastaci1a.localica.ui.spec

/** iOS 26.6.2 _UIFlexInteraction dynamic springs; response is seconds, damping is a ratio. */
internal data class FlexSpec(val damping: Float, val response: Float,
    val trackingDamping: Float, val trackingResponse: Float) {
    companion object {
        val Small = FlexSpec(.375f, .4f, .625f, .262f)
        fun dynamic(longSide: Float): FlexSpec {
            if (longSide < 120f) return Small
            val t = ((longSide - 44f) / 116f).coerceIn(0f, 1f)
            return FlexSpec(.375f + .225f * t, .4f - .04f * t, .625f, .262f + .052f * t)
        }
    }
}
