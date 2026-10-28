package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.*

internal enum class PlayerBackgroundUnavailable {
    Unmeasured, Preparing, FrozenSampleUnavailable, NativeBlurSampleUnavailable, MovingCover,
    PartialPlayer, Transitioning, MissingRole, UnreadableImage, InvalidGeometry,
}
internal enum class PlayerBackgroundConfidence { Cold, Current, Stale }
/** Current describes matching bounded background metadata, not per-pixel readability proof. */
internal data class PlayerBackgroundDecision(
    val lightInk: Boolean = true,
    val confidence: PlayerBackgroundConfidence = PlayerBackgroundConfidence.Cold,
    val unavailable: PlayerBackgroundUnavailable? = PlayerBackgroundUnavailable.Unmeasured,
)

/** Pure publication policy. Cold/unavailable is white ink, never the target palette.
 * A retained decision is explicitly stale; it is not evidence about the new frame. */
internal class PlayerBackgroundPublication {
    private var generation = 0L
    private var verified: Boolean? = null
    var decision = PlayerBackgroundDecision(); private set
    fun begin(reason: PlayerBackgroundUnavailable): Long {
        generation++
        decision = PlayerBackgroundDecision(verified ?: true,
            if (verified == null) PlayerBackgroundConfidence.Cold else PlayerBackgroundConfidence.Stale, reason)
        return generation
    }
    fun publish(token: Long, lightInk: Boolean): Boolean {
        if (token != generation) return false
        verified = lightInk
        decision = PlayerBackgroundDecision(lightInk, PlayerBackgroundConfidence.Current, null)
        return true
    }
}

/** Immutable description of the roles actually composed by ArtworkBlend. No clock ownership. */
internal data class ArtworkBackgroundRole(val identity: Any, val visual: PlayerVisual, val weight: Float)
internal val LocalArtworkBackgroundRole = staticCompositionLocalOf<Any?> { null }
internal data class ArtworkBackgroundFrame(val roles: List<ArtworkBackgroundRole>,
    val frozenVisible: Boolean, val liveBlur: Boolean, val transitioning: Boolean)

/** Returned paint images, not requested palette images: tone preparation may retain an older image. */
internal data class PlayerBackdropDescriptor(val palette: PlayerPalette, val wash: Bitmap?, val softWash: Bitmap?,
    val grain: Bitmap, val tone: GlassTone,
    val washProvenance: ToneSnapshotProvenance? = null, val softWashProvenance: ToneSnapshotProvenance? = null,
    val washReceipt: ToneSnapshotReceipt<Bitmap>? = null, val softWashReceipt: ToneSnapshotReceipt<Bitmap>? = null)

/** One resource-free failure witness, not a frame/image lease or a presentation acknowledgement. */
internal data class PlayerBackgroundRoleDiagnostic(val identity: Int, val visual: Int, val weight: Float,
    val requestedTone: GlassTone, val wash: ToneSnapshotProvenance?, val softWash: ToneSnapshotProvenance?)
internal data class PlayerBackgroundSampleDiagnostic(val decision: Int, val frame: Int,
    val mean: BackgroundMean, val roleCount: Int, val roles: List<PlayerBackgroundRoleDiagnostic>)

internal data class PlayerBackgroundGeometry(val size: Size, val cover: Rect, val washCover: Rect, val footprints: List<Rect>,
    val fullArtBlend: Float, val softness: Float, val edgeSoftness: Float, val edgeBand: Float,
    val coverBlend: Float, val artworkAlpha: Float, val corner: Float, val miniSize: Float, val cropRetention: Float)

