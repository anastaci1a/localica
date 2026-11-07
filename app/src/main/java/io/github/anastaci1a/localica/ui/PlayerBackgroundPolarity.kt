package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
/** [incoming] roles are the artwork the blend is travelling to; [soft] ones are its transitional blur overlay. */
internal data class ArtworkBackgroundRole(val identity: Any, val visual: PlayerVisual, val weight: Float,
    val incoming: Boolean = true, val soft: Boolean = false)
/** The grade of one prepared backdrop role: the ink its own region wants and the lightness shift that ink needs (#576). */
internal data class BackdropRoleGrade(val lightInk: Boolean, val shift: Float)
/** Prepared destinations, with no animation state or clock of their own. */
internal data class PlayerLyricsInk(val ordinary: Boolean, val lyrics: Boolean,
    val shifts: Map<Any, Pair<Float, Float>>)
internal val LocalArtworkBackgroundRole = staticCompositionLocalOf<Any?> { null }
internal data class ArtworkBackgroundFrame(val roles: List<ArtworkBackgroundRole>,
    val frozenVisible: Boolean, val liveBlur: Boolean, val transitioning: Boolean,
    /** The blend's own reveal fraction, 0 at an artwork change and 1 once the new artwork is fully shown: the one clock the ink and materials follow with the backdrop (#578). */
    val clock: Float = 1f)

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
    val coverBlend: Float, val artworkAlpha: Float, val corner: Float, val contentScale: ContentScale)

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
    /**
     * The polarity actually presented: [decision]'s light ink eased with the lyrics open/close clock (1 light ink,
     * 0 dark ink). Every consumer of the light/dark choice outside the player's own composition (the glass
     * backdrops, the panel theme, the Queue) reads this rather than the discrete decision, so the foreground
     * and the materials ease together. Published by the player surface, which owns the lyrics clock.
     */
    var lightMix by mutableFloatStateOf(1f); private set
    fun presentLightMix(value: Float) { if (lightMix != value) lightMix = value }
    // Both destinations are measured before lyrics move. The moving composite's
    // asynchronous contrast decision cannot supply the destination of its own ease.
    private var preparedLyricsInk by mutableStateOf<PlayerLyricsInk?>(null)
    internal val lyricsInk: PlayerLyricsInk? get() = if (pinned == null) preparedLyricsInk else null
    internal fun presentLyricsInk(value: PlayerLyricsInk?) { preparedLyricsInk = value }
    /**
     * Per backdrop role (#576/#578): the ink that role's own region wants and its lightness shift. Each role is graded
     * for itself, so a crossfade between two covers blends two correctly graded backdrops and a song change never
     * re-prepares pixels per frame. Held until a new preparation completes.
     */
    var roleGrades by mutableStateOf<Map<Any, BackdropRoleGrade>>(emptyMap()); private set
    // Blend roles change on admission and settlement. Retain grades by owned visual so
    // a remounted outgoing role never loses the grade it was already drawing.
    private val visualGrades = mutableStateMapOf<PlayerVisual, BackdropRoleGrade>()
    fun grade(identity: Any?, visual: PlayerVisual): BackdropRoleGrade? = roleGrades[identity] ?: visualGrades[visual]
        // The owned neutral gradient already meets white-ink contrast. Missing artwork
        // cannot provide the cover pixels required by the sampler; never hide this
        // known backing indefinitely while waiting for a grade that cannot arrive.
        ?: if (visual.artwork == null && visual.palette == NeutralPalette) BackdropRoleGrade(true, 0f) else null
    /** The ink the artwork transition is travelling to, once known; null when settled or not yet measured. */
    var destinationLightInk by mutableStateOf<Boolean?>(null); private set
    /** The artwork blend clock (1 when settled): drives the same ease as the lyrics clock for song changes. */
    var artworkClock by mutableFloatStateOf(1f); private set
    /** The settled decision is current again: it, not the transition's remembered destination, owns the ink. */
    internal fun settled() { if (destinationLightInk != null) destinationLightInk = null }
    internal fun presentGrades(grades: Map<Any, BackdropRoleGrade>, destination: Boolean?) {
        if (roleGrades != grades) roleGrades = grades
        frame?.roles?.forEach { role -> grades[role.identity]?.let { visualGrades[role.visual] = it } }
        if (visualGrades.size > 8) visualGrades.keys.filter { visual -> frame?.roles?.none { it.visual == visual } != false }
            .take(visualGrades.size - 8).forEach { visualGrades.remove(it) }
        // Kept past the end of the transition until the settled decision is current (see [settled]).
        if (destination != null && destinationLightInk != destination) destinationLightInk = destination
    }
    /** Holds [decision] at a fixed polarity so a presentation test controls exactly when it changes. */
    private var pinned: Boolean? = null
    internal fun pin(lightInk: Boolean?) { pinned = lightInk; if (lightInk != null) decision = PlayerBackgroundDecision(lightInk, PlayerBackgroundConfidence.Current, null) }
    fun measure(name: String, value: LayoutCoordinates) {
        coordinates[name] = value
        val background = coordinates["background"]?.takeIf { it.isAttached } ?: return
        val next = preparePlayerMeasuredBounds(measured, coordinates.keys) { key ->
            val child = coordinates.getValue(key)
            if (!child.isAttached) null else background.localBoundingBoxOf(child, clipBounds = false)
        }
        if (measured !== next) measured = next
    }
    /**
     * #634: the last settled ordinary full-player geometry, retained across minimization. It is what the
     * full player grades a song with once settled, so a song prepared against it while minimized gets the
     * same grade. Plain metadata: never observed by UI.
     */
    internal var settledEndpoint: PlayerBackgroundGeometry? = null
    /** Publishes a grade prepared before its backdrop mounted; a measured grade for the same visual wins. */
    internal fun prewarmed(visual: PlayerVisual, grade: BackdropRoleGrade, images: List<PreparedToneImage>) {
        // Keep the previous song's set until this one is complete, then replace it in one step.
        PreparedToneImage.retain(this, images)
        if (visualGrades[visual] == null) visualGrades[visual] = grade
        if (visualGrades.size > 8) visualGrades.keys.filter { it != visual && frame?.roles?.none { role -> role.visual == it } != false }
            .take(visualGrades.size - 8).forEach { visualGrades.remove(it) }
    }
    /** True once [visual]'s full-player backdrop can draw graded on its first frame (#634). */
    internal fun prepared(visual: PlayerVisual): Boolean = grade(null, visual) != null
    fun detach() {
        PreparedToneImage.retain(this, emptyList())
        preparedLyricsInk = null
        coordinates.clear(); measured = emptyMap(); frame = null; backdrops.clear(); roleGrades = emptyMap(); visualGrades.clear(); destinationLightInk = null; artworkClock = 1f
        unavailable(PlayerBackgroundUnavailable.Unmeasured)
    }
    fun frame(value: ArtworkBackgroundFrame) {
        if (frame != value) frame = value
        if (artworkClock != value.clock) artworkClock = value.clock
    }
    fun backdrop(identity: Any, value: PlayerBackdropDescriptor) {
        if (backdrops[identity] != value) backdrops[identity] = value
        // Only current/outgoing and their soft roles are needed. No unbounded visit history.
        if (backdrops.size > 8) backdrops.keys.filter { key -> key != identity && frame?.roles?.none { it.identity == key } != false }
            .take(backdrops.size - 8).forEach { backdrops.remove(it) }
    }
    fun unavailable(reason: PlayerBackgroundUnavailable): Long = publication.begin(reason).also {
        if (pinned != null) return@also
        sampleDiagnostic = null
        decision = publication.decision
    }
    fun current(lightInk: Boolean) {
        if (pinned != null) return
        val token = publication.begin(PlayerBackgroundUnavailable.Preparing)
        publication.publish(token, lightInk)
        decision = publication.decision
    }
}

