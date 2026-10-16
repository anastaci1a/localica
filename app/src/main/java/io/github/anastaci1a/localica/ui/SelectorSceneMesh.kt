package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

/** Legacy execution adapter for independently moving shell/ink over a stable parent.
 * It reads no live UI pixels during motion. API26–28 still require a bounded software
 * output for drawVertices; API29–32 submit the same layered mesh to the native canvas.
 */
internal class SelectorSceneMesh {
    var meshBuilds = 0
        private set
    var frameBuilds = 0
        private set
    var softwareRasters = 0
        private set
    private data class Key(val geometry: SelectorSceneGeometry, val backdrop: ImageBitmap,
        val backdropSize: IntSize, val backdropOffset: Offset, val ink: ImageBitmap?, val inkSize: IntSize,
        val normalInk: Int, val selectedInk: Int, val tone: GlassTone, val density: Float)
    private var key: Key? = null
    private var raster: Bitmap? = null
    private var vertices = FloatArray(0)
    private var backdropUv = FloatArray(0)
    private var inkUv = FloatArray(0)
    private var indices = ShortArray(0)
    private var backdropIndices = ShortArray(0)
    private var inkIndices = ShortArray(0)
    private var inkColors = IntArray(0)
    private var shellLight = IntArray(0)
    private var shellShine = IntArray(0)
    private var shellShade = IntArray(0)
    private var selectorLight = IntArray(0)
    private var shellLightVisible = false
    private var shellShineVisible = false
    private var shellShadeVisible = false
    private val backingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint()
    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun DrawScope.draw(geometry: SelectorSceneGeometry, backdrop: ImageBitmap, backdropSize: IntSize,
        backdropOffset: Offset, ink: ImageBitmap?, inkSize: IntSize, normalInk: Int, selectedInk: Int,
        tone: GlassTone) {
        val next = Key(geometry, backdrop, backdropSize, backdropOffset, ink, inkSize, normalInk, selectedInk, tone, density)
        if (key != next) {
            frameBuilds++
            val previousShape = key?.geometry?.selector
            val shapeChanged = previousShape?.bounds?.size != geometry.selector.bounds.size ||
                previousShape?.corner != geometry.selector.corner || key?.density != density
            if (shapeChanged) {
                meshBuilds++
                val mesh = curvedLensMesh(geometry.selector.copy(bounds =
                    androidx.compose.ui.geometry.Rect(Offset.Zero, geometry.selector.bounds.size)), density)
                vertices = mesh.vertices; indices = mesh.indices
                // Deformation can change coordinates without changing mesh capacity.
                // These scratch arrays never escape into a retained display bitmap.
                if (backdropUv.size != vertices.size) {
                    backdropUv = FloatArray(vertices.size); inkUv = FloatArray(vertices.size)
                    // Older Android validates colors against coordinate count, not vertex count.
                    inkColors = IntArray(vertices.size); shellLight = IntArray(vertices.size)
                    shellShine = IntArray(vertices.size); shellShade = IntArray(vertices.size)
                    selectorLight = IntArray(vertices.size)
                }
                if (Build.VERSION.SDK_INT < 29) {
                    if (backdropIndices.size != indices.size) {
                        backdropIndices = ShortArray(indices.size); inkIndices = ShortArray(indices.size)
                    }
                } else { backdropIndices = indices; inkIndices = indices }
            }
            val sx = backdrop.width.toFloat() / backdropSize.width.coerceAtLeast(1)
            val sy = backdrop.height.toFloat() / backdropSize.height.coerceAtLeast(1)
            val ix = (ink?.width ?: 1).toFloat() / inkSize.width.coerceAtLeast(1)
            val iy = (ink?.height ?: 1).toFloat() / inkSize.height.coerceAtLeast(1)
            shellLightVisible = false; shellShineVisible = false; shellShadeVisible = false
            for (i in 0 until vertices.size / 2) {
                val sample = geometry.sample(Offset(vertices[i * 2], vertices[i * 2 + 1]) + geometry.selector.bounds.topLeft,
                    ink != null, extendBoundary = true)
                backdropUv[i * 2] = (sample.backdrop.x + backdropOffset.x) * sx
                backdropUv[i * 2 + 1] = (sample.backdrop.y + backdropOffset.y) * sy
                inkUv[i * 2] = (sample.ink?.x ?: 0f) * ix
                inkUv[i * 2 + 1] = (sample.ink?.y ?: 0f) * iy
                inkColors[i] = if (sample.ink == null) 0 else if (sample.selectedInk) selectedInk else normalInk
                shellLight[i] = sample.shellRay?.let { lensReflectionColor(it, tone) } ?: 0
                selectorLight[i] = lensReflectionColor(sample.selectorRay, GlassTone())
                val p = sample.surface - geometry.shell.bounds.center
                val angle = ((atan2(p.y, p.x) / (2f * PI.toFloat())) + 1f) % 1f
                val distance = insideDistance(geometry.shell, sample.surface)
                shellShine[i] = glassGray(tone.channel(1f), rimAlpha(angle, false) * (1.3f * density + .5f - distance).coerceIn(0f, 1f))
                shellShade[i] = glassGray(tone.channel(0f), rimAlpha(angle, true) * (.7f * density + .5f - distance).coerceIn(0f, 1f))
                if (sample.shellRay == null) { shellShine[i] = 0; shellShade[i] = 0 }
                shellLightVisible = shellLightVisible || shellLight[i] ushr 24 != 0
                shellShineVisible = shellShineVisible || shellShine[i] ushr 24 != 0
                shellShadeVisible = shellShadeVisible || shellShade[i] ushr 24 != 0
            }
            if (Build.VERSION.SDK_INT < 29) {
                conditionLensTextureTriangles(vertices, backdropUv, indices, backdropIndices)
                conditionLensTextureTriangles(vertices, inkUv, indices, inkIndices)
            }
            if (key?.backdrop !== backdrop) backingPaint.shader = BitmapShader(backdrop.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (key?.ink !== ink) inkPaint.shader = ink?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            raster = null
            key = next
        }
        fun render(canvas: Canvas) {
            fun layer(paint: Paint, uv: FloatArray?, colors: IntArray?, triangles: ShortArray = indices) =
                canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertices.size, vertices, 0,
                    uv, 0, colors, 0, triangles, 0, triangles.size, paint)
            layer(backingPaint, backdropUv, null, backdropIndices)
            // An inset selector often samples none of the shell's rim. Avoid
            // traversing/rasterizing entire transparent layers on older devices.
            if (shellLightVisible) layer(lightPaint, null, shellLight)
            if (shellShineVisible) layer(lightPaint, null, shellShine)
            if (shellShadeVisible) layer(lightPaint, null, shellShade)
            if (ink != null) layer(inkPaint, inkUv, inkColors, inkIndices)
            layer(lightPaint, null, selectorLight)
        }
        drawIntoCanvas { canvas ->
            if (Build.VERSION.SDK_INT >= 29) {
                val target = canvas.nativeCanvas
                target.save()
                target.translate(geometry.selector.bounds.left, geometry.selector.bounds.top)
                render(target)
                target.restore()
            }
            else {
                val bounds = geometry.selector.bounds
                val output = raster ?: Bitmap.createBitmap(ceil(bounds.width).toInt().coerceAtLeast(1),
                    ceil(bounds.height).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    softwareRasters++
                    render(Canvas(it))
                    raster = it
                }
                canvas.nativeCanvas.drawBitmap(output, bounds.left, bounds.top, rasterPaint)
            }
        }
    }

    private fun insideDistance(shape: LensGeometry, point: Offset): Float {
        val p = point - shape.bounds.center
        val x = abs(p.x) - shape.bounds.width / 2f + shape.corner
        val y = abs(p.y) - shape.bounds.height / 2f + shape.corner
        return shape.corner - hypot(max(x, 0f), max(y, 0f)) - min(max(x, y), 0f)
    }
    private fun rimAlpha(angle: Float, shade: Boolean): Float {
        val stops = if (shade) shadeStops else shineStops
        val position = angle * 8f
        val index = position.toInt().coerceIn(0, 7)
        return stops[index] + (stops[index + 1] - stops[index]) * (position - index)
    }
    private val shineStops = floatArrayOf(.025f, .32f, .025f, 0f, .025f, .32f, .025f, 0f, .025f)
    private val shadeStops = floatArrayOf(0f, 0f, 0f, .11f, 0f, 0f, 0f, .11f, 0f)
}