/** All coordinates are relative to the measured background, including scroll and viewport clipping. */
@Stable internal class PlayerBackgroundPolarityState {
    var decision by mutableStateOf(PlayerBackgroundDecision()); private set
    var frame by mutableStateOf<ArtworkBackgroundFrame?>(null); private set
    // Plain bounded metadata: read/formatted only by a failure diagnostic, never observed by UI.
    @Volatile var sampleDiagnostic: PlayerBackgroundSampleDiagnostic? = null; internal set
    private val coordinates = mutableMapOf<String, LayoutCoordinates>()
    var measured by mutableStateOf<Map<String, Rect>>(emptyMap()); private set
    val backdrops = mutableStateMapOf<Any, PlayerBackdropDescriptor>()
    private val publication = PlayerBackgroundPublication()
    fun measure(name: String, value: LayoutCoordinates) {
        coordinates[name] = value
        val background = coordinates["background"]?.takeIf { it.isAttached } ?: return
        val next = coordinates.mapNotNull { (key, child) ->
            if (!child.isAttached) null else key to background.localBoundingBoxOf(child, clipBounds = false)
        }.toMap()
        if (measured != next) measured = next
    }
    fun detach() {
        coordinates.clear(); measured = emptyMap(); frame = null; backdrops.clear()
        unavailable(PlayerBackgroundUnavailable.Unmeasured)
    }
    fun frame(value: ArtworkBackgroundFrame) { if (frame != value) frame = value }
    fun backdrop(identity: Any, value: PlayerBackdropDescriptor) {
        if (backdrops[identity] != value) backdrops[identity] = value
        // Only current/outgoing and their soft roles are needed. No unbounded visit history.
        if (backdrops.size > 8) backdrops.keys.filter { key -> key != identity && frame?.roles?.none { it.identity == key } != false }
            .take(backdrops.size - 8).forEach { backdrops.remove(it) }
    }
    fun unavailable(reason: PlayerBackgroundUnavailable): Long = publication.begin(reason).also {
        sampleDiagnostic = null
        decision = publication.decision
    }
    fun current(lightInk: Boolean) {
        val token = publication.begin(PlayerBackgroundUnavailable.Preparing)
        publication.publish(token, lightInk)
        decision = publication.decision
    }
}

private data class BackgroundPaintInputs(val palette: PlayerPalette, val wash: Bitmap?, val softWash: Bitmap?,
    val grain: Bitmap, val tone: GlassTone)
private data class BackgroundSampleRole(val identity: Any, val visual: PlayerVisual, val backdrop: PlayerBackdropDescriptor) {
    // Key preparation by the exact images and tone used by the painter, not its requested
    // inputs. Provenance-only updates do not change samples but are rechecked before publishing.
    private val pixels = BackgroundPaintInputs(backdrop.palette, backdrop.wash, backdrop.softWash,
        backdrop.grain, backdrop.tone)
    override fun equals(other: Any?): Boolean = other is BackgroundSampleRole &&
        identity == other.identity && visual == other.visual && pixels == other.pixels
    override fun hashCode(): Int = 31 * (31 * identity.hashCode() + visual.hashCode()) + pixels.hashCode()
}
private data class BackgroundRequest(val geometry: PlayerBackgroundGeometry,
    val roles: List<BackgroundSampleRole>)
private data class PreparedBackground(val request: BackgroundRequest, val samples: List<List<BackgroundRgb>>,
    val weights: List<Float>)

/** Only images actually participating in this background need a preparation receipt. A raw
 * fallback or retained previous source/tone cannot certify the candidate as current. */
private fun BackgroundSampleRole.paintReady(geometry: PlayerBackgroundGeometry, requestedTone: GlassTone): Boolean {
    val b = backdrop
    if (b.tone != requestedTone) return false
    if (geometry.fullArtBlend <= 0f) return true
    fun ready(source: Bitmap?, output: Bitmap?, receipt: ToneSnapshotReceipt<Bitmap>?) =
        if (source == null) output == null else receipt?.matches(source, output, b.tone) == true
    return ready(b.palette.wash, b.wash, b.washReceipt) &&
        (geometry.softness <= 0f || b.palette.wash == null || ready(b.palette.softWash, b.softWash, b.softWashReceipt))
}

/** Preparation is keyed by owned pixels and geometry, not the blend clock. Each clipped
 * footprint uses an 8x8 quadrature: a bounded approximation, not an exact pixel average or
 * per-pixel readability guarantee. Geometry changes reuse cached immutable pixel arrays;
 * a blend frame only mixes prepared RGB metadata, without filtering, Bitmap reads or GPU copies. */
