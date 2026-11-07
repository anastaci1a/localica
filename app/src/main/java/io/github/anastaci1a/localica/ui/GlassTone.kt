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

/** Ordered compression: no brightness inversion, including at maximum trim/weight.
 * [lightInk] is the polarity being approached and [arrival] how far the tone has travelled to it from the opposite
 * polarity (1 = arrived). Both polarities are exact endpoints; between them the mapping is their linear mix, so a
 * polarity change can ease with an animation instead of switching in one frame. */
internal data class GlassTone(val trim: Float = 0f, val weight: Float = 2f, val lightInk: Boolean = false, val arrival: Float = 1f,
    /** Backdrop grading (#576): an OKLab lightness shift from [BackdropGrade]. Nonzero replaces the curve above. */
    val shift: Float = 0f) {
    /** True when this tone leaves pixels untouched. */
    val identity: Boolean get() = trim <= 0f && shift == 0f
    /** 0 is the dark-ink (lifted) endpoint and 1 the light-ink (compressed) endpoint. */
    val lightMix: Float get() = (if (lightInk) arrival else 1f - arrival).coerceIn(0f, 1f)
    /** The tone pinned at one polarity; only these tones have prepared pixels. */
    fun endpoint(light: Boolean) = if (lightInk == light && arrival == 1f) this else copy(lightInk = light, arrival = 1f)
    private fun endpointChannel(x: Float, light: Boolean): Float {
        val t = trim.coerceIn(0f, .5f)
        val p = weight.coerceIn(1f, 2f)
        return if (light) x - t * x.pow(p) else x + t * (1f - x).pow(p)
    }
    fun channel(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val m = lightMix
        return when {
            m >= 1f -> endpointChannel(x, true)
            m <= 0f -> endpointChannel(x, false)
            else -> endpointChannel(x, false) + (endpointChannel(x, true) - endpointChannel(x, false)) * m
        }
    }
    fun color(color: Color): Color {
        if (shift != 0f) return BackdropGrade.apply(color.red, color.green, color.blue, shift).let { Color(it[0], it[1], it[2], color.alpha) }
        return Color(channel(color.red), channel(color.green), channel(color.blue), color.alpha)
    }
    /** Same rounded 8-bit stage as the prepared image, before bitmap filtering. */
    fun byteChannel(value: Int): Int = (channel(value / 255f) * 255f + .5f).toInt()
    companion object {
        /** A tone at any point between the dark-ink (0) and light-ink (1) endpoints. */
        fun mixed(trim: Float, weight: Float, lightMix: Float): GlassTone {
            val m = lightMix.coerceIn(0f, 1f)
            return if (m >= .5f) GlassTone(trim, weight, true, m) else GlassTone(trim, weight, false, 1f - m)
        }
    }
}

/** Older adapters share bounded prepared pixels, never a readback or per-frame CPU filter. */
private object ToneImages {
    /** Keyed by the decoded pixels, not by an ImageBitmap wrapper: every composition wraps the same
     * bitmap anew, and a wrapper-keyed lookup never found what an earlier composition prepared. */
    data class Key(val bitmap: Bitmap, val tone: GlassTone)
    val images = object : LruCache<Key, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * 4
    }
    val mutex = Mutex()
    /** Sets an owner keeps outside the LRU (#634): the current song's full-player backdrop must survive
     * unrelated tone work while the player is minimized. An owner replaces its whole set at once. */
    private val retained = HashMap<Any, Map<Key, ImageBitmap>>()
    private fun lookup(key: Key): ImageBitmap? =
        images.get(key) ?: synchronized(retained) { retained.values.firstNotNullOfOrNull { it[key] } }
    fun peek(image: ImageBitmap, tone: GlassTone): ImageBitmap? = lookup(Key(image.asAndroidBitmap(), tone))
    fun retain(owner: Any, set: Map<Key, ImageBitmap>) = synchronized(retained) {
        if (set.isEmpty()) retained.remove(owner) else retained[owner] = set
    }
    /** Pixels already prepared for this image under another tone: close to the wanted result,
     * unlike the raw image, which is visibly brighter than any positive tone's output. An entry
     * of the same polarity is preferred; the opposite polarity is only a last resort. */
    fun anyTone(image: ImageBitmap, tone: GlassTone): ImageBitmap? {
        val bitmap = image.asAndroidBitmap()
        val entries = images.snapshot().entries.filter { it.key.bitmap === bitmap }
        return (entries.firstOrNull { it.key.tone.lightInk == tone.lightInk } ?: entries.firstOrNull())?.value
    }
    suspend fun get(image: ImageBitmap, tone: GlassTone): ImageBitmap = mutex.withLock {
        val key = Key(image.asAndroidBitmap(), tone)
        lookup(key)?.let { return@withLock it }
        val source = readGlassPixels(image, 1)
        val pixels = source.pixels
        if (tone.shift != 0f) {
            for (i in pixels.indices) pixels[i] = BackdropGrade.applyPixel(pixels[i], tone.shift)
        } else {
            val lut = IntArray(256) { tone.byteChannel(it) }
            for (i in pixels.indices) {
                val c = pixels[i]
                pixels[i] = (c and -0x1000000) or (lut[c ushr 16 and 255] shl 16) or
                    (lut[c ushr 8 and 255] shl 8) or lut[c and 255]
            }
        }
        Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap().also { images.put(key, it) }
    }
}

