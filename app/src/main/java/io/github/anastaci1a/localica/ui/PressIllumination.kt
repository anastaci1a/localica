package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.exp

/** Static opt-in photometric experiment, independent of lookup and gesture clocks.
 * The .16 peak is an engineering comparison bound, not an Apple measurement. */
internal data class PressIllumination(val amount: Float, val origin: Offset, val spread: Float) {
    init { require(amount.isFinite() && origin.x.isFinite() && origin.y.isFinite() && spread.isFinite()) }

    fun resolve(shape: LensGeometry): PressIllumination {
        val b = shape.bounds
        require(listOf(b.left, b.top, b.right, b.bottom, b.width, b.height).all { it.isFinite() } && b.width > 0f && b.height > 0f)
        val point = Offset(origin.x.coerceIn(b.left, b.right), origin.y.coerceIn(b.top, b.bottom))
        val radius = shape.corner.coerceIn(0f, shape.span)
        val core = Offset(point.x.coerceIn(b.left + radius, b.right - radius),
            point.y.coerceIn(b.top + radius, b.bottom - radius))
        val delta = point - core
        val length = delta.getDistance()
        val inside = if (length > radius && length > 0f) core + delta * (radius / length) else point
        return copy(amount = amount.coerceIn(0f, 1f), origin = inside,
            spread = spread.coerceIn(1f, minOf(2.0 * maxOf(b.width, b.height).coerceAtLeast(.5f),
                Float.MAX_VALUE.toDouble() / GradientExtent).toFloat()))
    }

    fun radialWeight(radiusInSpreads: Float): Float = Peak * amount * exp(-.5f * radiusInSpreads * radiusInSpreads)
    fun weight(point: Offset): Float = radialWeight(((point - origin) / spread).getDistance())
    fun channel(value: Float, endpoint: Float, point: Offset): Float = value + (endpoint - value) * weight(point)

    companion object {
        const val Peak = .16f
        const val GradientSteps = 64
        const val GradientExtent = 4f
    }
}

internal val PressIlluminationShader = """
    uniform float4 pressLight;
    float3 pressColor(float3 color, float2 p, float endpoint) {
        float2 delta = (p - pressLight.yz) / pressLight.w;
        float weight = ${PressIllumination.Peak} * pressLight.x * exp(-0.5 * dot(delta, delta));
        return mix(color, float3(endpoint), weight);
    }
""".trimIndent()