@Composable internal fun ObservePlayerBackgroundPolarity(owner: PlayerBackgroundPolarityState,
    geometry: PlayerBackgroundGeometry?, unsupported: PlayerBackgroundUnavailable?, requestedTone: GlassTone) {
    val frame = owner.frame
    val reason = unsupported ?: when {
        frame == null || geometry == null -> PlayerBackgroundUnavailable.Unmeasured
        !validBackgroundGeometry(geometry) -> PlayerBackgroundUnavailable.InvalidGeometry
        frame.frozenVisible -> PlayerBackgroundUnavailable.FrozenSampleUnavailable
        frame.liveBlur -> PlayerBackgroundUnavailable.NativeBlurSampleUnavailable
        frame.transitioning -> PlayerBackgroundUnavailable.Transitioning
        frame.roles.any { owner.backdrops[it.identity] == null } -> PlayerBackgroundUnavailable.MissingRole
        frame.roles.any { role -> owner.backdrops[role.identity]?.tone != requestedTone } -> PlayerBackgroundUnavailable.Preparing
        else -> null
    }
    val request = if (reason == null && geometry != null && frame != null)
        BackgroundRequest(geometry, frame.roles.map { BackgroundSampleRole(it.identity, it.visual, requireNotNull(owner.backdrops[it.identity])) }) else null
    var prepared by remember { mutableStateOf<PreparedBackground?>(null) }
    var failed by remember { mutableStateOf<BackgroundRequest?>(null) }
    LaunchedEffect(request) {
        prepared = null; failed = null
        if (request != null) {
            val result = withContext(Dispatchers.Default) { prepareBackground(request) }
            currentCoroutineContext().ensureActive()
            if (result == null) failed = request else prepared = result
        }
    }
    val ready = prepared?.takeIf { it.request == request }
    val failure = reason ?: if (failed == request && request != null) PlayerBackgroundUnavailable.UnreadableImage else PlayerBackgroundUnavailable.Preparing
    SideEffect {
        if (ready != null && frame != null) {
            val weights = ready.request.roles.map { role ->
                frame.roles.filter { it.identity == role.identity }.sumOf { it.weight.toDouble() }.toFloat()
            }
            val choice = backgroundSingleContrast(ready.samples.zip(weights), ready.weights)
            if (choice == null) {
                owner.unavailable(PlayerBackgroundUnavailable.InvalidGeometry)
                return@SideEffect
            }
            val currentRequest = requireNotNull(request)
            if (currentRequest.roles.filterIndexed { index, _ -> weights[index] > 0f }
                    .any { !it.paintReady(currentRequest.geometry, requestedTone) }) {
                owner.unavailable(PlayerBackgroundUnavailable.Preparing)
                return@SideEffect
            }
            owner.current(choice.lightInk)
            val mean = choice.mean
            // Use the current descriptor's provenance for these same prepared pixels. The equality
            // guard above still requires every original sampling input to match. Never retain frame,
            // visual, descriptor or bitmap references just to explain a future failure.
            owner.sampleDiagnostic = mean.let { sample ->
                PlayerBackgroundSampleDiagnostic(System.identityHashCode(owner.decision), System.identityHashCode(frame),
                    sample, ready.request.roles.size, currentRequest.roles.take(8).mapIndexed { index, role ->
                        PlayerBackgroundRoleDiagnostic(System.identityHashCode(role.identity), System.identityHashCode(role.visual),
                            weights[index], role.backdrop.tone, role.backdrop.washProvenance, role.backdrop.softWashProvenance)
                    })
            }
        } else owner.unavailable(failure)
    }
}

internal data class BackgroundRgb(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    fun over(back: BackgroundRgb, opacity: Float = 1f): BackgroundRgb {
        val alpha = (a * opacity).coerceIn(0f, 1f)
        return BackgroundRgb(r * alpha + back.r * (1f - alpha), g * alpha + back.g * (1f - alpha),
            b * alpha + back.b * (1f - alpha))
    }
    companion object { val Black = BackgroundRgb(0f, 0f, 0f) }
}
private fun Color.rgb() = BackgroundRgb(red, green, blue, alpha)
private fun mix(a: BackgroundRgb, b: BackgroundRgb, t: Float) = BackgroundRgb(
    a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t)

