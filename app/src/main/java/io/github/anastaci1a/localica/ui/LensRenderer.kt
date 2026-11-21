package io.github.anastaci1a.localica.ui

import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.spec.FluidContentBlurIn
import io.github.anastaci1a.localica.ui.spec.FluidGaussianSpec
import io.github.anastaci1a.localica.ui.spec.FluidLensingSpec

/** One material kernel for every glass shape. The geometry adapter supplies distance
 * and inward gradient; every shape gets the same refraction law (GlassRefraction). */
@RequiresApi(33)
internal class LensRenderer(private val connectedBodies: Int = 0) {
    private val bodyCapacity = maxOf(2, connectedBodies)
    private fun connectedQuery() = if (connectedBodies == 0) "" else """
        $LensFieldUnionShader
        float3 connectedField(float2 p) {
            // Compose 58 consumes negative-inside distances and outward normals.
            half4 field = half4(half3(-uniformCorners(p, bodyBounds[0], bodyRadii[0].x)), half(1));
            for (int i = 1; i < $connectedBodies; ++i) {
                half4 next = half4(half3(-uniformCorners(p, bodyBounds[i], bodyRadii[i * 2].x)), half(1));
                field = sdfUnion(field, next, half(0.0001));
            }
            return -float3(field.xyz);
        }
    """.trimIndent()
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform float4 bodyBounds[$bodyCapacity];
        uniform float4 bodyRadii[${2 * bodyCapacity}];
        uniform float bodyCount;
        uniform float span;
        uniform float2 optics;
        uniform float4 refraction;
        uniform float3 outerMix;
        uniform float power;
        uniform float whiteTint;
        uniform float illumination;
        uniform float3 tone;
        uniform float4 mapShape;
        uniform float2 mapAngle;
        uniform float4 warp;
        uniform float2 warpClamp;
        uniform float warpMode;
        $GlassColorShader
        float2 quarterPoint(float h) {
            return float2((1.0 - h) * (1.0 + h), 2.0 * h) / (1.0 + h * h);
        }
        float derivative(float h, float difference, float au, float bv) {
            float2 q = quarterPoint(h);
            return (difference * q.x - au) * q.y + bv * q.x;
        }
        float2 nearestQuarter(float2 p, float2 radii) {
            bool swap = radii.x < radii.y;
            float2 r = swap ? radii.yx : radii;
            float2 q = swap ? p.yx : p;
            float au = r.x * q.x, bv = r.y * q.y;
            float difference = (r.x - r.y) * (r.x + r.y);
            float2 best = difference < 2.0 * (au - bv) ? float2(1, 0) : float2(0, 1);
            if (difference == 0.0) {
                float2 positive = max(q, float2(0));
                if (any(greaterThan(positive, float2(0)))) best = normalize(positive);
            } else if (q.y == 0.0) {
                float x = clamp(au / difference, 0.0, 1.0);
                best = float2(x, sqrt((1.0 - x) * (1.0 + x)));
            } else if (q.x > 0.0) {
                // The distance as a function of cos(theta) has at most one interior minimum.
                // For a negative short-axis query, the derivative peaks at this sin(theta).
                float y = q.y < 0.0 ? min(1.0, pow(-bv / difference, 1.0 / 3.0)) : 0.0;
                float lo = y / (1.0 + sqrt((1.0 - y) * (1.0 + y))), hi = 1.0;
                if (derivative(lo, difference, au, bv) > 0.0) {
                    for (int i = 0; i < 24; ++i) {
                        float h = (lo + hi) * 0.5;
                        if (derivative(h, difference, au, bv) > 0.0) lo = h;
                        else hi = h;
                    }
                    float2 candidate = quarterPoint((lo + hi) * 0.5);
                    float2 delta = candidate - best;
                    if (difference * delta.x * (candidate.x + best.x) - 2.0 * dot(float2(au, bv), delta) < 0.0)
                        best = candidate;
                }
            }
            return swap ? best.yx : best;
        }
        float4 nearer(float4 best, float2 delta, float2 normal) {
            return dot(delta, delta) < dot(best.xy, best.xy) ? float4(delta, normal) : best;
        }
        float4 cornerArc(float4 best, float2 p, float2 tip, float2 r, float2 direction) {
            if (any(lessThanEqual(r, float2(0)))) return best;
            float2 q = (p - tip) * direction + r;
            float2 boxDelta = q - clamp(q, float2(0), r);
            if (dot(boxDelta, boxDelta) >= dot(best.xy, best.xy)) return best;
            float2 uv = nearestQuarter(q, r);
            return nearer(best, (q - uv * r) * direction, -normalize(uv * r.yx * direction));
        }
        bool insideCorner(float2 p, float2 tip, float2 r, float2 direction) {
            if (any(lessThanEqual(r, float2(0)))) return true;
            float2 q = (p - tip) * direction + r;
            if (any(lessThanEqual(q, float2(0)))) return true;
            float2 scaled = q / r;
            return dot(scaled, scaled) <= 1.0;
        }
        float4 verticalEdge(float4 best, float2 p, float x, float2 y, float2 cut, float direction) {
            if (cut.x >= cut.y)
                return nearer(best, p - float2(x, clamp(p.y, y.x, y.y)), float2(direction, 0));
            if (y.x < cut.x)
                best = nearer(best, p - float2(x, clamp(p.y, y.x, cut.x)), float2(direction, 0));
            if (cut.y < y.y)
                best = nearer(best, p - float2(x, clamp(p.y, cut.y, y.y)), float2(direction, 0));
            return best;
        }
        float4 bodyBoundary(float4 best, float2 p, float4 b, float4 top, float4 bottom, float2 cut, bool left) {
            best = nearer(best, p - float2(clamp(p.x, b.x + bottom.z, b.z - bottom.x), b.w), float2(0, -1));
            best = verticalEdge(best, p, b.z, float2(b.y + top.w, b.w - bottom.y), left ? cut : float2(0), -1.0);
            best = nearer(best, p - float2(clamp(p.x, b.x + top.x, b.z - top.z), b.y), float2(0, 1));
            best = verticalEdge(best, p, b.x, float2(b.y + top.y, b.w - bottom.w), left ? float2(0) : cut, 1.0);
            best = cornerArc(best, p, b.zw, bottom.xy, float2(1, 1));
            best = cornerArc(best, p, b.xw, bottom.zw, float2(-1, 1));
            best = cornerArc(best, p, b.zy, top.zw, float2(1, -1));
            return cornerArc(best, p, b.xy, top.xy, float2(-1, -1));
        }
        bool insideBody(float2 p, float4 b, float4 top, float4 bottom) {
            return all(greaterThanEqual(p, b.xy)) && all(lessThanEqual(p, b.zw))
                && insideCorner(p, b.xy, top.xy, float2(-1, -1))
                && insideCorner(p, b.zy, top.zw, float2(1, -1))
                && insideCorner(p, b.zw, bottom.xy, float2(1, 1))
                && insideCorner(p, b.xw, bottom.zw, float2(-1, 1));
        }
        float3 uniformCorners(float2 p, float4 b, float r) {
            float2 local = p - (b.xy + b.zw) * 0.5;
            float2 q = abs(local) - (b.zw - b.xy) * 0.5 + r;
            float outside = length(max(q, float2(0)));
            float2 n = outside > 0.0 ? max(q, float2(0)) / outside
                : (q.x > q.y ? float2(1, 0) : float2(0, 1));
            return float3(r - (outside + min(max(q.x, q.y), 0.0)),
                local.x < 0.0 ? n.x : -n.x, local.y < 0.0 ? n.y : -n.y);
        }
        ${connectedQuery()}
        float3 geometry(float2 p) {
            if (bodyCount == 0.0) return float3(-1e10, 0, 0);
            ${if (connectedBodies > 0) "return connectedField(p);" else """
            float4 a = bodyBounds[0], b = bodyBounds[1];
            float4 at = bodyRadii[0], ab = bodyRadii[1], bt = bodyRadii[2], bb = bodyRadii[3];
            if (bodyCount == 1.0 && all(equal(at, float4(at.x))) && all(equal(ab, at)))
                return uniformCorners(p, a, at.x);
            float2 cut = float2(0);
            if (bodyCount > 1.0 && a.z == b.x)
                cut = float2(max(a.y + at.w, b.y + bt.y), min(a.w - ab.y, b.w - bb.w));
            float4 closest = bodyBoundary(float4(1e16, 1e16, 0, 0), p, a, at, ab, cut, true);
            bool inside = insideBody(p, a, at, ab);
            if (bodyCount > 1.0) {
                closest = bodyBoundary(closest, p, b, bt, bb, cut, false);
                inside = inside || insideBody(p, b, bt, bb);
            }
            float distance = length(closest.xy);
            float side = inside ? 1.0 : -1.0;
            return float3(distance * side, distance > 0.0 ? closest.xy * (side / distance) : closest.zw);
            """}
        }
        float profile(float2 law, float inside) {
            float t = clamp(inside / law.y, 0.0, 1.0);
            return law.x * (1.0 - sqrt(t * (2.0 - t)));
        }
        // Popup content warp: the glass map (distance above zero inside, outward unit normal,
        // full coverage) evaluated inline, then the displacement sample it masks.
        half4 warped(float2 p, float3 f) {
            float2 n = dot(f.yz, f.yz) > 0.0 ? -normalize(f.yz) : float2(0);
            float u = f.x - mapShape.z;
            float z = u - mapShape.w;
            float w = max(abs(n.x) + abs(n.y), 0.0001);
            float mask = z < -5.0 ? 0.0 : clamp(z / w + 0.5, 0.0, 1.0);
            if (mask < 0.00392156886) return half4(0);
            float r = clamp(u / mapShape.x, 0.0, 1.0);
            float lift = mix(r < 1.0 ? 1.0 - 0.2929 : 1.0, sqrt(1.0 - (1.0 - r) * (1.0 - r)), mapShape.y);
            float2 normal = float2(dot(n, float2(mapAngle.x, -mapAngle.y)), dot(n, mapAngle.yx));
            float2 m = (1.0 - lift) * normal * clamp((mapShape.x - u) / w + 0.5, 0.0, 1.0);
            return scene.eval(min(warpClamp, p + float2(dot(m, warp.xy), dot(m, warp.zw)))) * half(mask);
        }
        half4 main(float2 p) {
            float3 f = geometry(p);
            if (warpMode > 0.5) return warped(p, f);
            if (f.x < outerMix.y) return scene.eval(p);
            float2 inward = dot(f.yz, f.yz) > 0.0 ? normalize(f.yz) : float2(0);
            half4 sampled = scene.eval(p - inward * (profile(refraction.xy, f.x) * power));
            float weight = outerMix.x * clamp((-f.x - outerMix.y) / (outerMix.z - outerMix.y), 0.0, 1.0);
            if (weight > 0.0) sampled = mix(sampled, scene.eval(p - inward * (profile(refraction.zw, f.x) * power)), half(weight));
            float alpha = float(sampled.a);
            float3 color = glassColor(float3(sampled.rgb) / max(alpha, 0.0001), whiteTint, tone);
            // A broad environment reflection belongs to the curved normal. No
            // painted plate, contour gradient or uniform adaptive-contrast coating.
            float u = clamp(1.0 - max(f.x, 0.0) / max(span, 0.001), 0.0, 1.0);
            float root = sqrt(max(1.0 - u * u, 0.0004));
            float slope = optics.x * u / root * (1.0 - 2.0 * optics.y + 3.0 * optics.y * u * u);
            float3 normal = normalize(float3(-f.yz * slope, 1.0));
            float facing = dot(normal, normalize(float3(-0.45, -0.75, 0.6)));
            float grazing = 1.0 - normal.z;
            grazing = grazing * grazing * grazing;
            float reflection = (0.018 * (1.0 - normal.z) + 0.26 * grazing) * illumination * power;
            float white = mix(1.0, 1.0 - glassTrim(tone), tone.z);
            float black = mix(glassTrim(tone), 0.0, tone.z);
            color = mix(color, float3(white), reflection * max(facing, 0.0));
            color = mix(color, float3(black), reflection * max(-facing, 0.0) * 0.3);
            // Environmental light still reflects when this recording is translucent
            // (for example an unpainted row over its parent's background).
            float reflected = reflection * max(facing, 0.0) * (1.0 - alpha);
            float reflectedTone = mix(1.0, 1.0 - glassTrim(tone), tone.z);
            return half4(half3(color * alpha + reflected * reflectedTone), half(alpha + reflected));
        }
    """.trimIndent())
    private var last = floatArrayOf()
    private var cached: RenderEffect? = null
    private var blurAmount = Float.NaN
    private var blurEffect: android.graphics.RenderEffect? = null

    /** [unit] is recorded pixels per dp: display density times recording resolution. */
    fun effect(geometry: LensGeometry, blur: Float, depth: Float, unit: Float, power: Float = 1f,
        whiteTint: Float = 0f, illumination: Float = 1f, flattening: Float = 0f,
        tone: GlassTone = GlassTone(), warp: LensWarp? = null, highlightSpan: Float = geometry.span): RenderEffect {
        val bodies = geometry.bodies
        // Stock surfaces supply one fitted round rectangle or SplitPill's horizontal pair.
        require(if (connectedBodies > 0) bodies.size == connectedBodies else
            bodies.size <= 2 && (bodies.size < 2 || bodies[0].right <= bodies[1].left))
        val bounds = FloatArray(4 * bodyCapacity)
        val radii = FloatArray(8 * bodyCapacity)
        bodies.forEachIndexed { i, body ->
            floatArrayOf(body.left, body.top, body.right, body.bottom).copyInto(bounds, i * 4)
            body.radiusWords(radii, i * 8)
        }
        val words = warp?.uniforms(unit)
        val d = depth.coerceIn(.05f, 4f)
        val state = floatArrayOf(unit, blur, d, power, whiteTint, illumination, flattening,
            tone.trim, tone.weight, tone.lightMix, bodies.size.toFloat(), highlightSpan) +
            bounds + radii + (words ?: FloatArray(0))
        if (state.contentEquals(last)) return requireNotNull(cached)
        last = state
        shader.setFloatUniform("bodyBounds", bounds)
        shader.setFloatUniform("bodyRadii", radii)
        shader.setFloatUniform("bodyCount", bodies.size.toFloat())
        shader.setFloatUniform("span", highlightSpan)
        // Surface-wide constants belong to the draw, not every shaded pixel.
        shader.setFloatUniform("optics", .62f * d / kotlin.math.sqrt(1f + d * d / 4f), .4f + .06f * flattening.coerceIn(0f, 1f))
        with(GlassRefraction) {
            shader.setFloatUniform("refraction", InnerAmount * unit, InnerHeight * unit, OuterAmount * unit, OuterHeight * unit)
            shader.setFloatUniform("outerMix", OuterOpacity, OuterDistance * unit, OuterDistanceEnd * unit)
        }
        shader.setFloatUniform("power", power)
        shader.setFloatUniform("whiteTint", whiteTint)
        shader.setFloatUniform("illumination", illumination)
        shader.setFloatUniform("tone", tone.trim, tone.weight, tone.lightMix)
        val w = words ?: FloatArray(12)
        shader.setFloatUniform("mapShape", w[0], w[1], w[2], w[3])
        shader.setFloatUniform("mapAngle", w[4], w[5])
        shader.setFloatUniform("warp", w[6], w[7], w[8], w[9])
        shader.setFloatUniform("warpClamp", w[10], w[11])
        shader.setFloatUniform("warpMode", if (warp == null) 0f else 1f)
        if (blur != blurAmount) {
            blurAmount = blur
            blurEffect = if (FluidGaussianSpec.isIdentity(blur.toDouble())) null
                else android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        }
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
        return lensChain(blurEffect, lens, android.graphics.RenderEffect::createChainEffect).asComposeRenderEffect().also { cached = it }
    }
    private fun RoundRect.radiusWords(words: FloatArray, offset: Int) {
        listOf(topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)
            .forEachIndexed { index, radius ->
                words[offset + index * 2] = radius.x
                words[offset + index * 2 + 1] = radius.y
            }
    }
}

/** Popup content warp inputs: the glass map's model values and the displacement filter's amount,
 * content transform and used pixel extent. [uniforms] packs the two shaders' words in upload order. */
internal class LensWarp(val height: Double, val amount: Double, val effectOffset: Float, val used: IntSize,
    val transform: DoubleArray = FluidContentBlurIn.zoomMatrix(), val curvature: Float = 1f, val angle: Float = 0f,
    val maskOffset: Float = 0f) {
    fun uniforms(unit: Float): FloatArray {
        val (limitX, limitY) = FluidLensingSpec.usedExtent(used.width, used.height, 1, 1)
        val displacement = FluidLensingSpec.amountUniform(transform, 1, 1, unit, amount)
        return floatArrayOf(height.toFloat() * unit, curvature.coerceIn(0f, 1f), effectOffset * unit, maskOffset * unit,
            kotlin.math.cos(angle), kotlin.math.sin(angle),
            displacement[0], displacement[1], displacement[2], displacement[3], limitX, limitY)
    }
}

/** The shared filter order: an elided (identity) blur passes the scene straight to the lens.
 * The lens evaluates its map inline, so no separate named map surface is retained. */
internal fun <E : Any> lensChain(blur: E?, lens: E, chain: (outer: E, inner: E) -> E): E =
    FluidLensingSpec.colorChain(emptyList<E>(), { null }) { filter, applied, _ ->
        when (filter) {
            FluidLensingSpec.Filter.GaussianBlur -> blur?.let(::listOf)
            FluidLensingSpec.Filter.DisplacementMap -> applied + lens
        }
    }.reduce { inner, outer -> chain(outer, inner) }

/** Device quad partners mapped back into the same local primitive. */
internal const val FluidDeviceQuadShader = """
    uniform float3x3 toDevice, toLocal;
    float2 quadPartner(float2 device, float2 axis) {
        float2 direction = 1.0 - 2.0 * mod(floor(device), 2.0);
        return (toLocal * float3(device + direction * axis, 1.0)).xy;
    }
    float quadWidth(float value, float horizontal, float vertical) {
        return max(abs(value - horizontal) + abs(value - vertical), 0.0001);
    }
"""

internal const val FluidApertureCoverageShader = """
    float halfOffset(float value) {
        float magnitude = abs(value);
        float unit = exp2(floor(log2(max(magnitude, 0.00006103515625))) - 10.0);
        float whole = floor(magnitude / unit);
        float remainder = magnitude / unit - whole;
        bool up = remainder > 0.5 || (remainder == 0.5 && mod(whole, 2.0) == 1.0);
        return sign(value) * (whole + (up ? 1.0 : 0.0)) * unit;
    }
    float contour(float2 q, float2 mixWeight, int selector) {
        if (selector == 10) return sqrt(dot(q, q));
        q = abs(q);
        float weight = q.y > q.x ? mixWeight.x : mixWeight.y;
        float shifted = length(max(q * 1.528664947 - 0.5286650062, float2(0)));
        if (selector == 11) {
            float high = max(q.x, q.y);
            float t = high == 0.0 ? 0.0 : clamp(min(q.x, q.y) / high, 0.0, 1.0);
            float s = (((3.156009913 - 0.9260540008 * t) * t - 3.641220093) * t + 1.268030047) * t + 0.2685309947;
            return mix(length(q) + 1.0 - 1.0 / (1.0 - t * t * s),
                       shifted * 0.654165566 + 0.345834434, weight);
        }
        if (selector == 12) {
            float2 r = max(q * 1.329273939 - 0.3292739391, float2(0));
            float t = clamp(1.0 - abs(r.y - r.x), 0.0, 1.0);
            float s = ((((-1.5 - t * 1.13) * t + 12.15) * t - 17.4) * t + 9.0) * t - 0.12;
            return mix(length(r) - (t * 0.05402784795) * s, (shifted - 1.0) * 0.8695652485 + 1.0, weight);
        }
        float2 r = max(q * 1.273887396 - 0.2738873959, float2(0));
        float high = max(r.x, r.y);
        float t = high == 0.0 ? 0.0 : min(r.x, r.y) / high;
        float s = (((16.03350067 - t * 4.821469784) * t - 17.85799980) * t + 4.901319981) * t + 2.744640112;
        return mix(length(r) - (t * t * 0.06903559715) * s, (shifted - 1.0) * 0.8333333135 + 1.0, weight);
    }
    half cornerCoverage(float2 q, float2 x, float2 y, float2 mixWeight, int selector) {
        float u = contour(q, mixWeight, selector);
        if (selector >= 12 && u < 0.8) return half(1);
        float ux = contour(x, mixWeight, selector), uy = contour(y, mixWeight, selector);
        if (selector == 11) {
            float d = halfOffset(u - 1.0);
            float width = quadWidth(d + 1.0, halfOffset(ux - 1.0) + 1.0, halfOffset(uy - 1.0) + 1.0);
            return half(halfOffset(clamp(0.5 - d / width, 0.0, 1.0)));
        }
        return half(halfOffset(clamp(0.5 + (1.0 - u) / quadWidth(u, ux, uy), 0.0, 1.0)));
    }
"""

@RequiresApi(33)
private val apertureClipShader by lazy {
    RuntimeShader("""
        uniform shader scene;
        uniform float2 center, halfSize, radius, mixWeight;
        uniform int selector;
        $FluidDeviceQuadShader
        $FluidApertureCoverageShader
        half4 main(float2 p) {
            float2 quadrant = step(float2(0), p - center) * 2.0 - 1.0;
            float2 r = (p - center) * quadrant - (halfSize - radius);
            float2 device = (toDevice * float3(p, 1.0)).xy;
            float2 x = r + (quadPartner(device, float2(1, 0)) - p) * quadrant;
            float2 y = r + (quadPartner(device, float2(0, 1)) - p) * quadrant;
            half mask;
            if (r.x > 0.0 && r.y > 0.0) mask = cornerCoverage(r / radius, x / radius, y / radius, mixWeight, selector);
            else {
                bool useX = r.x - radius.x > r.y - radius.y;
                float distance = useX ? r.x - radius.x : r.y - radius.y;
                float width = useX ? quadWidth(r.x, x.x, y.x) : quadWidth(r.y, x.y, y.y);
                mask = half(clamp(0.5 - distance / width, 0.0, 1.0));
            }
            return scene.eval(p) * mask;
        }
    """.trimIndent())
}

@RequiresApi(33)
internal fun fluidApertureClip(
    aperture: RoundRect, device: BackdropRegistration, selector: Int = 10, mixX: Float = 0f, mixY: Float = 0f,
): RenderEffect = apertureClipShader.run {
    fun BackdropRegistration.words() = floatArrayOf(xx, yx, 0f, xy, yy, 0f, tx, ty, 1f)
    setFloatUniform("toDevice", device.words())
    setFloatUniform("toLocal", requireNotNull(device.inverse()).words())
    setFloatUniform("center", (aperture.left + aperture.right) * 0.5f, (aperture.top + aperture.bottom) * 0.5f)
    setFloatUniform("halfSize", aperture.width * 0.5f, aperture.height * 0.5f)
    setFloatUniform("radius", aperture.topLeftCornerRadius.x, aperture.topLeftCornerRadius.y)
    setFloatUniform("mixWeight", mixX, mixY)
    setIntUniform("selector", selector)
    android.graphics.RenderEffect.createRuntimeShaderEffect(this, "scene").asComposeRenderEffect()
}