/** Optional suspension seam for deterministic first-frame preparation tests. */
internal val LocalPlayerBackgroundPreparationBarrier = staticCompositionLocalOf<(suspend () -> Unit)?> { null }

/** The role key of a song prepared before any blend role exists for it. */
private object PrewarmRole

/**
 * #634: while the player shows [visual], even minimized, prepare its full-player backdrop: the grade at the
 * settled full-player [geometry] and the graded wash pixels, off Main, before any expansion asks for them.
 * The backdrop then draws graded on its first expanding frame instead of waiting for a measurement that
 * only a settled full player can finish. Uses the same sampler and grade as the settled measurement.
 */
@Composable internal fun PrewarmPlayerBackdrop(owner: PlayerBackgroundPolarityState, visual: PlayerVisual,
    geometry: PlayerBackgroundGeometry?, grading: Boolean) {
    val barrier = LocalPlayerBackgroundPreparationBarrier.current
    // A known grade, measured or prewarmed, needs no new geometry; only its pixels are re-checked.
    val known = owner.grade(null, visual)
    LaunchedEffect(owner, visual, if (known == null) geometry else null, known, grading, barrier) {
        // Missing art keeps its own neutral grade; Off draws untoned and needs nothing prepared.
        if (!grading || visual.artwork == null || visual.loading) return@LaunchedEffect
        val grade = known ?: run {
            val endpoint = geometry ?: return@LaunchedEffect
            barrier?.invoke()
            val role = BackgroundSampleRole(PrewarmRole, visual, PlayerBackdropDescriptor(visual.palette,
                visual.palette.wash, visual.palette.softWash, playerGrain, GlassTone()))
            val sample = withContext(Dispatchers.Default) { prepareBackground(BackgroundRequest(endpoint, listOf(role))) }
                ?: return@LaunchedEffect
            gradeRoles(sample)[PrewarmRole] ?: return@LaunchedEffect
        }
        val tone = GlassTone(shift = grade.shift)
        val images = listOfNotNull(visual.palette.wash, visual.palette.softWash).mapNotNull { PreparedToneImage.prepare(it, tone) }
        currentCoroutineContext().ensureActive()
        owner.prewarmed(visual, grade, images)
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
    geometry: PlayerBackgroundGeometry?, unsupported: PlayerBackgroundUnavailable?, requestedTone: GlassTone,
    lyricsEndpoints: Pair<PlayerBackgroundGeometry, PlayerBackgroundGeometry>? = null) {
    val frame = owner.frame
    // Grading and the transition's destination ink need only each role's own prepared pixels, not a settled or
    // unblurred composite, so they are available all through an artwork change (#578).
    // Partial expansion still has measurable owned pixels; prepare its grade before
    // the backdrop contributes, while the settled ink policy below stays unchanged.
    val gradeReason = unsupported?.takeUnless { it == PlayerBackgroundUnavailable.PartialPlayer } ?: when {
        frame == null || geometry == null -> PlayerBackgroundUnavailable.Unmeasured
        !validBackgroundGeometry(geometry) -> PlayerBackgroundUnavailable.InvalidGeometry
        frame.roles.any { owner.backdrops[it.identity] == null } -> PlayerBackgroundUnavailable.MissingRole
        frame.roles.any { role -> owner.backdrops[role.identity]?.tone != requestedTone } -> PlayerBackgroundUnavailable.Preparing
        else -> null
    }
    val reason = unsupported ?: when {
        frame == null || geometry == null -> PlayerBackgroundUnavailable.Unmeasured
        !validBackgroundGeometry(geometry) -> PlayerBackgroundUnavailable.InvalidGeometry
        frame.frozenVisible -> PlayerBackgroundUnavailable.FrozenSampleUnavailable
        frame.liveBlur -> PlayerBackgroundUnavailable.NativeBlurSampleUnavailable
        // A live crossfade has the same owned images and reveal weights as the
        // painter. Mix those prepared samples even before the successor settles.
        // Frozen captures and native blur remain explicitly unavailable above.
        frame.roles.any { owner.backdrops[it.identity] == null } -> PlayerBackgroundUnavailable.MissingRole
        frame.roles.any { role -> owner.backdrops[role.identity]?.tone != requestedTone } -> PlayerBackgroundUnavailable.Preparing
        else -> null
    }
    val request = if (gradeReason == null && geometry != null && frame != null)
        BackgroundRequest(geometry, frame.roles.map { BackgroundSampleRole(it.identity, it.visual, requireNotNull(owner.backdrops[it.identity])) }) else null
    // Endpoint geometry is independent of lyrics visibility, so this preparation
    // is not cancelled and restarted on every animation frame.
    // Closing lyrics remounts the actions row before its first measurement.
    // Its destination bounds are already known; that temporary missing CURRENT
    // footprint must not discard the prepared endpoint ink mid-transition.
    val endpointRoles = frame?.takeIf { !it.transitioning && !it.frozenVisible && !it.liveBlur }?.roles
        ?.takeIf { roles -> roles.all { owner.backdrops[it.identity]?.tone == requestedTone } }
        ?.map { BackgroundSampleRole(it.identity, it.visual, requireNotNull(owner.backdrops[it.identity])) }
    val endpointRequests = if (endpointRoles != null && lyricsEndpoints != null)
        lyricsEndpoints.toList().map { BackgroundRequest(it, endpointRoles) } else null
    val endpointWeights = if (endpointRequests != null) frame?.roles?.map { it.weight } else null
    LaunchedEffect(endpointRequests, endpointWeights) {
        owner.presentLyricsInk(null)
        if (endpointRequests != null) {
            val endpoints = withContext(Dispatchers.Default) {
                endpointRequests.map { endpoint ->
                    val sample = prepareBackground(endpoint) ?: return@withContext null
                    if (endpoint.roles.any { !it.paintReady(endpoint.geometry, requestedTone) }) return@withContext null
                    // A lyrics transition does not change artwork ownership. Artwork
                    // transitions continue to use their existing blend clock below.
                    val choice = backgroundSingleContrast(sample.samples.zip(requireNotNull(endpointWeights)), sample.weights)
                        ?: return@withContext null
                    choice.lightInk to gradeRoles(sample)
                }
            }
            currentCoroutineContext().ensureActive()
            if (endpoints != null) owner.presentLyricsInk(PlayerLyricsInk(endpoints[0].first, endpoints[1].first,
                endpoints[0].second.mapValues { (identity, grade) -> grade.shift to requireNotNull(endpoints[1].second[identity]).shift }))
        }
    }
    var prepared by remember { mutableStateOf<PreparedBackground?>(null) }
    var failed by remember { mutableStateOf<BackgroundRequest?>(null) }
    val preparationBarrier = LocalPlayerBackgroundPreparationBarrier.current
    LaunchedEffect(request, preparationBarrier) {
        prepared = null; failed = null
        if (request != null) {
            preparationBarrier?.invoke()
            val result = withContext(Dispatchers.Default) { prepareBackground(request) }
            currentCoroutineContext().ensureActive()
            if (result == null) failed = request else prepared = result
        }
    }
    val ready = prepared?.takeIf { it.request == request }
    val grades = remember(ready) { ready?.let(::gradeRoles) }
    val failure = reason ?: if (failed == request && request != null) PlayerBackgroundUnavailable.UnreadableImage else PlayerBackgroundUnavailable.Preparing
    SideEffect {
        if (ready != null && frame != null && grades != null) {
            val destination = frame.roles.firstOrNull { it.incoming && !it.soft }?.takeIf { frame.transitioning }
            owner.presentGrades(grades, destination?.let { grades[it.identity]?.lightInk })
        }
        if (ready != null && frame != null && reason == null) {
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
            if (!frame.transitioning) owner.settled()
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
            value.artworkAlpha, value.corner).all { it.isFinite() } &&
        value.edgeBand > 0f && value.corner >= 0f

private fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
// ArtworkEdge uses 33 gradient stops, not a continuously evaluated cubic.
private fun featherStop(position: Float, value: (Float) -> Float): Float {
    val scaled = position.coerceIn(0f, 1f) * 32f; val low = scaled.toInt().coerceAtMost(31)
    return value(low / 32f) + (value((low + 1) / 32f) - value(low / 32f)) * (scaled - low)
}
internal fun playerBackgroundFeather(x: Float, y: Float, amount: Float, band: Float, continuation: Float): Float {
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
        fun bounds(pixels: BackgroundPixels?) = pixels?.let {
            playerBackgroundImageBounds(Size(it.width.toFloat(), it.height.toFloat()), geometry.cover, geometry.contentScale)
        }
        val coverImage = bounds(cover)
        val softCoverImage = bounds(softCover)
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
                fun image(pixels: BackgroundPixels, bounds: Rect?): BackgroundRgb? {
                    bounds ?: return null
                    val u = (point.x - bounds.left) / bounds.width
                    val v = (point.y - bounds.top) / bounds.height
                    return if (u in 0f..1f && v in 0f..1f) pixels.at(u, v) else null
                }
                if (softCover != null && geometry.edgeSoftness > 0f) image(softCover, softCoverImage)?.let {
                    result = it.over(result, geometry.artworkAlpha * geometry.edgeSoftness * playerBackgroundFeather(x, y, 1f, .13f, 0f))
                }
                val radius = geometry.corner.coerceAtMost(minOf(geometry.cover.width, geometry.cover.height) / 2)
                val dx = max(0f, radius - min(point.x - geometry.cover.left, geometry.cover.right - point.x))
                val dy = max(0f, radius - min(point.y - geometry.cover.top, geometry.cover.bottom - point.y))
                if (dx * dx + dy * dy <= radius * radius) {
                    // Missing/loading placeholders have their own foreground; do not invent their pixels.
                    if (cover == null) return null
                    var direct = visual.palette.middle.rgb()
                    image(cover, coverImage)?.let { direct = it.over(direct) }
                    result = direct.over(result, geometry.artworkAlpha * playerBackgroundFeather(x, y, geometry.edgeSoftness, geometry.edgeBand, geometry.coverBlend))
                }
            }
            result
        }
    }
    return PreparedBackground(request, samples, areas)
}