internal data class BackgroundMean(val red: Double, val green: Double, val blue: Double, val luminance: Double)
/** Mix RGB first: averaging endpoint luminances would not describe a crossfade. */
internal fun backgroundLightInk(roles: List<Pair<List<BackgroundRgb>, Float>>, areas: List<Float>,
    onMean: ((BackgroundMean) -> Unit)? = null): Boolean {
    var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
    for (i in areas.indices) {
        val area = areas[i].toDouble(); total += area
        for ((pixels, weight) in roles) {
            r += pixels[i].r * weight * area; g += pixels[i].g * weight * area; b += pixels[i].b * weight * area
        }
    }
    if (total <= 0.0) return true
    fun linear(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
    val red = r / total; val green = g / total; val blue = b / total
    val luminance = .2126 * linear(red) + .7152 * linear(green) + .0722 * linear(blue)
    onMean?.invoke(BackgroundMean(red, green, blue, luminance))
    // Compare white (1.05 / (L + .05)) with black ((L + .05) / .05).
    // The old palette cutoff is not a contrast rule for already-toned pixels.
    return backgroundContrastLightInk(luminance)
}

/** Exact contrast ties choose white, deterministically. */
internal fun backgroundContrastLightInk(luminance: Double): Boolean = luminance <= sqrt(.0525) - .05

/** Compare each ink against its OWN toned candidate, never against the previous ink's
 * painted result. Neither score depends on publication history. Exact ties choose white. */
internal fun jointBackgroundLightInk(blackLuminance: Double, whiteLuminance: Double): Boolean? {
    if (!blackLuminance.isFinite() || !whiteLuminance.isFinite() ||
        blackLuminance !in 0.0..1.0 || whiteLuminance !in 0.0..1.0) return null
    return 1.05 / (whiteLuminance + .05) >= (blackLuminance + .05) / .05
}
internal data class BackgroundJointContrast(val lightInk: Boolean, val black: BackgroundMean, val white: BackgroundMean)
internal fun backgroundJointContrast(black: List<Pair<List<BackgroundRgb>, Float>>,
    white: List<Pair<List<BackgroundRgb>, Float>>, areas: List<Float>): BackgroundJointContrast? {
    if (areas.isEmpty() || areas.any { !it.isFinite() || it <= 0f } ||
        !areas.sumOf { it.toDouble() }.isFinite() || black.isEmpty() || black.size != white.size) return null
    fun valid(roles: List<Pair<List<BackgroundRgb>, Float>>) = roles.all { (pixels, weight) ->
        weight.isFinite() && weight >= 0f && pixels.size == areas.size && pixels.all { pixel ->
            listOf(pixel.r, pixel.g, pixel.b, pixel.a).all { it.isFinite() && it in 0f..1f }
        }
    } && roles.sumOf { it.second.toDouble() }.let { it.isFinite() && it > 0.0 && it <= 1.000001 }
    if (!valid(black) || !valid(white) || black.indices.any { black[it].second != white[it].second }) return null
    var blackMean: BackgroundMean? = null
    var whiteMean: BackgroundMean? = null
    backgroundLightInk(black, areas) { blackMean = it }
    backgroundLightInk(white, areas) { whiteMean = it }
    // Float blend weights can sum a few ulps above one. Clamp only that arithmetic
    // residue to the representable output range; materially excessive weight is rejected.
    fun bounded(mean: BackgroundMean?): BackgroundMean? {
        mean ?: return null
        if (listOf(mean.red, mean.green, mean.blue).any { !it.isFinite() || it !in 0.0..1.000001 }) return null
        val r = mean.red.coerceIn(0.0, 1.0); val g = mean.green.coerceIn(0.0, 1.0); val b = mean.blue.coerceIn(0.0, 1.0)
        fun linear(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)
        return BackgroundMean(r, g, b, .2126 * linear(r) + .7152 * linear(g) + .0722 * linear(b))
    }
    val b = bounded(blackMean) ?: return null
    val w = bounded(whiteMean) ?: return null
    return BackgroundJointContrast(jointBackgroundLightInk(b.luminance, w.luminance) ?: return null, b, w)
}

/** Both inks are evaluated against the same returned backing, never two hypothetical washes. */
internal data class BackgroundSingleContrast(val lightInk: Boolean, val mean: BackgroundMean)
internal fun backgroundSingleContrast(roles: List<Pair<List<BackgroundRgb>, Float>>,
    areas: List<Float>): BackgroundSingleContrast? {
    val result = backgroundJointContrast(roles, roles, areas) ?: return null
    return BackgroundSingleContrast(result.lightInk, result.black)
}

internal class BackgroundPixels(val width: Int, val height: Int, val colors: IntArray) {
    fun at(u: Float, v: Float, toneLut: IntArray? = null): BackgroundRgb {
        val x = u * width - .5f; val y = v * height - .5f
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        fun pixel(dx: Int, dy: Int): BackgroundRgb {
            val c = colors[(y0 + dy).coerceIn(0, height - 1) * width + (x0 + dx).coerceIn(0, width - 1)]
            fun channel(shift: Int): Float {
                val raw = c ushr shift and 255
                return (toneLut?.get(raw) ?: raw) / 255f
            }
            return BackgroundRgb(channel(16), channel(8), channel(0), (c ushr 24) / 255f)
        }
        fun premultiplied(value: BackgroundRgb) = value.copy(r = value.r * value.a, g = value.g * value.a, b = value.b * value.a)
        val color = mix(mix(premultiplied(pixel(0, 0)), premultiplied(pixel(1, 0)), x - x0),
            mix(premultiplied(pixel(0, 1)), premultiplied(pixel(1, 1)), x - x0), y - y0)
        return if (color.a <= 0f) BackgroundRgb(0f, 0f, 0f, 0f)
            else color.copy(r = color.r / color.a, g = color.g / color.a, b = color.b / color.a)
    }
}

/** Software-owned inputs only; hardware is unavailable, never implicitly readPixels/copied.
 * Bounded immutable CPU metadata does not recycle or change ownership of any shared bitmap. */
private object BackgroundPixelCache {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Bitmap, BackgroundPixels>(16, .75f, true)
    private var bytes = 0
    suspend fun get(bitmap: Bitmap): BackgroundPixels? = mutex.withLock {
        cache[bitmap]?.let { return@withLock it }
        if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE || bitmap.width.toLong() * bitmap.height > 1024 * 1024) return@withLock null
        currentCoroutineContext().ensureActive()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        currentCoroutineContext().ensureActive()
        val value = BackgroundPixels(bitmap.width, bitmap.height, pixels)
        cache[bitmap] = value; bytes += pixels.size * 4
        while (bytes > 16 * 1024 * 1024 && cache.size > 1) {
            val entry = cache.entries.iterator().next(); bytes -= entry.value.colors.size * 4; cache.remove(entry.key)
        }
        value
    }
}

