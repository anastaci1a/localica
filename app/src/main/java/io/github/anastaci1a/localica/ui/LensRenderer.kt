package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.ui.spec.FluidContentBlurIn
import io.github.anastaci1a.localica.ui.spec.FluidGaussianSpec
import io.github.anastaci1a.localica.ui.spec.FluidLensingSpec

/** One material kernel for every glass shape. The geometry adapter supplies distance
 * and inward gradient; every shape gets the same refraction law (GlassRefraction). */
@RequiresApi(33)
internal class LensRenderer {
    private val shader = RuntimeShader("""
        uniform shader scene;
        uniform shader field;
        uniform float4 bounds;
        uniform float corner;
        uniform float2 weights;
        uniform float span;
        uniform float2 optics;
        uniform float4 refraction;
        uniform float3 outerMix;
        uniform float power;
        uniform float whiteTint;
        uniform float illumination;
        uniform float3 tone;
        uniform float fieldMode;
        uniform float2 fieldOrigin;
        uniform float fieldScale;
        uniform float fieldRange;
        uniform float circleMode;
        uniform float4 circleCenters;
        uniform float splitInnerRadius;
        uniform float4 mapShape;
        uniform float2 mapAngle;
        uniform float4 warp;
        uniform float2 warpClamp;
        uniform float warpMode;
        $GlassColorShader
        float3 circleField(float2 p, float2 center) {
            float2 local = p - center;
            float radial = length(local);
            return float3(span - radial, radial > 0.000001 ? -local / radial : float2(0));
        }
        float3 analyticGeometry(float2 p) {
            if (circleMode > 0.5) {
                float2 axis = circleCenters.zw - circleCenters.xy;
                float separation = length(axis);
                if (separation < 0.000001) return circleField(p, circleCenters.xy);
                float2 direction = axis / separation;
                float along = dot(p - (circleCenters.xy + circleCenters.zw) * 0.5, direction);
                float width = span * fieldMode;
                if (width < 0.000001) return circleField(p, along <= 0.0 ? circleCenters.xy : circleCenters.zw);
                float t = clamp(0.5 + along / (2.0 * width), 0.0, 1.0);
                float3 a = circleField(p, circleCenters.xy);
                float3 b = circleField(p, circleCenters.zw);
                float3 blended = mix(a, b, t * t * (3.0 - 2.0 * t));
                blended.yz += direction * ((b.x - a.x) * 3.0 * t * (1.0 - t) / width);
                return blended;
            }
            float2 local = p - (bounds.xy + bounds.zw) * 0.5;
            float2 e = abs(local) - (bounds.zw - bounds.xy) * 0.5;
            float reach = corner * 1.52866495;
            float2 q = mix(reach, corner, max(weights.x, weights.y)) + e;
            float2 v = max(float2(0), (e + reach) / reach);
            float length2 = length(v);
            float t = max(v.x, v.y) == 0.0 ? 0.0 : clamp(min(v.x, v.y) / max(v.x, v.y), 0.0, 1.0);
            float poly = (((3.15600991 - t * 0.926054) * t - 3.64122009) * t + 1.26803005) * t + 0.268530995;
            float a = length2 + 1.0 + -1.0 / (1.0 - t * t * clamp(length2, 0.0, 1.0) * poly);
            float b = length(max(float2(0), 1.52866495 * v - 0.528665006)) * 0.654165566 + 0.345834434;
            float2 axes = mix(float2(a), float2(b), weights);
            float side = v.y > v.x ? 1.0 : -1.0;
            float distance = min(0.0, max(q.x, q.y)) + reach * (mix(axes.x, axes.y, clamp(0.5 - side + side * t, 0.0, 1.0)) - 1.0);
            float2 n = max(q, float2(0));
            n = dot(n, n) > 0.0 ? normalize(n) : (q.x > q.y ? float2(1, 0) : float2(0, 1));
            return float3(-distance, local.x < 0.0 ? n.x : -n.x, local.y < 0.0 ? n.y : -n.y);
        }
        float4 quarterArc(float2 p, float2 c, float r, float side) {
            float2 q = max((p - c) * float2(side, -1), float2(0));
            float radial = length(q);
            float2 edge;
            if (radial > 0.000001) edge = c + q * float2(side, -1) * (r / radial);
            else {
                float2 a = c + float2(side * r, 0);
                float2 b = c + float2(0, -r);
                edge = dot(p - a, p - a) < dot(p - b, p - b) ? a : b;
            }
            return float4(edge, (c - edge) / max(length(c - edge), 0.000001));
        }
        float3 splitBoundary(float2 point) {
            float2 middle = (bounds.xy + bounds.zw) * 0.5;
            float inner = clamp(splitInnerRadius, 0.0, span);
            float fit = min(1.0, (bounds.z - bounds.x) * 0.5 / max(span + inner, 0.000001));
            float radius = span * fit;
            inner *= fit;
            float2 delta = point - middle;
            float2 p = middle - abs(delta);
            float2 closest = float2(clamp(p.x, bounds.x + radius, max(bounds.x + radius, middle.x - inner)), bounds.y);
            float2 normal = float2(0, 1);
            float square = dot(p - closest, p - closest);
            float2 side = float2(bounds.x, clamp(p.y, bounds.y + radius, middle.y));
            float sideSquare = dot(p - side, p - side);
            if (sideSquare < square) { closest = side; normal = float2(1, 0); square = sideSquare; }
            float4 outer = quarterArc(p, float2(bounds.x + radius, bounds.y + radius), radius, -1);
            float outerSquare = dot(p - outer.xy, p - outer.xy);
            if (outerSquare < square) { closest = outer.xy; normal = outer.zw; square = outerSquare; }
            float4 inset = quarterArc(p, float2(middle.x - inner, bounds.y + inner), inner, 1);
            float innerSquare = dot(p - inset.xy, p - inset.xy);
            if (innerSquare < square) { closest = inset.xy; normal = inset.zw; square = innerSquare; }
            float2 outerLocal = p - float2(bounds.x + radius, bounds.y + radius);
            float2 innerLocal = p - float2(middle.x - inner, bounds.y + inner);
            bool inside = p.x >= bounds.x && p.y >= bounds.y
                && (p.x >= bounds.x + radius || p.y >= bounds.y + radius || dot(outerLocal, outerLocal) <= radius * radius)
                && (p.x <= middle.x - inner || p.y >= bounds.y + inner || dot(innerLocal, innerLocal) <= inner * inner);
            float distance = sqrt(square);
            float polarity = inside ? 1.0 : -1.0;
            bool boundary = distance <= max(0.0001, span * 0.00001);
            float2 gradient = boundary ? normal : (p - closest) * (polarity / distance);
            gradient *= -sign(delta);
            return float3(boundary ? 0.0 : distance * polarity, gradient);
        }
        float3 geometry(float2 p) {
            if (fieldMode <= 0.0) return analyticGeometry(p);
            float3 sampled;
            if (splitInnerRadius >= 0.0) sampled = splitBoundary(p);
            else {
                float3 raw = float3(field.eval((p - fieldOrigin) * fieldScale).rgb);
                sampled = float3(raw.r * fieldRange, raw.gb * 2.0 - 1.0);
            }
            if (fieldMode >= 1.0) return sampled;
            return mix(analyticGeometry(p), sampled, fieldMode);
        }
        float profile(float2 law, float inside) {
            float t = clamp(inside / law.y, 0.0, 1.0);
            return law.x * (1.0 - sqrt(t * (2.0 - t)));
        }
        // Popup content warp: the glass map (distance below zero inside, outward unit normal,
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
    private var fieldBitmap: Bitmap? = null
    private var cached: RenderEffect? = null
    private var blurAmount = Float.NaN
    private var blurEffect: android.graphics.RenderEffect? = null
    init {
        shader.setInputBuffer("field", BitmapShader(emptyField, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }

    /** [unit] is recorded pixels per dp: display density times recording resolution. */
    fun effect(geometry: LensGeometry, blur: Float, depth: Float, unit: Float, power: Float = 1f,
        whiteTint: Float = 0f, illumination: Float = 1f, map: LensDistanceMap? = null,
        origin: Offset = Offset.Zero, flattening: Float = 0f,
        tone: GlassTone = GlassTone(), fieldWeight: Float = 1f,
        circles: Pair<Offset, Offset>? = null, splitInner: Float? = null, warp: LensWarp? = null): RenderEffect {
        val r = geometry.bounds
        val words = warp?.uniforms(unit)
        val state = floatArrayOf(r.left, r.top, r.right, r.bottom, geometry.corner, unit, blur,
            depth.coerceIn(.05f, 4f), power, whiteTint, illumination, origin.x, origin.y,
            map?.scale ?: 1f, map?.range ?: 1f, flattening, tone.trim, tone.weight, tone.lightMix,
            fieldWeight.coerceIn(0f, 1f), if (circles == null) 0f else 1f,
            circles?.first?.x ?: 0f, circles?.first?.y ?: 0f,
            circles?.second?.x ?: 0f, circles?.second?.y ?: 0f, splitInner ?: -1f) + (words ?: FloatArray(0))
        if (state.contentEquals(last) && fieldBitmap === map?.bitmap) return requireNotNull(cached)
        last = state
        shader.setFloatUniform("bounds", r.left, r.top, r.right, r.bottom)
        shader.setFloatUniform("corner", geometry.radius)
        shader.setFloatUniform("weights", geometry.weights.x, geometry.weights.y)
        shader.setFloatUniform("span", geometry.span)
        // Surface-wide constants belong to the draw, not every shaded pixel.
        val d = state[7]
        shader.setFloatUniform("optics", .62f * d / kotlin.math.sqrt(1f + d * d / 4f), .4f + .06f * flattening.coerceIn(0f, 1f))
        with(GlassRefraction) {
            shader.setFloatUniform("refraction", InnerAmount * unit, InnerHeight * unit, OuterAmount * unit, OuterHeight * unit)
            shader.setFloatUniform("outerMix", OuterOpacity, OuterDistance * unit, OuterDistanceEnd * unit)
        }
        shader.setFloatUniform("power", power)
        shader.setFloatUniform("whiteTint", whiteTint)
        shader.setFloatUniform("illumination", illumination)
        shader.setFloatUniform("tone", tone.trim, tone.weight, tone.lightMix)
        shader.setFloatUniform("fieldMode", if (map == null && splitInner == null) 0f else fieldWeight.coerceIn(0f, 1f))
        shader.setFloatUniform("splitInnerRadius", splitInner ?: -1f)
        shader.setFloatUniform("circleMode", if (circles == null) 0f else 1f)
        shader.setFloatUniform("circleCenters", circles?.first?.x ?: 0f, circles?.first?.y ?: 0f,
            circles?.second?.x ?: 0f, circles?.second?.y ?: 0f)
        shader.setFloatUniform("fieldOrigin", origin.x + (map?.origin?.x ?: 0f), origin.y + (map?.origin?.y ?: 0f))
        shader.setFloatUniform("fieldScale", map?.scale ?: 1f)
        shader.setFloatUniform("fieldRange", map?.range ?: 1f)
        val w = words ?: FloatArray(12)
        shader.setFloatUniform("mapShape", w[0], w[1], w[2], w[3])
        shader.setFloatUniform("mapAngle", w[4], w[5])
        shader.setFloatUniform("warp", w[6], w[7], w[8], w[9])
        shader.setFloatUniform("warpClamp", w[10], w[11])
        shader.setFloatUniform("warpMode", if (warp == null) 0f else 1f)
        if (fieldBitmap !== map?.bitmap) {
            fieldBitmap = map?.bitmap
            shader.setInputBuffer("field", BitmapShader(map?.bitmap ?: emptyField, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            })
        }
        if (blur != blurAmount) {
            blurAmount = blur
            blurEffect = if (FluidGaussianSpec.isIdentity(blur.toDouble())) null
                else android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        }
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
        return lensChain(blurEffect, lens, android.graphics.RenderEffect::createChainEffect).asComposeRenderEffect().also { cached = it }
    }
    companion object {
        private val emptyField by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }
}

/** Popup content warp inputs: the glass map's model values and the displacement filter's amount,
 * content transform and used pixel extent. [uniforms] packs the two shaders' words in upload order. */
internal class LensWarp(val height: Double, val amount: Double, val effectOffset: Float, val used: IntSize,
    val transform: DoubleArray = FluidContentBlurIn.zoomMatrix(), val curvature: Float = 1f, val angle: Float = 0f,
    val maskOffset: Float = 0f) {
    fun uniforms(unit: Float): FloatArray {
        val (limitX, limitY) = FluidLensingSpec.usedExtent(used.width, used.height, 1, 1)
        return floatArrayOf(height.toFloat() * unit, curvature.coerceIn(0f, 1f), effectOffset * unit, maskOffset * unit,
            kotlin.math.cos(angle), kotlin.math.sin(angle)) + FluidLensingSpec.amountUniform(transform, 1, 1, unit, amount) +
            floatArrayOf(limitX, limitY)
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
