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

internal data class PlayerPalette(val top: Color, val middle: Color, val bottom: Color, val aspect: Float = 1f,
    val wash: android.graphics.Bitmap? = null, val softWash: android.graphics.Bitmap? = null, val collectionWash: android.graphics.Bitmap? = null)
internal fun PlayerPalette.isLight(): Boolean = middle.luminance() > .45f

internal val NeutralPalette = PlayerPalette(Color(0xFF424C56), Color(0xFF343C47), Color(0xFF242B34))

/** A bounded memory-only seed: 108 pixels, no resize, decode or blur on the UI thread. */
internal fun thumbnailPalette(bitmap: android.graphics.Bitmap): PlayerPalette {
    fun band(part: Int): Color {
        var r = 0; var g = 0; var b = 0; var count = 0
        for (y in 0 until 6) for (x in 0 until 6) {
            val pixel = bitmap.getPixel(((x + .5f) * bitmap.width / 6).toInt().coerceAtMost(bitmap.width - 1),
                ((part + (y + .5f) / 6) * bitmap.height / 3).toInt().coerceAtMost(bitmap.height - 1))
            if (android.graphics.Color.alpha(pixel) >= 128) {
                r += android.graphics.Color.red(pixel); g += android.graphics.Color.green(pixel)
                b += android.graphics.Color.blue(pixel); count++
            }
        }
        return if (count == 0) Color.DarkGray else Color(r / count, g / count, b / count)
    }
    return PlayerPalette(band(0), band(1), band(2), bitmap.width.toFloat() / bitmap.height)
}

/** Pure palette-color retargeting on the artwork reveal clock. */
internal class PlayerPaletteTransition(initial: PlayerPalette) {
    internal data class Step(val shown: PlayerPalette, val start: PlayerPalette, val target: PlayerPalette,
        val startProgress: Float = 0f, val lastProgress: Float = 1f)
    private var committed = Step(initial, initial, initial)
    fun resolve(target: PlayerPalette, clock: Float): Step {
        val progress = clock.coerceIn(0f, 1f)
        if (progress >= 1f) return Step(target, target, target)
        val old = committed
        val restart = target != old.target || progress < old.lastProgress - .001f
        val start = if (restart) old.shown else old.start
        val startProgress = if (restart) progress else old.startProgress
        val fraction = ((progress - startProgress) / (1f - startProgress).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val shown = target.copy(top = lerp(start.top, target.top, fraction),
            middle = lerp(start.middle, target.middle, fraction), bottom = lerp(start.bottom, target.bottom, fraction))
        return Step(shown, start, target, startProgress, progress)
    }
    fun commit(step: Step) { committed = step }
}

@Composable
internal fun animatedPlayerPalette(palette: PlayerPalette, clock: Float): PlayerPalette {
    val transition = remember { PlayerPaletteTransition(palette) }
    val step = transition.resolve(palette, clock)
    SideEffect { transition.commit(step) }
    return step.shown
}

/** Derived with the full cover, so both become ready in the same frame. */
internal suspend fun playerPalette(bitmap: android.graphics.Bitmap): PlayerPalette = withContext(Dispatchers.Default) {
    val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 96,
        (96f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
    fun band(from: Float, to: Float): Color {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        for (y in (sample.height * from).toInt() until (sample.height * to).toInt() step 2) {
            for (x in 0 until sample.width step 2) {
                val pixel = sample.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) < 128) continue
                red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel)
                blue += android.graphics.Color.blue(pixel); count++
            }
        }
        if (count == 0L) return NeutralPalette.middle
        return Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }
    // Color statistics may stay tiny; the visible feather continuation must not.
    val wash = ArtworkWash.create(bitmap)
    // The transition's deliberately soft endpoint needs no extra detail. Preserve
    // its old filter resolution/cost rather than scaling all preparation with wash.
    val softSource = android.graphics.Bitmap.createScaledBitmap(wash, ArtworkWash.BLUR_WIDTH,
        ArtworkWash.BLUR_WIDTH * 5 / 2, true)
    val soft = try {
        blurGlassSnapshot(softSource.asImageBitmap(), ArtworkWash.BLUR_WIDTH * .12f).asAndroidBitmap()
    } finally {
        if (softSource !== wash) softSource.recycle()
    }
    PlayerPalette(band(0f, .3f), band(.3f, .7f), band(.7f, 1f), bitmap.width.toFloat() / bitmap.height,
        wash, soft, ArtworkWash.collectionShade(wash))
}

