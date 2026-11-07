package io.github.anastaci1a.localica.ui.engine

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.spec.FocusSpec
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/** How the focus blur composites on this device (#640 §2.2). */
enum class FocusBlurMode {
    /** API 33+: AGSL composite sampling a pre-blurred pyramid, radial front, dim veil. */
    Shader,
    /** API 31–32: one pre-blurred level under a radial alpha mask. */
    Masked,
    /** API < 31 and glass mode None: the radial dim veil only. */
    DimOnly,
}

fun focusBlurMode(api: Int, glassNone: Boolean): FocusBlurMode = when {
    glassNone || api < 31 -> FocusBlurMode.DimOnly
    api < 33 -> FocusBlurMode.Masked
    else -> FocusBlurMode.Shader
}

/**
 * Focus blur prototype (#640 §2.1): the scene is captured **once** (at δ, not per frame) into a quarter-resolution
 * bitmap, pre-blurred once into a pyramid, and then composited per frame by one full-screen draw whose mask is
 * [FocusMask]. Lives for one hold or card; [release] drops the bitmaps. Engine only: the caller supplies the scene
 * recording and the readback (production: `FrostedBackdrop.captureCurrent`), so this file imports nothing from `ui`.
 * Trace sections `focus.capture`, `focus.pyramid` and `focus.composite` mark the three costs for Perfetto.
 */
class FocusBackdrop(private val spec: FocusSpec, private val screen: Density, val mode: FocusBlurMode) {
    private var sceneSize = IntSize.Zero
    private var levels: List<ImageBitmap> = emptyList()
    private var shader: RuntimeShader? = null
    /** Diagnostics for tests: how many captures, pyramid builds and composites happened. */
    var captures = 0; private set
    var pyramidBuilds = 0; private set
    var composites = 0; private set

    /** Readiness is a logical gate (I7): until the pyramid exists the blur channel holds at 0. The dim-only veil needs no capture. */
    val ready: Boolean get() = mode == FocusBlurMode.DimOnly || levels.isNotEmpty()

    /**
     * Record [drawScene] at `spec.captureScale` into a fresh capture layer and hand it to [readback], which owns the
     * native readback and returns the bitmap (or null when the scene went invalid). Builds the pyramid on success.
     */
    suspend fun capture(sceneSize: IntSize, graphics: GraphicsContext, direction: LayoutDirection,
        readback: suspend (captureLayer: GraphicsLayer, record: () -> Unit) -> ImageBitmap?, drawScene: DrawScope.() -> Unit): Boolean {
        require(sceneSize.width > 0 && sceneSize.height > 0) { "scene must have a size" }
        this.sceneSize = sceneSize
        if (mode == FocusBlurMode.DimOnly) return true
        if (Build.VERSION.SDK_INT < 31) return false
        val scaled = IntSize(max(1, ceil(sceneSize.width * spec.captureScale).toInt()), max(1, ceil(sceneSize.height * spec.captureScale).toInt()))
        val captureLayer = graphics.createGraphicsLayer()
        val image = try {
            Trace.beginSection("focus.capture")
            try {
                readback(captureLayer) {
                    captureLayer.record(screen, direction, scaled) { scale(spec.captureScale, spec.captureScale, Offset.Zero) { drawScene() } }
                }
            } finally { Trace.endSection() }
        } finally { graphics.releaseGraphicsLayer(captureLayer) }
        if (image == null) return false
        captures++
        buildPyramid(image, graphics, direction)
        return true
    }

    @RequiresApi(31)
    private suspend fun buildPyramid(scene: ImageBitmap, graphics: GraphicsContext, direction: LayoutDirection) {
        Trace.beginSection("focus.pyramid")
        try {
            val sigmas = if (mode == FocusBlurMode.Shader) spec.pyramidSigmasDp else listOf(spec.pyramidSigmasDp[spec.pyramidSigmasDp.size / 2])
            val built = ArrayList<ImageBitmap>(sigmas.size)
            for (sigmaDp in sigmas) {
                val radius = sigmaDp * screen.density * spec.captureScale
                val layer = graphics.createGraphicsLayer()
                try {
                    layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    layer.record(screen, direction, IntSize(scene.width, scene.height)) { drawImage(scene) }
                    built += layer.toImageBitmap()
                } finally { graphics.releaseGraphicsLayer(layer) }
            }
            levels = built
            pyramidBuilds++
            if (mode == FocusBlurMode.Shader && Build.VERSION.SDK_INT >= 33) shader = buildShader(built)
        } finally { Trace.endSection() }
    }

