package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import io.github.anastaci1a.localica.library.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlayerPalette(val top: Color, val middle: Color, val bottom: Color, val aspect: Float = 1f,
    val wash: android.graphics.Bitmap? = null)
private val NeutralPalette = PlayerPalette(Color(0xFF424C56), Color(0xFF343C47), Color(0xFF242B34))

@Composable
internal fun rememberPlayerPalette(uri: String?): PlayerPalette {
    val context = LocalContext.current.applicationContext
    val revision by ArtworkCache.revision.collectAsState()
    val palette by produceState(NeutralPalette, uri, revision) {
        value = NeutralPalette
        val bitmap = uri?.let { ArtworkCache.read(context, it, 128) } ?: return@produceState
        value = withContext(Dispatchers.Default) {
            fun band(from: Float, to: Float): Color {
                var red = 0L; var green = 0L; var blue = 0L; var count = 0L
                for (y in (bitmap.height * from).toInt() until (bitmap.height * to).toInt() step 3) {
                    for (x in 0 until bitmap.width step 3) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(pixel) < 128) continue
                        red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel)
                        blue += android.graphics.Color.blue(pixel); count++
                    }
                }
                if (count == 0L) return NeutralPalette.middle
                return lerp(Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()), Color.Black, 0.4f)
            }
            PlayerPalette(band(0f, 0.3f), band(0.3f, 0.7f), band(0.7f, 1f), bitmap.width.toFloat() / bitmap.height,
                ArtworkWash.create(bitmap))
        }
    }
    return palette
}

/** Low-frequency artwork colors and fixed fine grain give a matte surface without a blur dependency. */
@Composable
internal fun PlayerBackdrop(palette: PlayerPalette, modifier: Modifier = Modifier, fullArtBlend: Float = 0f,
    artTop: Float = 0f, artHeight: Float = 0f) {
    val washPaint = remember(palette.wash) {
        palette.wash?.let { bitmap -> android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        } }
    }
    val washMatrix = remember { android.graphics.Matrix() }
    val grain = remember {
        val random = kotlin.random.Random(417)
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(32 * 32) { android.graphics.Color.argb(random.nextInt(18), 255, 255, 255) }, 0, 32, 0, 0, 32, 32)
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(palette.top, palette.middle, palette.bottom)))
        drawRect(Brush.radialGradient(listOf(palette.top.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.4f), radius = size.width * 1.2f))
        if (washPaint != null && fullArtBlend > 0f && artHeight > 0f) {
            washMatrix.setScale(size.width / ArtworkWash.WIDTH, artHeight / ArtworkWash.WIDTH)
            washMatrix.postTranslate(0f, artTop + artHeight * ArtworkWash.TOP)
            washPaint.shader.setLocalMatrix(washMatrix)
            washPaint.alpha = (255 * fullArtBlend).toInt().coerceIn(0, 255)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, washPaint) }
        }
        drawRect(grain, alpha = 1f - fullArtBlend * 0.75f)
    }
}