/** Low-frequency artwork colors and fixed fine grain give a matte surface without a blur dependency. */
@Composable
internal fun PlayerBackdrop(palette: PlayerPalette, modifier: Modifier = Modifier, fullArtBlend: Float = 0f,
    artTop: Float = 0f, artHeight: Float = 0f, artLeft: Float = 0f, artWidth: Float = 0f, softness: Float = 0f, tone: GlassTone = GlassTone(),
    artTopReader: (() -> Float)? = null,
    onBackgroundDescriptor: ((PlayerBackdropDescriptor) -> Unit)? = null, ready: Boolean = true,
    geometryReader: (() -> BackdropDrawGeometry)? = null) {
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
    // Geometry affects paint admission as well as Canvas drawing. Observe only its
    // readiness predicates, retaining the original transition decisions at their crossings.
    val readiness = remember(geometryReader, fullArtBlend, softness) { derivedStateOf {
        val geometry = geometryReader?.invoke()
        ((geometry?.fullArtBlend ?: fullArtBlend) > 0f) to ((geometry?.softness ?: softness) > 0f)
    } }.value
    val paintReady = !readiness.first || (
        (palette.wash == null || paintedWashReceipt?.matches(palette.wash, wash, tone) == true) &&
        (!readiness.second || palette.softWash == null || paintedSoftReceipt?.matches(palette.softWash, softWash, tone) == true))
    // #634: what this backdrop draws now. A prepared paint draws exactly as before. Until its replacement is
    // prepared, a drawn paint stays (a grade or tone change never blanks the backdrop). A role whose grade is
    // not known yet (an expansion or first open that began before preparation finished) draws the same art
    // ungraded, its best available form, and eases into the grade; it never draws nothing over an empty window.
    val transition = remember { BackdropPaintTransition() }
    val prepared = if (ready && paintReady) BackdropPaint(palette, tone, wash, softWash) else null
    val step = transition.resolve(prepared ?: transition.shown?.takeIf { it.palette == palette }
        ?: if (!ready) BackdropPaint(palette, GlassTone(), palette.wash, palette.softWash, provisional = true) else null)
    SideEffect { transition.commit(step) }
    val ease = remember(step.from) { Animatable(if (step.from == null) 1f else 0f) }
    LaunchedEffect(ease) {
        if (ease.value < 1f) {
            ease.animateTo(1f, tween(BackdropPaintTransition.EASE_MILLIS))
            transition.eased()
        }
    }
    val painter = remember { BackdropPainter() }
    Canvas(modifier) {
        val shown = step.shown ?: return@Canvas
        val from = step.from
        val blend = if (from == null) 1f else ease.value
        val geometry = geometryReader?.invoke() ?: BackdropDrawGeometry(fullArtBlend, artTopReader?.invoke() ?: artTop, artHeight, artLeft, artWidth, softness)
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
private data class BackdropPaint(val palette: PlayerPalette, val tone: GlassTone, val wash: android.graphics.Bitmap?,
    val softWash: android.graphics.Bitmap?, val provisional: Boolean = false)
internal class BackdropDrawGeometry(val fullArtBlend: Float, val artTop: Float, val artHeight: Float, val artLeft: Float,
    val artWidth: Float, val softness: Float)

/** Pure retention: the shown paint, and the provisional paint it eases from once the graded one is prepared. */
private class BackdropPaintTransition {
    data class Step(val shown: BackdropPaint?, val from: BackdropPaint?)
    private var committed = Step(null, null)
    private var easedFrom: BackdropPaint? = null
    val shown: BackdropPaint? get() = committed.shown
    fun resolve(target: BackdropPaint?): Step {
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

/** The unchanged backdrop drawing for one [BackdropPaint]; shaders are kept per prepared bitmap. */
private class BackdropPainter {
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
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, shown: BackdropPaint, g: BackdropDrawGeometry) = with(scope) {
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

/** Shared immutable grain avoids generating a large texture when a neighbor enters a swipe. */
internal val playerGrain: android.graphics.Bitmap by lazy {
    val random = kotlin.random.Random(417)
    // One low-contrast field spans the whole surface. No small repeating tile and no
    // net brightening: both dark and light grains sit over the diffused color layers.
    val bitmap = android.graphics.Bitmap.createBitmap(512, 1024, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(IntArray(512 * 1024) {
        val tone = if (random.nextBoolean()) 255 else 0
        android.graphics.Color.argb(random.nextInt(7), tone, tone, tone)
    }, 0, 512, 0, 0, 512, 1024)
    bitmap
}