/** Each prepared role is graded for itself (#576): its own mean decides its ink with the ordinary contrast rule and
 * its lightness shift for that ink. Weights never enter, so a crossfade only mixes already graded backdrops. */
private fun gradeRoles(prepared: PreparedBackground): Map<Any, BackdropRoleGrade> =
    prepared.request.roles.mapIndexedNotNull { index, role ->
        var mean: BackgroundMean? = null
        val light = backgroundLightInk(listOf(prepared.samples[index] to 1f), prepared.weights) { mean = it }
        val m = mean ?: return@mapIndexedNotNull null
        val shift = BackdropGrade.lightnessShift(m.red.coerceIn(0.0, 1.0), m.green.coerceIn(0.0, 1.0), m.blue.coerceIn(0.0, 1.0), light)
        role.identity to BackdropRoleGrade(light, if (shift.isFinite()) shift else 0f)
    }.toMap()

/**
 * #634: the ordinary full player at rest (lyrics closed, no swipe, scrolled to the top) for a cover of [aspect],
 * from the same layout rules [PlayerSurface] applies at full expansion. A retained measurement of that rest state
 * supplies the exact control footprints, and is used whole when its cover already has this shape.
 */
internal fun settledPlayerGeometry(aspect: Float, background: Float, windowWidth: androidx.compose.ui.unit.Dp,
    windowHeight: androidx.compose.ui.unit.Dp, virtualHeight: androidx.compose.ui.unit.Dp, floatingSlot: androidx.compose.ui.unit.Dp,
    band: PlayerArtworkBand, miniArtY: androidx.compose.ui.unit.Dp, metadataY: androidx.compose.ui.unit.Dp,
    transportY: androidx.compose.ui.unit.Dp, contentInset: androidx.compose.ui.unit.Dp, transportWidth: androidx.compose.ui.unit.Dp,
    actionRows: Int, titleHeight: Float, artistHeight: Float, seekLabelHeight: Float,
    density: androidx.compose.ui.unit.Density, retained: PlayerBackgroundGeometry?): PlayerBackgroundGeometry? = with(density) {
    if (!aspect.isFinite() || aspect <= 0f) return null
    fun dp(value: Float) = androidx.compose.ui.unit.Dp(value)
    val slotWidth = minOf(floatingSlot, floatingSlot * aspect)
    val slotHeight = minOf(floatingSlot, floatingSlot / aspect)
    val fit = minOf(1f, band.fittingHeight() / slotHeight.value)
    val floatingWidth = slotWidth * fit
    val floatingHeight = slotHeight * fit
    val width = floatingWidth + (windowWidth - floatingWidth) * background
    val height = floatingHeight + (windowWidth / aspect - floatingHeight) * background
    val left = (windowWidth - width) / 2
    val top = dp(band.artworkTop(height.value))
    val cover = Rect(left.toPx(), top.toPx(), left.toPx() + width.toPx(), top.toPx() + height.toPx())
    val size = Size(windowWidth.toPx(), virtualHeight.toPx())
    if (retained != null && retained.size == size && retained.fullArtBlend == background &&
        kotlin.math.abs(retained.cover.width / retained.cover.height - aspect) < .01f) return retained
    val contentWidth = windowWidth - contentInset * 2
    fun rect(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) =
        Rect(x.toPx(), y.toPx(), (x + w).toPx(), (y + h).toPx())
    val footprints = retained?.takeIf { it.size == size }?.footprints ?: playerBackgroundFootprints(Rect(Offset.Zero, size),
        Rect(0f, 0f, windowWidth.toPx(), windowHeight.toPx()), listOfNotNull(
            rect(contentInset, metadataY, contentWidth, dp(titleHeight)),
            rect(contentInset, metadataY + 34.dp, contentWidth, dp(artistHeight)),
            rect(contentInset, metadataY + 64.dp, contentWidth, 40.dp + dp(seekLabelHeight)),
            if (actionRows > 0) rect(contentInset, metadataY + 128.dp, contentWidth, 48.dp * actionRows) else null,
            rect((windowWidth - transportWidth) / 2, transportY, transportWidth, 64.dp)))
    if (footprints.isEmpty()) return null
    val clearance = if (minOf(width, height) < MiniArtworkSize) 0f else
        playerArtworkClearance(top.value, height.value, metadataY.value, miniArtY.value, MiniArtworkSize.value)
    PlayerBackgroundGeometry(size, cover, cover, footprints, background, 1f - background, background, .26f, background, 1f,
        (24.dp * (1f - background)).toPx(), playerArtworkContentScale(MiniArtworkSize.toPx(), if (clearance > 0f) 1f else 0f, false))
}
