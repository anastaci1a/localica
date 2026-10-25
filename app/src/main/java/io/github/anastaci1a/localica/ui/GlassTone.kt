package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Retain completed positive-tone content while a replacement image/tone is prepared.
 * The optional preparation seam is for deterministic state tests; ordinary callers keep
 * the identical cache and Dispatchers.Default preparation path. No bitmap is recycled here.
 */
@Composable internal fun rememberToneSnapshot(image: ImageBitmap?, tone: GlassTone,
    prepare: (suspend (ImageBitmap, GlassTone) -> ImageBitmap)? = null): ImageBitmap? {
    val cached = remember(image, tone, prepare) {
        image?.takeIf { tone.trim > 0f && prepare == null }?.let { ToneImages.images.get(ToneImages.Key(it, tone)) }
    }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image, tone, prepare) {
        val replacement = if (image == null || tone.trim == 0f) image
            else if (prepare != null) prepare(image, tone)
            else withContext(Dispatchers.Default) { ToneImages.get(image, tone) }
        // A suspended preparer may finish non-cancellable owned work after replacement.
        // Its retired effect must never publish into the retained result.
        currentCoroutineContext().ensureActive()
        result = replacement
    }
    return if (image == null || tone.trim == 0f) image else cached ?: result ?: image
}

/** Shared post-optics mapping, also used by the material-only average. */
internal const val GlassColorShader = """
    float3 glassColor(float3 color, float whiteTint, float3 tone) {
        float luma = clamp(dot(color, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
        float lifted = 1.0 - (1.0 - luma) * (1.0 - whiteTint) / max(1.0 - whiteTint * luma, 0.0001);
        float tint = clamp((lifted - luma) / max(1.0 - luma, 0.0001), 0.0, 1.0);
        color = mix(color, float3(1), tint);
        color = clamp(color, 0.0, 1.0);
        if (tone.x > 0.0) {
            float3 end = tone.z > 0.5 ? color : 1.0 - color;
            float3 weighted = tone.y == 2.0 ? end * end : tone.y == 1.0 ? end : pow(end, float3(tone.y));
            color += weighted * (tone.z > 0.5 ? -tone.x : tone.x);
        }
        return color;
    }
"""

/** The same mapping for broad frosted backings; foregrounds stay outside the pass. */
@androidx.annotation.RequiresApi(33)
internal class GlassToneRenderer {
    private val shader = android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float3 tone;
        $GlassColorShader
        half4 main(float2 p) {
            half4 c = scene.eval(p);
            return half4(half3(glassColor(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, tone) * float(c.a)), c.a);
        }
    """)
    fun effect(tone: GlassTone, blur: Float): RenderEffect {
        shader.setFloatUniform("tone", tone.trim, tone.weight, if (tone.lightInk) 1f else 0f)
        val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
        return android.graphics.RenderEffect.createChainEffect(effect,
            android.graphics.RenderEffect.createBlurEffect(blur.coerceAtLeast(.01f), blur.coerceAtLeast(.01f), android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
    }
}