private fun valid(rect: Rect) = listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } && rect.width > 0f && rect.height > 0f
/** Union the clipped measured rectangles so overlapping controls do not count pixels twice. */
internal fun playerBackgroundFootprints(background: Rect?, viewport: Rect?, controls: List<Rect>): List<Rect> {
    if (background == null || viewport == null || !valid(background) || !valid(viewport)) return emptyList()
    val result = mutableListOf<Rect>()
    for (control in controls.filter(::valid)) {
        val clipped = control.intersect(background).intersect(viewport)
        if (!valid(clipped)) continue
        var pieces = listOf(clipped)
        for (old in result) pieces = pieces.flatMap { piece ->
            val overlap = piece.intersect(old)
            if (!valid(overlap)) listOf(piece) else listOf(
                Rect(piece.left, piece.top, piece.right, overlap.top),
                Rect(piece.left, overlap.bottom, piece.right, piece.bottom),
                Rect(piece.left, overlap.top, overlap.left, overlap.bottom),
                Rect(overlap.right, overlap.top, piece.right, overlap.bottom),
            ).filter(::valid)
        }
        result += pieces
    }
    return result
}

private fun validBackgroundGeometry(value: PlayerBackgroundGeometry): Boolean =
    valid(value.cover) && valid(value.washCover) && value.footprints.isNotEmpty() && value.footprints.all(::valid) &&
        value.size.width.isFinite() && value.size.height.isFinite() && value.size.width > 0f && value.size.height > 0f &&
        listOf(value.fullArtBlend, value.softness, value.edgeSoftness, value.edgeBand, value.coverBlend,
            value.artworkAlpha, value.corner, value.miniSize, value.cropRetention).all { it.isFinite() } &&
        value.edgeBand > 0f && value.miniSize > 0f && value.corner >= 0f

private fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
// ArtworkEdge uses 33 gradient stops, not a continuously evaluated cubic.
private fun featherStop(position: Float, value: (Float) -> Float): Float {
    val scaled = position.coerceIn(0f, 1f) * 32f; val low = scaled.toInt().coerceAtMost(31)
    return value(low / 32f) + (value((low + 1) / 32f) - value(low / 32f)) * (scaled - low)
}
private fun feather(x: Float, y: Float, amount: Float, band: Float, continuation: Float): Float {
    fun edge(v: Float) = featherStop(v) { 1f - amount * (1f - smooth(minOf(it, 1f - it) / band)) }
    val bottom = featherStop(y) { 1f - smooth(if (it < .14f) (.14f - it) / .14f else (it - .68f) / .32f) * continuation }
    return edge(x) * edge(y) * bottom
}

