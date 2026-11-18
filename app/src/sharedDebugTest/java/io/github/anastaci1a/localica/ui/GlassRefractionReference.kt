package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt

/** Test reference for the kernel's refraction law, from the shared GlassRefraction constants. */
internal object GlassRefractionReference {
    fun profile(amount: Float, height: Float, inside: Float): Float {
        val t = (inside / height).coerceIn(0f, 1f)
        return amount * (1f - sqrt(t * (2f - t)))
    }
    /** Lookup offsets in pixels, for [unit] pixels per dp. */
    fun at(field: LensField, unit: Float): LensRefraction = with(GlassRefraction) {
        val length = field.inward.getDistance()
        val inward = if (length > 0f) field.inward / length else Offset.Zero
        LensRefraction(inward * -profile(InnerAmount * unit, InnerHeight * unit, field.distance),
            inward * -profile(OuterAmount * unit, OuterHeight * unit, field.distance),
            OuterOpacity * ((-field.distance / unit - OuterDistance) / (OuterDistanceEnd - OuterDistance)).coerceIn(0f, 1f))
    }
}
internal data class LensRefraction(val inner: Offset, val outer: Offset, val outerWeight: Float)
