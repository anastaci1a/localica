package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Frozen 4315f7ae backdrop body and painter; only private declarations are renamed. */
@Composable
internal fun ReferencePlayerBackdrop(palette: PlayerPalette, modifier: Modifier = Modifier, fullArtBlend: Float = 0f,
    artTop: Float = 0f, artHeight: Float = 0f, artLeft: Float = 0f, artWidth: Float = 0f, softness: Float = 0f, tone: GlassTone = GlassTone(),
    artTopReader: (() -> Float)? = null,
    onBackgroundDescriptor: ((PlayerBackdropDescriptor) -> Unit)? = null, ready: Boolean = true) {
    var washProvenance: ToneSnapshotProvenance? = null
    var softWashProvenance: ToneSnapshotProvenance? = null
    var washReceipt: ToneSnapshotReceipt<android.graphics.Bitmap>? = null
    var softWashReceipt: ToneSnapshotReceipt<android.graphics.Bitmap>? = null
    var paintedWashReceipt: ToneSnapshotReceipt<android.graphics.Bitmap>? = null
    var paintedSoftReceipt: ToneSnapshotReceipt<android.graphics.Bitmap>? = null
    val wash = rememberToneSnapshot(remember(palette.wash) { palette.wash?.asImageBitmap() }, tone,
        onReceipt = { paintedWashReceipt = it })?.asAndroidBitmap()
    val softWash = rememberToneSnapshot(remember(palette.softWash) { palette.softWash?.asImageBitmap() }, tone,
        onReceipt = { paintedSoftReceipt = it })?.asAndroidBitmap()
    // The ink decision measures the UNGRADED backdrop (#576): the grade depends on the chosen ink, so measuring
    // graded pixels would be circular. These untoned snapshots are the raw palette images the sampler reads.
    val measured = GlassTone()
    val rawWash = if (onBackgroundDescriptor != null) rememberToneSnapshot(remember(palette.wash) { palette.wash?.asImageBitmap() }, measured,
        onProvenance = { washProvenance = it }, onReceipt = { washReceipt = it })?.asAndroidBitmap() else null
    val rawSoftWash = if (onBackgroundDescriptor != null) rememberToneSnapshot(remember(palette.softWash) { palette.softWash?.asImageBitmap() }, measured,
        onProvenance = { softWashProvenance = it }, onReceipt = { softWashReceipt = it })?.asAndroidBitmap() else null
    if (onBackgroundDescriptor != null) {
        val descriptor = PlayerBackdropDescriptor(palette, rawWash, rawSoftWash, playerGrain, measured,
            washProvenance, softWashProvenance, washReceipt, softWashReceipt)
        SideEffect { onBackgroundDescriptor(descriptor) }
    }
    val paintReady = fullArtBlend <= 0f || (
        (palette.wash == null || paintedWashReceipt?.matches(palette.wash, wash, tone) == true) &&
        (softness <= 0f || palette.softWash == null || paintedSoftReceipt?.matches(palette.softWash, softWash, tone) == true))
    // #634: what this backdrop draws now. A prepared paint draws exactly as before. Until its replacement is
    // prepared, a drawn paint stays (a grade or tone change never blanks the backdrop). A role whose grade is
    // not known yet (an expansion or first open that began before preparation finished) draws the same art
    // ungraded, its best available form, and eases into the grade; it never draws nothing over an empty window.
    val transition = remember { ReferenceBackdropPaintTransition() }
    val prepared = if (ready && paintReady) ReferenceBackdropPaint(palette, tone, wash, softWash) else null
    val step = transition.resolve(prepared ?: transition.shown?.takeIf { it.palette == palette }
        ?: if (!ready) ReferenceBackdropPaint(palette, GlassTone(), palette.wash, palette.softWash, provisional = true) else null)
    SideEffect { transition.commit(step) }
    val ease = remember(step.from) { Animatable(if (step.from == null) 1f else 0f) }
    LaunchedEffect(ease) {
        if (ease.value < 1f) {
            ease.animateTo(1f, tween(ReferenceBackdropPaintTransition.EASE_MILLIS))
            transition.eased()
        }
    }
    val painter = remember { ReferenceBackdropPainter() }
    Canvas(modifier) {
        val shown = step.shown ?: return@Canvas
        val from = step.from
        val blend = if (from == null) 1f else ease.value
        val geometry = ReferenceBackdropDrawGeometry(fullArtBlend, artTopReader?.invoke() ?: artTop, artHeight, artLeft, artWidth, softness)
        if (from != null && blend < 1f) {
            painter.draw(this, from, geometry)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), Paint().apply { alpha = blend })
                painter.draw(this, shown, geometry)
                canvas.restore()
            }
        } else painter.draw(this, shown, geometry)
    }
}

