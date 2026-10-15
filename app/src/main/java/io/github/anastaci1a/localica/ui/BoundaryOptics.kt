package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

/** Experimental inverse lookup, opt-in only. This is a bounded comparison model,
 * not an inferred Apple shader. Distances use the actual rounded outline, unlike
 * the default membrane field. Scattering and illumination are independent.
 *
 * The unmeasured outer band uses a smooth constant continuation. Its normalized
 * width .15 corresponds to 8.55 px for the measured 57 px capsule radius; other
 * sizes are an explicit scale hypothesis. No fit is claimed inside that band.
 */
internal data class BoundaryOptics(
    val strength: Float = .14f,
    val falloff: Float = .5f,
    val coreStart: Float = .3f,
    val coreEnd: Float = .65f,
    val guard: Float = .15f,
    val softness: Float = .01f,
) {
    init {
        require(listOf(strength, falloff, coreStart, coreEnd, guard, softness).all { it.isFinite() })
        require(strength >= 0f && falloff > 0f && coreStart > 0f && coreEnd > coreStart && coreEnd < 1f)
        require(guard >= 0f && guard < coreStart && softness > 0f)
        // |df/dd| <= this bound, so the inverse normal Jacobian remains >= .1.
        require(strength * (1f / falloff + 1.5f / (coreEnd - coreStart)) <= .9f)
        // Curvature radius remaining throughout support is >= reach*(1-coreEnd).
        require(strength < 1f - coreEnd)
    }

    fun offset(shape: LensGeometry, point: Offset): Offset {
        val reach = shape.corner.coerceIn(0f, shape.span.coerceAtLeast(0f))
        if (reach <= 0f || strength == 0f) return Offset.Zero
        val local = point - shape.bounds.center
        val q = Offset(abs(local.x) - shape.bounds.width / 2f + reach,
            abs(local.y) - shape.bounds.height / 2f + reach)
        val outside = Offset(max(q.x, 0f), max(q.y, 0f))
        val length = outside.getDistance()
        val distance = -(length + min(max(q.x, q.y), 0f) - reach)
        val raw = distance.coerceAtLeast(0f) / reach
        // Stable softplus: no exponential overflow at arbitrarily deep points.
        val delta = raw - guard
        val effective = guard + max(delta, 0f) + softness * ln(1f + exp(-abs(delta) / softness))
        if (effective >= coreEnd) return Offset.Zero
        val inward = if (length > .000001f) Offset(-sign(local.x) * outside.x / length,
            -sign(local.y) * outside.y / length)
        else if (q.x > q.y) Offset(-sign(local.x), 0f) else Offset(0f, -sign(local.y))
        val t = ((effective - coreStart) / (coreEnd - coreStart)).coerceIn(0f, 1f)
        val taper = 1f - t * t * (3f - 2f * t)
        return inward * (reach * strength * exp(-effective / falloff) * taper)
    }
}

/** Same coefficient contract as [BoundaryOptics]; adapter parity is checked with
 * coordinate-coded native images, including the legacy interpolated mesh. */
internal val BoundaryOpticsShader = """
    uniform float4 boundaryProfile;
    uniform float2 boundaryGuard;
    float2 boundaryOffset(float2 p) {
        float reach = min(corner, span);
        if (reach <= 0.0 || boundaryProfile.x <= 0.0) return float2(0);
        float2 local = p - (bounds.xy + bounds.zw) * 0.5;
        float2 q = abs(local) - (bounds.zw - bounds.xy) * 0.5 + reach;
        float2 outside = max(q, float2(0));
        float len = length(outside);
        float distance = -(len + min(max(q.x, q.y), 0.0) - reach);
        float delta = max(distance, 0.0) / reach - boundaryGuard.x;
        float effective = boundaryGuard.x + max(delta, 0.0)
            + boundaryGuard.y * log(1.0 + exp(-abs(delta) / boundaryGuard.y));
        if (effective >= boundaryProfile.w) return float2(0);
        float2 inward = len > 0.000001 ? -sign(local) * outside / len
            : (q.x > q.y ? float2(-sign(local.x), 0) : float2(0, -sign(local.y)));
        float t = clamp((effective - boundaryProfile.z) / (boundaryProfile.w - boundaryProfile.z), 0.0, 1.0);
        return inward * (reach * boundaryProfile.x * exp(-effective / boundaryProfile.y)
            * (1.0 - t * t * (3.0 - 2.0 * t)));
    }
""".trimIndent()