    @RequiresApi(33)
    private fun buildShader(levels: List<ImageBitmap>): RuntimeShader = RuntimeShader(CompositeShader).apply {
        for ((index, level) in levels.withIndex()) setInputShader("l${index + 1}", BitmapShader(level.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        setFloatUniform("scale", spec.captureScale)
    }

    /**
     * The per-frame composite, drawn in the scene's own coordinate basis above the live list and below the focus plane.
     * [front] is w (the blur front window of h), [strength] is S (the strength window of h times the user setting),
     * both in [0, 1]. Nothing here allocates per frame beyond the brush objects of the fallback paths.
     */
    fun DrawScope.composite(item: Rect, cornerRadius: Float, front: Float, strength: Float, dark: Boolean) {
        if (!ready || strength <= 0f || sceneSize == IntSize.Zero) return
        Trace.beginSection("focus.composite")
        try {
            composites++
            val viewport = Rect(Offset.Zero, Size(sceneSize.width.toFloat(), sceneSize.height.toFloat()))
            val radius = cornerRadius.coerceAtMost(minOf(item.width, item.height) / 2f).coerceAtLeast(0f)
            val feather = spec.maskFeatherDp * screen.density
            val dMax = FocusMask.frontStart(item, viewport, radius)
            val rho = FocusMask.front(front, dMax)
            val dim = if (dark) spec.dimDark else spec.dimLight
            when (mode) {
                FocusBlurMode.Shader -> {
                    if (Build.VERSION.SDK_INT < 33) return
                    val runtime = shader ?: return
                    runtime.setFloatUniform("item", item.left, item.top, item.right, item.bottom)
                    runtime.setFloatUniform("radius", radius)
                    runtime.setFloatUniform("rho", rho)
                    runtime.setFloatUniform("feather", feather)
                    runtime.setFloatUniform("strength", strength)
                    runtime.setFloatUniform("dim", dim)
                    drawRect(ShaderBrush(runtime))
                }
                FocusBlurMode.Masked -> {
                    // One level under a radial alpha mask: the front is a circle around the item's centre at the
                    // item's outer radius plus ρ, feathered over F. Still radial and eased, as §2.2 asks.
                    val outer = hypot(item.width, item.height) / 2f
                    val canvas = drawContext.canvas
                    canvas.saveLayer(viewport, Paint())
                    val level = levels[0]
                    drawImage(level, IntOffset.Zero, IntSize(level.width, level.height), IntOffset.Zero, sceneSize, filterQuality = FilterQuality.Low)
                    drawRect(Color.Black, alpha = dim)
                    drawRect(radialMask(item.center, outer + rho, feather, strength), blendMode = BlendMode.DstIn)
                    canvas.restore()
                }
                FocusBlurMode.DimOnly -> {
                    val outer = hypot(item.width, item.height) / 2f
                    drawRect(radialMask(item.center, outer + rho, feather, strength * dim), blendMode = BlendMode.SrcOver)
                }
            }
        } finally { Trace.endSection() }
    }

    private fun radialMask(center: Offset, inner: Float, feather: Float, alpha: Float): Brush {
        val end = inner + feather
        // Hermite feather sampled at its quarter points; the gradient interpolates linearly between them.
        val stops = arrayOf(
            0f to Color.Black.copy(alpha = 0f), (inner / end).coerceIn(0f, 1f) to Color.Black.copy(alpha = 0f),
            ((inner + feather * .25f) / end).coerceIn(0f, 1f) to Color.Black.copy(alpha = alpha * FocusMask.smoothstep(0f, 1f, .25f)),
            ((inner + feather * .5f) / end).coerceIn(0f, 1f) to Color.Black.copy(alpha = alpha * .5f),
            ((inner + feather * .75f) / end).coerceIn(0f, 1f) to Color.Black.copy(alpha = alpha * FocusMask.smoothstep(0f, 1f, .75f)),
            1f to Color.Black.copy(alpha = alpha))
        return Brush.radialGradient(colorStops = stops, center = center, radius = max(end, 1f), tileMode = TileMode.Clamp)
    }

    fun release() { levels = emptyList(); shader = null; sceneSize = IntSize.Zero }

    private companion object {
        /** β = strength · smoothstep(0, F, sdf − ρ); the colour blends the two pyramid levels adjacent to β and adds the dim veil. Premultiplied output with alpha β. */
        const val CompositeShader = """
            uniform shader l1; uniform shader l2; uniform shader l3;
            uniform float4 item; uniform float radius; uniform float rho; uniform float feather; uniform float strength; uniform float dim; uniform float scale;
            float roundedRect(float2 p) {
                float2 c = (item.xy + item.zw) * 0.5; float2 h = (item.zw - item.xy) * 0.5 - radius;
                float2 q = abs(p - c) - h;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }
            half4 main(float2 p) {
                float beta = strength * smoothstep(0.0, feather, roundedRect(p) - rho);
                if (beta <= 0.0) return half4(0.0);
                float2 c = p * scale;
                float lv = beta * 2.0;
                half4 blurred = lv < 1.0 ? mix(l1.eval(c), l2.eval(c), half(lv)) : mix(l2.eval(c), l3.eval(c), half(lv - 1.0));
                half veil = half(dim * beta);
                half4 color = blurred * (1.0 - veil);
                return half4(color.rgb, 1.0) * half(beta);
            }
        """
    }
}