/** One complete set of backdrop pixels: the palette, the tone it is drawn with and that tone's prepared washes. */
private data class ReferenceBackdropPaint(val palette: PlayerPalette, val tone: GlassTone, val wash: android.graphics.Bitmap?,
    val softWash: android.graphics.Bitmap?, val provisional: Boolean = false)
private class ReferenceBackdropDrawGeometry(val fullArtBlend: Float, val artTop: Float, val artHeight: Float, val artLeft: Float,
    val artWidth: Float, val softness: Float)

/** Pure retention: the shown paint, and the provisional paint it eases from once the graded one is prepared. */
private class ReferenceBackdropPaintTransition {
    data class Step(val shown: ReferenceBackdropPaint?, val from: ReferenceBackdropPaint?)
    private var committed = Step(null, null)
    private var easedFrom: ReferenceBackdropPaint? = null
    val shown: ReferenceBackdropPaint? get() = committed.shown
    fun resolve(target: ReferenceBackdropPaint?): Step {
        val old = committed
        if (target == null) return Step(null, null)
        // An unfinished ease continues toward the same target; its start never jumps.
        if (old.from != null && old.from !== easedFrom && target == old.shown) return old
        val previous = old.shown
        return if (previous != null && previous.provisional && !target.provisional && previous.palette == target.palette &&
            previous.tone != target.tone) Step(target, previous) else Step(target, null)
    }
    fun commit(step: Step) { committed = step }
    fun eased() { easedFrom = committed.from }
    companion object { const val EASE_MILLIS = 240 }
}

/** The unchanged backdrop drawing for one [ReferenceBackdropPaint]; shaders are kept per prepared bitmap. */
private class ReferenceBackdropPainter {
    private val shaders = HashMap<android.graphics.Bitmap, android.graphics.Paint>()
    private val washMatrix = android.graphics.Matrix()
    private val softWashMatrix = android.graphics.Matrix()
    private val grainMatrix = android.graphics.Matrix()
    private val grainPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        shader = android.graphics.BitmapShader(playerGrain, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    }
    private fun paint(bitmap: android.graphics.Bitmap): android.graphics.Paint = shaders.getOrPut(bitmap) {
        // Only the shown and easing paints are ever drawn: a few entries per backdrop.
        if (shaders.size >= 4) shaders.clear()
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    }
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, shown: ReferenceBackdropPaint, g: ReferenceBackdropDrawGeometry) = with(scope) {
        val palette = shown.palette
        val tone = shown.tone
        drawRect(Brush.verticalGradient(listOf(tone.color(palette.top), tone.color(palette.middle), tone.color(palette.bottom))))
        drawRect(Brush.radialGradient(listOf(tone.color(palette.top).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.4f), radius = size.width * 1.2f))
        val wash = shown.wash
        if (wash != null && g.fullArtBlend > 0f && g.artHeight > 0f) {
            val washPaint = paint(wash)
            washMatrix.setScale((g.artWidth.takeIf { it > 0f } ?: size.width) / ArtworkWash.WIDTH, g.artHeight / ArtworkWash.WIDTH)
            washMatrix.postTranslate(g.artLeft, g.artTop + g.artHeight * ArtworkWash.TOP)
            washPaint.shader.setLocalMatrix(washMatrix)
            washPaint.alpha = (255 * g.fullArtBlend).toInt().coerceIn(0, 255)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, washPaint) }
            val soft = shown.softWash
            if (soft != null && g.softness > 0f) {
                val softWashPaint = paint(soft)
                softWashMatrix.set(washMatrix)
                softWashMatrix.preScale(ArtworkWash.WIDTH.toFloat() / soft.width, ArtworkWash.HEIGHT.toFloat() / soft.height)
                softWashPaint.shader.setLocalMatrix(softWashMatrix)
                softWashPaint.alpha = (255 * g.fullArtBlend * g.softness).toInt().coerceIn(0, 255)
                drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, softWashPaint) }
            }
        }
        grainMatrix.setScale(size.width / 512f, size.height / 1024f)
        grainPaint.shader.setLocalMatrix(grainMatrix)
        grainPaint.alpha = (255 * (1f - g.fullArtBlend * .8f)).toInt()
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, grainPaint) }
    }
}

