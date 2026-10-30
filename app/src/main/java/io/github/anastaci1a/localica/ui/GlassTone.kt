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
    /** Same rounded 8-bit stage as the prepared image, before bitmap filtering. */
    fun byteChannel(value: Int): Int = (channel(value / 255f) * 255f + .5f).toInt()
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
        val source = readGlassPixels(image, 1)
        val pixels = source.pixels
        val lut = IntArray(256) { tone.byteChannel(it) }
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = (c and -0x1000000) or (lut[c ushr 16 and 255] shl 16) or
                (lut[c ushr 8 and 255] shl 8) or lut[c and 255]
        }
        Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap().also { images.put(key, it) }
    }
}

/** Diagnostic identity hashes only: no source/returned bitmap is retained by this metadata.
 * Hashes are correlation hints, not globally unique resource IDs. */
internal data class ToneImageIdentity(val image: Int, val bitmap: Int)
private fun ImageBitmap?.toneIdentity(): ToneImageIdentity? = this?.let {
    ToneImageIdentity(System.identityHashCode(it), System.identityHashCode(it.asAndroidBitmap()))
}
internal enum class ToneSnapshotPath { CacheHit, CompletedResult, RawFallback, UntonedInput, CompletionMetadataUnavailable }
/** [appliedTone] is null for raw or unverified pixels; requested tone never describes retained pixels.
 * A custom preparation seam is identified separately: its output is not asserted to apply the curve. */
internal data class ToneSnapshotProvenance(
    val returned: ToneImageIdentity?, val source: ToneImageIdentity?, val appliedTone: GlassTone?,
    val path: ToneSnapshotPath, val requestedSource: ToneImageIdentity?, val requestedTone: GlassTone,
    val customPreparation: Boolean = false,
)
/** Reference identity receipt, separate from diagnostic hashes. It acknowledges preparation,
 * not native presentation. At most the one retained completion is owned by this composable. */
internal data class ToneSnapshotReceipt<T : Any>(private val source: T?, private val output: T?,
    private val appliedTone: GlassTone?, private val custom: Boolean = false) {
    fun matches(source: T?, output: T?, tone: GlassTone): Boolean = !custom &&
        this.source === source && this.output === output &&
        (if (tone.trim == 0f) source === output else appliedTone == tone)
}
private class ToneSnapshotCompletion {
    @Volatile var provenance: ToneSnapshotProvenance? = null
    @Volatile var receipt: ToneSnapshotReceipt<Bitmap>? = null
    @Volatile var output: ImageBitmap? = null
}

/** Retain completed positive-tone content while a replacement image/tone is prepared.
 * The optional preparation seam is for deterministic state tests; ordinary callers keep
 * the identical cache and Dispatchers.Default preparation path. No bitmap is recycled here.
 */
@Composable internal fun rememberToneSnapshot(image: ImageBitmap?, tone: GlassTone,
    prepare: (suspend (ImageBitmap, GlassTone) -> ImageBitmap)? = null,
    onProvenance: ((ToneSnapshotProvenance) -> Unit)? = null,
    onReceipt: ((ToneSnapshotReceipt<Bitmap>?) -> Unit)? = null): ImageBitmap? {
    val cached = remember(image, tone, prepare) {
        image?.takeIf { tone.trim > 0f && prepare == null }?.let { ToneImages.images.get(ToneImages.Key(it, tone)) }
    }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    // Non-observable metadata follows the existing result publication, without another effect,
    // cache entry or metadata-triggered recomposition. Only an opted-in receipt retains
    // one exact raw/output association until the next completion or disposal.
    val completed = remember { ToneSnapshotCompletion() }
    LaunchedEffect(image, tone, prepare) {
        val replacement = if (image == null || tone.trim == 0f) image
            else if (prepare != null) prepare(image, tone)
            else withContext(Dispatchers.Default) { ToneImages.get(image, tone) }
        // A suspended preparer may finish non-cancellable owned work after replacement.
        // Its retired effect must never publish into the retained result.
        currentCoroutineContext().ensureActive()
        completed.provenance = ToneSnapshotProvenance(replacement.toneIdentity(), image.toneIdentity(),
            tone.takeIf { image != null && tone.trim != 0f && prepare == null },
            ToneSnapshotPath.CompletedResult, image.toneIdentity(), tone, image != null && tone.trim != 0f && prepare != null)
        if (onReceipt != null) {
            completed.receipt = ToneSnapshotReceipt(image?.asAndroidBitmap(), replacement?.asAndroidBitmap(), tone,
                custom = image != null && tone.trim != 0f && prepare != null)
            completed.output = replacement
        }
        result = replacement
    }
    val returned = if (image == null || tone.trim == 0f) image else cached ?: result ?: image
    onReceipt?.invoke(when {
        image == null || tone.trim == 0f || cached != null -> ToneSnapshotReceipt(image?.asAndroidBitmap(), returned?.asAndroidBitmap(), tone)
        result != null && completed.output === returned -> completed.receipt
        else -> null
    })
    if (onProvenance != null) {
        val source = image.toneIdentity()
        val provenance = when {
            image == null || tone.trim == 0f -> ToneSnapshotProvenance(returned.toneIdentity(), source,
                null, ToneSnapshotPath.UntonedInput, source, tone)
            cached != null -> ToneSnapshotProvenance(returned.toneIdentity(), source,
                tone, ToneSnapshotPath.CacheHit, source, tone)
            result != null -> completed.provenance?.takeIf { it.returned == returned.toneIdentity() }
                ?.copy(requestedSource = source, requestedTone = tone)
                // Do not misattribute a snapshot read if completion advances concurrently with composition.
                ?: ToneSnapshotProvenance(returned.toneIdentity(), null, null,
                    ToneSnapshotPath.CompletionMetadataUnavailable, source, tone)
            else -> ToneSnapshotProvenance(returned.toneIdentity(), source,
                null, ToneSnapshotPath.RawFallback, source, tone)
        }
        // The caller consumes this immutable value in the same composition as returned pixels.
        onProvenance(provenance)
    }
    return returned
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
