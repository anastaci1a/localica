package io.github.anastaci1a.localica.ui

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
    val washPaint = remember(wash) {
        wash?.let { bitmap -> android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        } }
    }
    val softWashPaint = remember(softWash) {
        softWash?.let { bitmap -> android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        } }
    }
    val washMatrix = remember { android.graphics.Matrix() }
    val softWashMatrix = remember { android.graphics.Matrix() }
    val grainPaint = remember {
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(playerGrain, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    }
    val grainMatrix = remember { android.graphics.Matrix() }
    val paintReady = fullArtBlend <= 0f || (
        (palette.wash == null || paintedWashReceipt?.matches(palette.wash, wash, tone) == true) &&
        (softness <= 0f || palette.softWash == null || paintedSoftReceipt?.matches(palette.softWash, softWash, tone) == true))
    Canvas(modifier) {
        // Measurement/preparation stays mounted; no raw or stale-tone fallback reaches the first draw.
        if (!ready || !paintReady) return@Canvas
        drawRect(Brush.verticalGradient(listOf(tone.color(palette.top), tone.color(palette.middle), tone.color(palette.bottom))))
        drawRect(Brush.radialGradient(listOf(tone.color(palette.top).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.4f), radius = size.width * 1.2f))
        if (washPaint != null && fullArtBlend > 0f && artHeight > 0f) {
            washMatrix.setScale((artWidth.takeIf { it > 0f } ?: size.width) / ArtworkWash.WIDTH, artHeight / ArtworkWash.WIDTH)
            washMatrix.postTranslate(artLeft, (artTopReader?.invoke() ?: artTop) + artHeight * ArtworkWash.TOP)
            washPaint.shader.setLocalMatrix(washMatrix)
            washPaint.alpha = (255 * fullArtBlend).toInt().coerceIn(0, 255)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, washPaint) }
            if (softWashPaint != null && softness > 0f) {
                val soft = requireNotNull(softWash)
                softWashMatrix.set(washMatrix)
                softWashMatrix.preScale(ArtworkWash.WIDTH.toFloat() / soft.width, ArtworkWash.HEIGHT.toFloat() / soft.height)
                softWashPaint.shader.setLocalMatrix(softWashMatrix)
                softWashPaint.alpha = (255 * fullArtBlend * softness).toInt().coerceIn(0, 255)
                drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, softWashPaint) }
            }
        }
        grainMatrix.setScale(size.width / 512f, size.height / 1024f)
        grainPaint.shader.setLocalMatrix(grainMatrix)
        grainPaint.alpha = (255 * (1f - fullArtBlend * .8f)).toInt()
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, grainPaint) }
    }
}

/** Shared immutable grain avoids generating a large texture when a neighbor enters a swipe. */
private val playerGrain: android.graphics.Bitmap by lazy {
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
