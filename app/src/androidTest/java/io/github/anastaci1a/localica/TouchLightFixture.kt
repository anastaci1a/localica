package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*

internal fun fusedTestValue(a: Double, b: Double, c: Double) = java.math.BigDecimal(a).multiply(java.math.BigDecimal(b)).add(java.math.BigDecimal(c)).toDouble()

internal fun glowRecords(body: GlassMomentum) = body.javaClass.getDeclaredField("locals").apply { isAccessible = true }.get(body) as List<*>
internal fun glowChannels(record: Any) = record.javaClass.getDeclaredField("light").apply { isAccessible = true }.get(record) as List<*>
internal fun glowChannels(body: GlassMomentum): List<Any> = listOf(requireNotNull(body.javaClass.getDeclaredField("broad").apply { isAccessible = true }.get(body))) + (glowRecords(body).lastOrNull()?.let { glowChannels(it).map { s -> s!! } }.orEmpty())
internal fun seedGlowEpoch(body: GlassMomentum) {
    glowRecords(body).forEach { record ->
        record!!
        val alpha = glowChannels(record).first()!!
        for ((dest, source) in listOf("origin" to "value", "originVelocity" to "velocity"))
            record.javaClass.getDeclaredField(dest).apply { isAccessible = true }.setFloat(record, alpha.javaClass.getDeclaredField(source).apply { isAccessible = true }.getFloat(alpha))
        record.javaClass.getDeclaredField("elapsed").apply { isAccessible = true }.setDouble(record, 0.0)
    }
}

/** Execute the production pass list against successive immutable destinations. */
internal fun DrawScope.drawTouchLightFixture(glow: GlassGlow, shape: GlassShape, vertical: Boolean,
    cache: ContactGlowCache, bounds: Rect = Rect(Offset.Zero, size), sample: Color = Color(0xff405060), corner: Float = 0f, backing: DrawScope.() -> Unit) {
    var image = ImageBitmap(kotlin.math.ceil(size.width).toInt(), kotlin.math.ceil(size.height).toInt())
    fun draw(target: ImageBitmap, block: DrawScope.() -> Unit) = CanvasDrawScope().draw(Density(density), layoutDirection, Canvas(target), size, block)
    try {
        draw(image, backing)
        for (pass in contactGlowPasses(glow, shape, vertical, cache, bounds, sample, corner = corner)) {
            val prior = image
            image = ImageBitmap(prior.width, prior.height)
            draw(image) { drawImage(prior); pass { drawImage(prior) } }
            prior.asAndroidBitmap().recycle()
        }
        drawImage(image)
    } finally { image.asAndroidBitmap().recycle() }
}
