package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Ordered compression: no brightness inversion, including at maximum trim/weight. */
internal data class GlassTone(val trim: Float = 0f, val weight: Float = 2f, val lightInk: Boolean = false) {
    fun channel(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val t = trim.coerceIn(0f, .5f)
        val p = weight.coerceIn(1f, 2f)
        return if (lightInk) x - t * x.pow(p) else x + t * (1f - x).pow(p)
    }
    fun color(color: Color) = Color(channel(color.red), channel(color.green), channel(color.blue), color.alpha)
}

/** Older adapters share bounded prepared pixels, never a readback or per-frame CPU filter. */
private object ToneImages {
    data class Key(val image: ImageBitmap, val tone: GlassTone)
    val images = object : LruCache<Key, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * 4
    }
    val mutex = Mutex()
    suspend fun get(image: ImageBitmap, tone: GlassTone): ImageBitmap = mutex.withLock {
        val key = Key(image, tone)
        images.get(key)?.let { return@withLock it }
        val original = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(original.width * original.height)
        original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
        val lut = IntArray(256) { (tone.channel(it / 255f) * 255f + .5f).toInt() }
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (c and -0x1000000) or (lut[c ushr 16 and 255] shl 16) or
                (lut[c ushr 8 and 255] shl 8) or lut[c and 255]
        }
        Bitmap.createBitmap(pixels, original.width, original.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap().also { original.recycle(); images.put(key, it) }
    }
}

@Composable internal fun rememberToneSnapshot(image: ImageBitmap?, tone: GlassTone): ImageBitmap? {
    val cached = remember(image, tone) {
        image?.takeIf { tone.trim > 0f }?.let { ToneImages.images.get(ToneImages.Key(it, tone)) }
    }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image, tone) {
        result = if (image == null || tone.trim == 0f) image else withContext(Dispatchers.Default) { ToneImages.get(image, tone) }
    }
    return if (image == null || tone.trim == 0f) image else cached ?: result ?: image
}
