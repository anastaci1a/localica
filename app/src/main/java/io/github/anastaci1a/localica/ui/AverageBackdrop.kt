package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import kotlin.math.ceil
import kotlin.math.floor

/** Integrate the shared, progressively box-reduced 32×32 grid by overlap area.
 * A footprint boundary inside a cell cannot recover that cell's original subpixels.
 * Its mean error is bounded by boundary-cell area fraction times boundary contrast
 * (apart from raster quantization). Finished materials reuse one resolved texel. */
internal fun FrostedBackdrop.averageColor(location: BackdropLocation, bounds: Rect): Color? {
    val mapping = location.registrationTo(this.location) ?: return null
    return averageColor(mapping, bounds)
}

private fun FrostedBackdrop.averageColor(mapping: BackdropRegistration, bounds: Rect): Color? {
    val image = snapshot?.asAndroidBitmap() ?: return null
    if (size.width <= 0 || size.height <= 0) return null
    if (materialOnly) {
        val x = (size.width * .5f * image.width / snapshotExtent.width).toInt().coerceIn(0, image.width - 1)
        val y = (size.height * .5f * image.height / snapshotExtent.height).toInt().coerceIn(0, image.height - 1)
        return Color(image.getPixel(x, y))
    }
    val corners = mapping.corners(bounds).map {
        Offset(it.x * image.width / snapshotExtent.width, it.y * image.height / snapshotExtent.height)
    }
    val left = corners.minOf { it.x }; val right = corners.maxOf { it.x }
    val top = corners.minOf { it.y }; val bottom = corners.maxOf { it.y }
    val axisAligned = mapping.xy == 0f && mapping.yx == 0f
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (y in floor(top).toInt().coerceAtLeast(0) until ceil(bottom).toInt().coerceAtMost(image.height)) {
        for (x in floor(left).toInt().coerceAtLeast(0) until ceil(right).toInt().coerceAtMost(image.width)) {
            val weight = if (axisAligned) (minOf(right, x + 1f) - maxOf(left, x.toFloat())) *
                (minOf(bottom, y + 1f) - maxOf(top, y.toFloat())) else footprintPixelArea(corners, x, y)
            if (weight <= 0f) continue
            val pixel = image.getPixel(x, y)
            val a = (pixel ushr 24) / 255f * weight
            red += (pixel ushr 16 and 255) / 255f * a
            green += (pixel ushr 8 and 255) / 255f * a
            blue += (pixel and 255) / 255f * a
            alpha += a
        }
    }
    val area = kotlin.math.abs(mapping.xx * mapping.yy - mapping.xy * mapping.yx) * bounds.width * bounds.height *
        image.width * image.height / (snapshotExtent.width.toFloat() * snapshotExtent.height)
    return if (alpha <= 0f || area <= 0f) Color.Transparent
        else Color(red / alpha, green / alpha, blue / alpha, (alpha / area).coerceIn(0f, 1f))
}