/** One prepared tone image, opaque outside this file: the shared cache key and its pixels. */
internal class PreparedToneImage private constructor(internal val key: Any, val image: ImageBitmap) {
    internal companion object {
        /** Prepares [bitmap] under [tone] in the shared cache before any consumer mounts (#634), off Main.
         * Null for an identity tone, which draws the source itself. */
        suspend fun prepare(bitmap: Bitmap, tone: GlassTone): PreparedToneImage? = if (tone.identity) null
            else withContext(Dispatchers.Default) {
                PreparedToneImage(ToneImages.Key(bitmap, tone), ToneImages.get(bitmap.asImageBitmap(), tone))
            }
        /** [owner] keeps exactly [images] findable by every tone consumer, replacing its previous set. */
        fun retain(owner: Any, images: List<PreparedToneImage>) =
            ToneImages.retain(owner, images.associate { it.key as ToneImages.Key to it.image })
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
        (if (tone.identity) source === output else appliedTone == tone)
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
        image?.takeIf { !tone.identity && prepare == null }?.let { ToneImages.peek(it, tone) }
    }
    // A newly mounted consumer (such as the outgoing frame of a crossfade) has no retained
    // result. Until its own tone is prepared, show another tone of the same pixels, never the raw image.
    val standIn = remember(image, tone, prepare) {
        if (cached != null || prepare != null || tone.identity) null else image?.let { ToneImages.anyTone(it, tone) }
    }
    var result by remember { mutableStateOf<ImageBitmap?>(null) }
    // Non-observable metadata follows the existing result publication, without another effect,
    // cache entry or metadata-triggered recomposition. Only an opted-in receipt retains
    // one exact raw/output association until the next completion or disposal.
    val completed = remember { ToneSnapshotCompletion() }
    LaunchedEffect(image, tone, prepare) {
        val replacement = if (image == null || tone.identity) image
            else if (prepare != null) prepare(image, tone)
            else withContext(Dispatchers.Default) { ToneImages.get(image, tone) }
        // A suspended preparer may finish non-cancellable owned work after replacement.
        // Its retired effect must never publish into the retained result.
        currentCoroutineContext().ensureActive()
        completed.provenance = ToneSnapshotProvenance(replacement.toneIdentity(), image.toneIdentity(),
            tone.takeIf { image != null && !tone.identity && prepare == null },
            ToneSnapshotPath.CompletedResult, image.toneIdentity(), tone, image != null && !tone.identity && prepare != null)
        if (onReceipt != null) {
            completed.receipt = ToneSnapshotReceipt(image?.asAndroidBitmap(), replacement?.asAndroidBitmap(), tone,
                custom = image != null && !tone.identity && prepare != null)
            completed.output = replacement
        }
        result = replacement
    }
    val returned = if (image == null || tone.identity) image else cached ?: result ?: standIn ?: image
    onReceipt?.invoke(when {
        image == null || tone.identity || cached != null -> ToneSnapshotReceipt(image?.asAndroidBitmap(), returned?.asAndroidBitmap(), tone)
        result != null && completed.output === returned -> completed.receipt
        else -> null
    })
    if (onProvenance != null) {
        val source = image.toneIdentity()
        val provenance = when {
            image == null || tone.identity -> ToneSnapshotProvenance(returned.toneIdentity(), source,
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
            // tone.z is the light-ink mix: 0 and 1 are the two exact polarities, between them their blend.
            float3 darkEnd = 1.0 - color;
            float3 darkWeighted = tone.y == 2.0 ? darkEnd * darkEnd : tone.y == 1.0 ? darkEnd : pow(darkEnd, float3(tone.y));
            float3 lightWeighted = tone.y == 2.0 ? color * color : tone.y == 1.0 ? color : pow(color, float3(tone.y));
            color += mix(darkWeighted * tone.x, -lightWeighted * tone.x, clamp(tone.z, 0.0, 1.0));
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
        shader.setFloatUniform("tone", tone.trim, tone.weight, tone.lightMix)
        val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene")
        return android.graphics.RenderEffect.createChainEffect(effect,
            android.graphics.RenderEffect.createBlurEffect(blur.coerceAtLeast(.01f), blur.coerceAtLeast(.01f), android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
    }
}