private suspend fun prepareBackground(request: BackgroundRequest): PreparedBackground? {
    val geometry = request.geometry
    if (!validBackgroundGeometry(geometry)) return null
    val mapping = (ArtworkWashMapping.create(geometry.washCover) as? ArtworkWashMappingResult.Known)?.value ?: return null
    val points = geometry.footprints.flatMap { rect -> (0 until 8).flatMap { y -> (0 until 8).map { x ->
        Offset(rect.left + (x + .5f) * rect.width / 8, rect.top + (y + .5f) * rect.height / 8)
    } } }
    val areas = geometry.footprints.flatMap { rect -> List(64) { rect.width * rect.height / 64 } }
    val samples = request.roles.map { (_, visual, backdrop) ->
        currentCoroutineContext().ensureActive()
        if (!backdrop.tone.trim.isFinite() || !backdrop.tone.weight.isFinite()) return null
        val tone = backdrop.tone
        val wash = backdrop.wash?.let { BackgroundPixelCache.get(it) ?: return null }
        val softWash = backdrop.softWash?.let { BackgroundPixelCache.get(it) ?: return null }
        val cover = visual.artwork?.let { BackgroundPixelCache.get(it.asAndroidBitmap()) ?: return null }
        val softCover = visual.softArtwork?.let { BackgroundPixelCache.get(it.asAndroidBitmap()) ?: return null }
        val grain = BackgroundPixelCache.get(backdrop.grain) ?: return null
        val top = tone.color(backdrop.palette.top).rgb()
        val middle = tone.color(backdrop.palette.middle).rgb()
        val bottom = tone.color(backdrop.palette.bottom).rgb()
        points.map { point ->
            val t = (point.y / geometry.size.height).coerceIn(0f, 1f)
            var result = if (t < .5f) mix(top, middle, t * 2) else mix(middle, bottom, (t - .5f) * 2)
            val radial = (1f - hypot(point.x - geometry.size.width * .1f, point.y - geometry.size.height * .4f) / (geometry.size.width * 1.2f)).coerceIn(0f, 1f)
            result = top.copy(a = .45f).over(result, radial)
            val uv = (mapping.sample(point) as? ArtworkWashMappingResult.Known)?.value?.clampedNormalized ?: return null
            if (wash != null && geometry.fullArtBlend > 0f) {
                result = wash.at(uv.x, uv.y).over(result, (255 * geometry.fullArtBlend).toInt() / 255f)
                if (softWash != null && geometry.softness > 0f) result = softWash.at(uv.x, uv.y).over(result,
                    (255 * geometry.fullArtBlend * geometry.softness).toInt().coerceIn(0, 255) / 255f)
            }
            result = grain.at(point.x / geometry.size.width, point.y / geometry.size.height).over(result,
                (255 * (1f - geometry.fullArtBlend * .8f)).toInt() / 255f)
            val x = (point.x - geometry.cover.left) / geometry.cover.width
            val y = (point.y - geometry.cover.top) / geometry.cover.height
            if (x in 0f..1f && y in 0f..1f && geometry.artworkAlpha > 0f) {
                fun image(pixels: BackgroundPixels): BackgroundRgb? {
                    val fit = minOf(geometry.cover.width / pixels.width, geometry.cover.height / pixels.height)
                    val mini = maxOf(geometry.miniSize / pixels.width, geometry.miniSize / pixels.height)
                    val scale = fit + (maxOf(fit, mini) - fit) * geometry.cropRetention
                    val u = (point.x - geometry.cover.center.x) / (pixels.width * scale) + .5f
                    val v = (point.y - geometry.cover.center.y) / (pixels.height * scale) + .5f
                    return if (u in 0f..1f && v in 0f..1f) pixels.at(u, v) else null
                }
                if (softCover != null && geometry.edgeSoftness > 0f) image(softCover)?.let {
                    result = it.over(result, geometry.artworkAlpha * geometry.edgeSoftness * feather(x, y, 1f, .13f, 0f))
                }
                val radius = geometry.corner.coerceAtMost(minOf(geometry.cover.width, geometry.cover.height) / 2)
                val dx = max(0f, radius - min(point.x - geometry.cover.left, geometry.cover.right - point.x))
                val dy = max(0f, radius - min(point.y - geometry.cover.top, geometry.cover.bottom - point.y))
                if (dx * dx + dy * dy <= radius * radius) {
                    // Missing/loading placeholders have their own foreground; do not invent their pixels.
                    if (cover == null) return null
                    var direct = visual.palette.middle.rgb()
                    image(cover)?.let { direct = it.over(direct) }
                    result = direct.over(result, geometry.artworkAlpha * feather(x, y, geometry.edgeSoftness, geometry.edgeBand, geometry.coverBlend))
                }
            }
            result
        }
    }
    return PreparedBackground(request, samples, areas)
}