/** Polygon clipping is only needed for rotated/sheared consumers; ordinary rectangles take the fast path. */
private fun footprintPixelArea(corners: List<Offset>, x: Int, y: Int): Float {
    var polygon = corners
    for (edge in 0..3) {
        val horizontal = edge >= 2
        val boundary = when (edge) { 0 -> x.toFloat(); 1 -> x + 1f; 2 -> y.toFloat(); else -> y + 1f }
        fun coordinate(p: Offset) = if (horizontal) p.y else p.x
        fun inside(p: Offset) = if (edge % 2 == 0) coordinate(p) >= boundary else coordinate(p) <= boundary
        if (polygon.isEmpty()) return 0f
        val output = mutableListOf<Offset>()
        var previous = polygon.last()
        for (point in polygon) {
            if (inside(previous) != inside(point)) {
                val t = (boundary - coordinate(previous)) / (coordinate(point) - coordinate(previous))
                output += previous + (point - previous) * t
            }
            if (inside(point)) output += point
            previous = point
        }
        polygon = output
    }
    return kotlin.math.abs(polygon.indices.sumOf { index ->
        val a = polygon[index]; val b = polygon[(index + 1) % polygon.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) * .5f
}

internal inline fun averageGlassColor(sample: (Int, Int) -> Int): Color {
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (y in 0 until 8) for (x in 0 until 8) {
        val pixel = sample(x, y)
        val a = (pixel ushr 24) / 255f
        red += (pixel ushr 16 and 255) / 255f * a
        green += (pixel ushr 8 and 255) / 255f * a
        blue += (pixel and 255) / 255f * a
        alpha += a
    }
    return if (alpha == 0f) Color.Transparent else Color(red / alpha, green / alpha, blue / alpha, alpha / 64)

}

internal fun MaterialAverageStyle.color(mean: Color): Color {
    val tint = whiteTint.coerceIn(0f, 1f)
    val y = .2126f * mean.red + .7152f * mean.green + .0722f * mean.blue
    val lifted = 1f - (1f - y) * (1f - tint) / (1f - tint * y).coerceAtLeast(.0001f)
    val amount = ((lifted - y) / (1f - y).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
    return tone.color(lerp(mean, Color.White.copy(alpha = mean.alpha), amount)).let {
        if (opaque) it.copy(alpha = 1f) else it
    }
}

@Composable
internal fun AverageBackdrop(modifier: Modifier, source: FrostedBackdrop, location: BackdropLocation,
    style: MaterialAverageStyle, home: FrostedBackdrop? = null, homeWeight: () -> Float = { 0f },
    homeBounds: (() -> Rect)? = null, homeFootprint: (() -> HomeBackdropFootprint?)? = null, styleReader: (() -> MaterialAverageStyle)? = null) {
    if (flatGlass()) return
    val drawObserver = LocalBackdropSamplingObserver.current
    // The scene the lens would sample (and its parent outside its box), never a substitute.
    val backing = source
    val homeBacking = home
    BackdropLease(backing)
    BackdropLease(homeBacking?.takeUnless { it === backing })
    Canvas(modifier) {
        val style = styleReader?.invoke() ?: style
        val bounds = Rect(Offset.Zero, size)
        val localToSource = location.registrationTo(backing.location) ?: return@Canvas
        val primary = backing.fullBoundsMean(localToSource, bounds) {
            location.registrationTo(it.location) } ?: return@Canvas
        val weight = homeWeight().coerceIn(0f, 1f)
        val homeColor = if (homeBacking != null && weight > 0f) {
            val footprint = homeFootprint?.invoke()
            if (homeFootprint != null && footprint == null) return@Canvas
            val logicalBounds = if (homeFootprint == null) homeBounds?.invoke()?.takeUnless { it.isEmpty } else null
            val sourceToScreen = homeBacking.location.registrationToScreen()
            val mapping = when {
                footprint != null -> footprint.sourceToLocal(sourceToScreen)?.inverse()
                logicalBounds != null -> BackdropRegistration.create(1f, 0f, 0f, 1f,
                    logicalBounds.left, logicalBounds.top)?.followedBy(sourceToScreen?.inverse() ?: return@Canvas)
                else -> location.registrationTo(homeBacking.location)
            } ?: return@Canvas
            val extent = footprint?.size ?: logicalBounds?.size ?: size
            homeBacking.fullBoundsMean(mapping, Rect(Offset.Zero, extent)) { parent ->
                homeBacking.location.registrationTo(parent.location)?.let(mapping::followedBy) } ?: return@Canvas
        } else primary
        fun resolved(mean: Color, material: FrostedBackdrop) = style.forBacking(material).color(mean)
        val color = lerp(resolved(primary, backing), resolved(homeColor, homeBacking ?: backing), weight)
        drawObserver?.let { observeBackdropSampling(it, location, localToSource, bounds) }
        drawRect(color)
    }
}

/** Premultiplied coverage sums in consumer-area units. */
private class AreaSums { var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f }

private fun polygonArea(polygon: List<Offset>): Float = kotlin.math.abs(polygon.indices.sumOf { index ->
    val a = polygon[index]; val b = polygon[(index + 1) % polygon.size]
    (a.x * b.y - b.x * a.y).toDouble()
}.toFloat()) * .5f

/** Sutherland–Hodgman against a convex clip polygon of either winding. */
internal fun clipConvexPolygon(subject: List<Offset>, clip: List<Offset>): List<Offset> {
    if (subject.isEmpty() || clip.size < 3) return emptyList()
    val orientation = clip.indices.sumOf { i ->
        val a = clip[i]; val b = clip[(i + 1) % clip.size]; (a.x * b.y - b.x * a.y).toDouble()
    }.let { if (it >= 0.0) 1f else -1f }
    var output = subject
    for (i in clip.indices) {
        if (output.isEmpty()) break
        val a = clip[i]; val b = clip[(i + 1) % clip.size]
        fun side(p: Offset) = orientation * ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x))
        val input = output
        output = mutableListOf()
        var previous = input.last()
        for (point in input) {
            val inside = side(point) >= 0f
            val previousInside = side(previous) >= 0f
            if (inside != previousInside) {
                val sp = side(previous); val sc = side(point)
                output += previous + (point - previous) * (sp / (sp - sc))
            }
            if (inside) output += point
            previous = point
        }
    }
    return output
}

/** Add this snapshot's coverage over [local] (this recording's pixels), minus [excluded]. */
private fun FrostedBackdrop.accumulate(local: List<Offset>, excluded: List<Offset>?, consumerAreaPerPixel: Float,
    sums: AreaSums): Boolean {
    val image = snapshot?.asAndroidBitmap() ?: return false
    if (size.width <= 0 || size.height <= 0) return false
    val sx = image.width / snapshotExtent.width.toFloat()
    val sy = image.height / snapshotExtent.height.toFloat()
    val corners = local.map { Offset(it.x * sx, it.y * sy) }
    val cut = excluded?.let { box -> clipConvexPolygon(corners, box.map { Offset(it.x * sx, it.y * sy) }) }
        ?.takeIf { it.size >= 3 }
    val unit = consumerAreaPerPixel / (sx * sy)
    val left = corners.minOf { it.x }; val right = corners.maxOf { it.x }
    val top = corners.minOf { it.y }; val bottom = corners.maxOf { it.y }
    val axisAligned = corners.size == 4 && (corners[0].y == corners[1].y && corners[1].x == corners[2].x ||
        corners[0].x == corners[1].x && corners[1].y == corners[2].y)
    for (y in floor(top).toInt().coerceAtLeast(0) until ceil(bottom).toInt().coerceAtMost(image.height)) {
        for (x in floor(left).toInt().coerceAtLeast(0) until ceil(right).toInt().coerceAtMost(image.width)) {
            var weight = if (axisAligned) (minOf(right, x + 1f) - maxOf(left, x.toFloat())).coerceAtLeast(0f) *
                (minOf(bottom, y + 1f) - maxOf(top, y.toFloat())).coerceAtLeast(0f) else footprintPixelArea(corners, x, y)
            if (cut != null) weight -= footprintPixelArea(cut, x, y)
            if (weight <= 0f) continue
            val pixel = image.getPixel(x, y)
            val a = (pixel ushr 24) / 255f * weight * unit
            sums.red += (pixel ushr 16 and 255) / 255f * a
            sums.green += (pixel ushr 8 and 255) / 255f * a
            sums.blue += (pixel and 255) / 255f * a
            sums.alpha += a
        }
    }
    return true
}

/** Coverage-weighted mean over [region] (consumer-local, the material's full bounds)
 * of the scene the lens samples: this recording inside its box, its nearest parent outside
 * it. API 26 integrates the shared 32×32 snapshots: boundary cells contribute their cell
 * mean by overlap area, and a second ancestor beyond the parent is not consulted. Area
 * outside every available recording (or a parent without a snapshot yet) does not count. */
internal fun FrostedBackdrop.fullBoundsMean(toLocal: BackdropRegistration, region: Rect,
    toParent: (FrostedBackdrop) -> BackdropRegistration?): Color? {
    if (region.isEmpty) return null
    val determinant = kotlin.math.abs(toLocal.xx * toLocal.yy - toLocal.xy * toLocal.yx)
    if (!(determinant > 0f)) return null
    val sums = AreaSums()
    val local = toLocal.corners(region)
    if (!accumulate(local, null, 1f / determinant, sums)) return null
    val total = region.width * region.height
    val box = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
    val boxCorners = listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft)
    val insideArea = polygonArea(clipConvexPolygon(local, boxCorners)) / determinant
    // Nothing outside the recorded scene exists to read; only recorded area counts.
    var available = insideArea
    val parent = parent
    if (insideArea < total * .999f && parent != null && parent !== this) {
        val parentMapping = toParent(parent)
        val parentDeterminant = parentMapping?.let { kotlin.math.abs(it.xx * it.yy - it.xy * it.yx) } ?: 0f
        val boxInParent = parentMapping?.let { mapping ->
            toLocal.inverse()?.followedBy(mapping)?.let { m -> boxCorners.map(m::map) } }
        if (parentMapping != null && parentDeterminant > 0f && boxInParent != null &&
            parent.accumulate(parentMapping.corners(region), boxInParent, 1f / parentDeterminant, sums)) {
            val parentBox = listOf(Offset.Zero, Offset(parent.size.width.toFloat(), 0f),
                Offset(parent.size.width.toFloat(), parent.size.height.toFloat()), Offset(0f, parent.size.height.toFloat()))
            val inParent = clipConvexPolygon(parentMapping.corners(region), parentBox)
            val overlap = if (inParent.size >= 3) polygonArea(clipConvexPolygon(inParent, boxInParent)) else 0f
            available = (insideArea + (polygonArea(inParent) - overlap) / parentDeterminant).coerceAtMost(total)
        }
    }
    return if (sums.alpha <= 0f || available <= 0f) Color.Transparent
        else Color(sums.red / sums.alpha, sums.green / sums.alpha, sums.blue / sums.alpha, (sums.alpha / available).coerceIn(0f, 1f))
}

/** Premultiplied mean of a rasterized region; alpha is its coverage of [area] pixels. */
internal fun averageGlassPixels(pixels: IntArray, area: Float): Color {
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (pixel in pixels) {
        val a = (pixel ushr 24) / 255f
        red += (pixel ushr 16 and 255) / 255f * a
        green += (pixel ushr 8 and 255) / 255f * a
        blue += (pixel and 255) / 255f * a
        alpha += a
    }
    return if (alpha == 0f || area <= 0f) Color.Transparent
        else Color(red / alpha, green / alpha, blue / alpha, (alpha / area).coerceIn(0f, 1f))
}
