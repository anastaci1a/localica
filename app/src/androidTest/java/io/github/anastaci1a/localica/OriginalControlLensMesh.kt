package io.github.anastaci1a.localica.ui

import android.graphics.BitmapShader
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

/** Android 8–12 execution adapter for the same new lens model. It warps a shared
 * prepared scene through a bounded mesh; it does not retain the old edge-only style. */
internal class OriginalControlLensMesh {
    private var geometryKey: List<Any> = emptyList()
    private var opticalDepth = Float.NaN
    private var opticalPower = Float.NaN
    private var opticalFlattening = Float.NaN
    private var opticalTone = GlassTone()
    private var fields = emptyList<LensField>()
    private var generation = 0
    private var vertices = FloatArray(0)
    private var rays = FloatArray(0)
    private var texture = FloatArray(0)
    private var highlights = IntArray(0)
    private var indices = ShortArray(0)
    private var conditionedIndices = ShortArray(0)
    private val scenePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val addition = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    private val shaders = object : LinkedHashMap<ImageBitmap, BitmapShader>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ImageBitmap, BitmapShader>) = size > 4
    }
    // drawVertices is hardware-supported only from API 29. Older devices execute
    // the same mesh into a retained raster, then composite it normally. Never turn
    // off hardware acceleration for the whole app or retain another optical style.
    private data class RasterKey(val generation: Int, val image: ImageBitmap?,
        val sampling: BackdropSampleMapping, val lighting: Boolean)
    private val rasters = object : LinkedHashMap<RasterKey, Bitmap>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RasterKey, Bitmap>) = size > 4
    }

    fun DrawScope.draw(shape: LensGeometry, snapshot: ImageBitmap?, sourceSize: IntSize,
        sourceOffset: Offset, depth: Float, power: Float = 1f, alpha: Float = 1f,
        field: ((Offset) -> LensField)? = null, fieldKey: Any? = null, lighting: Boolean = true, additive: Boolean = false,
        flattening: Float = 0f, tone: GlassTone = GlassTone(), outlines: List<RoundRect>? = null,
        registration: BackdropRegistration? = null) {
        val next = listOf(shape, density, fieldKey ?: Unit, outlines ?: Unit)
        val geometryChanged = geometryKey != next
        if (geometryChanged) {
            geometryKey = next
            val nextMesh = when {
                outlines != null -> outlinedLensMesh(outlines, density, field ?: shape::field)
                field == null -> curvedLensMesh(shape, density)
                else -> sampledLensMesh(shape, density, field)
            }
            vertices = nextMesh.vertices; fields = nextMesh.fields; indices = nextMesh.indices
            rays = FloatArray(vertices.size); texture = FloatArray(vertices.size)
            conditionedIndices = ShortArray(indices.size)
            // Android 8 JNI validates colors against coordinate count (2/vertex).
            highlights = IntArray(vertices.size)
        }
        if (geometryChanged || depth != opticalDepth || power != opticalPower || flattening != opticalFlattening || tone != opticalTone) {
            opticalDepth = depth; opticalPower = power; opticalFlattening = flattening; generation++
            opticalTone = tone
            for (i in fields.indices) {
                val f = fields[i]
                // Boundary triangles include vertices just outside a rounded clip.
                // Extend the boundary normal there; zeroing their ray introduces
                // a sawtooth along the curve when those triangles interpolate.
                val ray = lensRay(f.copy(distance = f.distance.coerceAtLeast(0f)), shape.span, depth, flattening)
                rays[2 * i] = ray.offset.x * power; rays[2 * i + 1] = ray.offset.y * power
                highlights[i] = lensReflectionColor(ray, tone, power)
            }
        }
        scenePaint.shader = snapshot?.let { shaders.getOrPut(it) {
            BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        } }
        val sampling = BackdropSampleMapping(sourceSize,
            if (snapshot == null || registration != null) Offset.Zero else sourceOffset,
            registration.takeIf { snapshot != null })
        if (snapshot != null) {
            val imageSize = IntSize(snapshot.width, snapshot.height)
            for (i in texture.indices step 2) {
                val sample = sampling.texture(Offset(vertices[i] + rays[i], vertices[i + 1] + rays[i + 1]), imageSize)
                texture[i] = sample.x
                texture[i + 1] = sample.y
            }
        }
        fun render(canvas: android.graphics.Canvas, opacity: Float, textureIndices: ShortArray = indices) {
            if (snapshot != null) {
                scenePaint.alpha = (255 * opacity).toInt().coerceIn(0, 255)
                canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, vertices.size,
                    vertices, 0, texture, 0, null, 0, textureIndices, 0, textureIndices.size, scenePaint)
            }
            if (lighting) {
                lightPaint.alpha = (255 * opacity).toInt().coerceIn(0, 255)
                canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, vertices.size,
                    vertices, 0, null, 0, highlights, 0, indices, 0, indices.size, lightPaint)
            }
        }
        drawIntoCanvas { canvas ->
            if (Build.VERSION.SDK_INT >= 29) {
                scenePaint.xfermode = if (additive) addition else null
                render(canvas.nativeCanvas, alpha)
            }
            else {
                val key = RasterKey(generation, snapshot, sampling, lighting)
                val image = rasters.getOrPut(key) {
                    Bitmap.createBitmap(ceil(shape.bounds.width).toInt().coerceAtLeast(1),
                        ceil(shape.bounds.height).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        if (snapshot != null) conditionLensTextureTriangles(vertices, texture, indices, conditionedIndices)
                        val target = android.graphics.Canvas(it)
                        target.translate(-shape.bounds.left, -shape.bounds.top)
                        render(target, 1f, conditionedIndices)
                    }
                }
                bitmapPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                bitmapPaint.xfermode = if (additive) addition else null
                canvas.nativeCanvas.drawBitmap(image, shape.bounds.left, shape.bounds.top, bitmapPaint)
            }
        }
    }
}
