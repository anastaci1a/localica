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
    val wash: android.graphics.Bitmap? = null)
internal val NeutralPalette = PlayerPalette(Color(0xFF424C56), Color(0xFF343C47), Color(0xFF242B34))

@Composable
internal fun animatedPlayerPalette(palette: PlayerPalette, duration: Int): PlayerPalette {
    val top by androidx.compose.animation.animateColorAsState(palette.top, androidx.compose.animation.core.tween(duration), label = "Player top color")
    val middle by androidx.compose.animation.animateColorAsState(palette.middle, androidx.compose.animation.core.tween(duration), label = "Player middle color")
    val bottom by androidx.compose.animation.animateColorAsState(palette.bottom, androidx.compose.animation.core.tween(duration), label = "Player bottom color")
    val aspect by androidx.compose.animation.core.animateFloatAsState(palette.aspect, androidx.compose.animation.core.tween(duration), label = "Cover proportions")
    return palette.copy(top = top, middle = middle, bottom = bottom, aspect = aspect)
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
        return lerp(Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()), Color.Black, 0.4f)
    }
    PlayerPalette(band(0f, .3f), band(.3f, .7f), band(.7f, 1f), bitmap.width.toFloat() / bitmap.height,
        ArtworkWash.create(sample))
}

/** Low-frequency artwork colors and fixed fine grain give a matte surface without a blur dependency. */
@Composable
internal fun PlayerBackdrop(palette: PlayerPalette, modifier: Modifier = Modifier, fullArtBlend: Float = 0f,
    artTop: Float = 0f, artHeight: Float = 0f, artLeft: Float = 0f, artWidth: Float = 0f) {
    val washPaint = remember(palette.wash) {
        palette.wash?.let { bitmap -> android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        } }
    }
    val washMatrix = remember { android.graphics.Matrix() }
    val grainPaint = remember {
        val random = kotlin.random.Random(417)
        // One low-contrast field spans the whole surface. No small repeating tile and no
        // net brightening: both dark and light grains sit over the diffused color layers.
        val bitmap = android.graphics.Bitmap.createBitmap(512, 1024, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(512 * 1024) {
            val tone = if (random.nextBoolean()) 255 else 0
            android.graphics.Color.argb(random.nextInt(7), tone, tone, tone)
        }, 0, 512, 0, 0, 512, 1024)
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
    }
    val grainMatrix = remember { android.graphics.Matrix() }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(palette.top, palette.middle, palette.bottom)))
        drawRect(Brush.radialGradient(listOf(palette.top.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.4f), radius = size.width * 1.2f))
        if (washPaint != null && fullArtBlend > 0f && artHeight > 0f) {
            washMatrix.setScale((artWidth.takeIf { it > 0f } ?: size.width) / ArtworkWash.WIDTH, artHeight / ArtworkWash.WIDTH)
            washMatrix.postTranslate(artLeft, artTop + artHeight * ArtworkWash.TOP)
            washPaint.shader.setLocalMatrix(washMatrix)
            washPaint.alpha = (255 * fullArtBlend).toInt().coerceIn(0, 255)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, washPaint) }
        }
        grainMatrix.setScale(size.width / 512f, size.height / 1024f)
        grainPaint.shader.setLocalMatrix(grainMatrix)
        grainPaint.alpha = (255 * (1f - fullArtBlend * .8f)).toInt()
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, grainPaint) }
    }
}
