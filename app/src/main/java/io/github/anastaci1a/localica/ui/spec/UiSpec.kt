package io.github.anastaci1a.localica.ui.spec

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.*

/** Neutral extent for an absent inset; independent of configurable spacing. */
val EmptyInset: Dp = Dp.Hairline

enum class Space { Hairline, Tight, Compact, Base, Comfortable, Section, Panel }
enum class Radius { Artwork, Card, Menu, LargeArtwork, Panel, Pill }
enum class ControlSize { NavigationPill, Compact, MiniArtwork, CollapsedQueue, DragBubble, Icon, PreferenceRow, PreferenceCompact, SettingsIcon }
enum class Clearance { FloatingGap, LiftMax, LiftClearance, ShadowInset, BottomChrome }
enum class MotionProfile { Control, ControlOff, Selection, Hover, Reveal, ResolutionBlend, Floating, Heading, ArtworkLayout, CropRetention, Marquee, Scroll, SettingsHighlight, SettingsScroll, LyricEmphasis }
enum class SpringProfile { GlassMorph, Page, GlassMode }
enum class MaterialRole { Glass, Panel, Selection, DragPane, Page, Scrim }
enum class MotionAccess { Standard, Reduced }
enum class GlassMode { Normal, FlatNone }
/** A unit motion curve from 0 to 1 over u in [0,1]: a cubic Bezier [EasingSpec] or a [SpringCurveSpec]. */
@Immutable sealed interface UnitCurveSpec { val maxSlope: Float }
@Immutable data class EasingSpec(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : UnitCurveSpec { override val maxSlope: Float by lazy { slopeBound(this) } }
/** A critically damped spring from rest, 1-(1+wt)e^(-wt), normalised over its settle [span] = wT so it ends exactly at 1 (#846). */
@Immutable data class SpringCurveSpec(val span: Float) : UnitCurveSpec { override val maxSlope: Float by lazy { springSlopeBound(span) } }
@Immutable sealed interface MotionSpec
@Immutable data class MotionProfileSpec(val durationMs: Int, val easing: EasingSpec) : MotionSpec
@Immutable data class MarqueeSpec(val pauseMs: Int, val fadeMs: Int, val returnMs: Int, val speedDpPerSecond: Float, val easing: EasingSpec) : MotionSpec
@Immutable data class SettingsScrollSpec(val normalMs: Int, val fasterMs: Int, val easing: EasingSpec) : MotionSpec
@Immutable data class SettingsHighlightSpec(val revealMs: Int, val holdMs: Int, val fadeMs: Int, val reducedHoldMs: Int, val opacity: Float, val easing: EasingSpec) : MotionSpec
@Immutable data class ListMotionSpec(val listMs: Int, val gridMs: Int, val fadeMs: Int, val easing: EasingSpec, val fadeEasing: EasingSpec) : MotionSpec
@Immutable data class SpringProfileSpec(val dampingRatio: Float, val stiffness: Float, val speedExponent: Int = 0)
@Immutable data class ExpansionGeometryDomain(val referenceTravelPx: Float,val maxViewportDiagonalPx: Float,val maxDisplayIntervalSeconds: Float) { init { require(referenceTravelPx.isFinite() && referenceTravelPx>0 && maxViewportDiagonalPx.isFinite() && maxViewportDiagonalPx>=1 && maxDisplayIntervalSeconds.isFinite() && maxDisplayIntervalSeconds>0) } }
@Immutable data class ExpansionSpec(val core: UnitCurveSpec, val referenceDurationMs: Float, val positionFloor: Float, val velocityKnee: Float, val velocityExponent: Float, val sizeExponent: Float, val floorMs: Float, val deadBand: Float, val stationaryOpenAt: Float, val band: Float)
/** A damped spring law: natural frequency [omega] in rad/s at animation speed 1.0 and damping ratio [zeta] (#854). */
@Immutable data class SpringLaw(val omega: Float, val zeta: Float)
/** The popup surface families of the #854 model. [Legacy] keeps the v0.33.21 popup spring ([UiSpec.expansion]). */
enum class PopupKind { Menu, RowMenu, BottomMenu, Dialog, SelectionPill, Legacy }
enum class PopupMotionStyle(val label: String) { EdgeSpring("Edge spring"), Fluid("Fluid") }
enum class PopupMotionVariant { Base, CrossAxisSnap, Auto, Legacy }
enum class PopupDirection { Open, Close, Gate }
/** #854/#855 measured Apple popup motion. Every popup trajectory, test reference and admission bound derives from this.
 * Laws are read only through [law], so a revision can make them per kind without touching callers. */
@Immutable data class PopupMotionModel(val revision: String, val open: SpringLaw, val close: SpringLaw, val crossOpen: SpringLaw, val gate: SpringLaw,
    val snapAspect: Float, val preparedRamp: Float, val rampBlend: Float, val variants: Map<PopupKind,PopupMotionVariant>,
    /** The phase band [-band, 1 + band] every spring channel must stay in; admission and the engine's extremum proofs both read it. */
    val band: Float = StockPopupExpansionBand, val centre: SpringLaw? = null, val centreBand: Float = band) {
    fun variant(kind: PopupKind): PopupMotionVariant = variants[kind] ?: PopupMotionVariant.Base
    internal val reversalReach by lazy { popupReversalReach(this) }
    /** One channel's law: the main axis by [direction]; only a [cross] open uses the snap law; close and gate run the main law on every edge. */
    @Suppress("UNUSED_PARAMETER") fun law(kind: PopupKind, direction: PopupDirection, cross: Boolean): SpringLaw =
        if(cross && direction==PopupDirection.Open) centre ?: crossOpen else when(direction) { PopupDirection.Open -> open; PopupDirection.Close -> close; PopupDirection.Gate -> gate }
}
/** Provenance of [StockPopupMotion]: revision 855-ios262-60fps-interim (feedback/issues/855-apple-popup-motion/pass2/INTERIM-MODEL.md,
 * lead swap 2026-11-11), -r926: iOS 26.2 60 fps menu fits open (28, .78), close (20, 1) with the gate = close; open cross snap (120, 1) unchanged from
 * pass 1 (not yet measured in 26.2); close and gate have no cross law (#926); dialogs use the open law. The 10–13 % bar-button far-edge bounce is deliberately not reproduced
 * (it would leave the .05 band). Supersedes 855-wwdc25-30fps and the lead close-omega-24 ruling. T1 pins the model to this record. */
internal val StockPopupMotionRecord: Map<String,List<Float>> = mapOf("open" to listOf(28f,.78f), "close" to listOf(20f,1f), "crossOpen" to listOf(120f,1f), "gate" to listOf(20f,1f), "snapAspect" to listOf(1.4f), "preparedRamp" to listOf(.25f,.05f))
internal const val StockPopupMotionRevision = "855-ios262-60fps-interim-r926"
internal val StockPopupMotion = PopupMotionModel(StockPopupMotionRevision, SpringLaw(28f,.78f), SpringLaw(20f,1f), SpringLaw(120f,1f), SpringLaw(20f,1f), 1.4f, .25f, .05f,
    mapOf(PopupKind.Menu to PopupMotionVariant.Base, PopupKind.RowMenu to PopupMotionVariant.Base, PopupKind.BottomMenu to PopupMotionVariant.CrossAxisSnap, PopupKind.Dialog to PopupMotionVariant.Base,
        PopupKind.SelectionPill to PopupMotionVariant.Base, PopupKind.Legacy to PopupMotionVariant.Legacy))
// Provisional neutral law while #1083 pass 2 determines the fluid shape; replace only this spec.
internal fun fluidPopupMotion() = StockPopupMotion.copy(centre = StockPopupMotion.open)
internal fun fluidPreviewMorphs(compact: Boolean, overlapsPreview: () -> Boolean) =
    compact && overlapsPreview()
internal fun fluidIntermediateY(sourceY: Double, targetY: Double, ratio: Double, cap: Double) =
    sourceY + (ratio * (targetY - sourceY)).coerceIn(-cap, cap)
internal fun fluidStoredDelay(storedDelay: Double, speed: Double, amplify: Boolean) =
    storedDelay * speed * (if (amplify) 10.0 else 1.0)
private fun fluidThroughTailDelay(growing: Boolean) = if (growing) 0.12 else 0.1
internal class FluidPopupSpringSettings(
    val dampingRatio: Double, val response: Double, val retargetImpulse: Double = 0.0, val trackingResponse: Double = 0.15,
) {
    val mass get() = 1.0
    val omega = 2 * PI / response
    val stiffness = omega * omega
    val damping = 2 * dampingRatio * omega
    private val installedDamping = (sqrt(stiffness) + sqrt(stiffness)) * dampingRatio
    val velocityScalingFactor get() = installedDamping / stiffness
    fun advance(body: FluidScalarSpringBody, target: Double, dt: Double) =
        SpringKernel(sqrt(stiffness), dampingRatio, installedDamping).advanceBody(body, target, dt)

    fun scaledResponses(multiplier: Double) =
        FluidPopupSpringSettings(dampingRatio, multiplier * response, retargetImpulse, multiplier * trackingResponse)
}
internal val FluidMainEjectDefaults = FluidPopupSpringSettings(dampingRatio = 0.75, response = 0.5)

internal data class FluidContentSettings(
    val minScale: Double, val maxScale: Double,
    val blurInSpring: FluidPopupSpringSettings, val blurOutSpring: FluidPopupSpringSettings,
    val speed: Double, val amplifyDelays: Boolean, val lensingEnabled: Boolean,
) {
    fun blurSequence(growing: Boolean): FluidContentBlurSequence? =
        if (lensingEnabled) FluidContentBlurSequence(
            FluidPopupStep(0.0, blurInSpring),
            FluidPopupStep(fluidThroughTailDelay(growing), blurOutSpring),
        ) else null
}
internal val FluidContentDefaults = FluidContentSettings(
    minScale = 1.2, maxScale = 1.65,
    blurInSpring = FluidPopupSpringSettings(dampingRatio = 1.0, response = 0.3),
    blurOutSpring = FluidPopupSpringSettings(dampingRatio = 0.8, response = 0.45),
    speed = 0.7, amplifyDelays = false, lensingEnabled = true,
)
internal data class FluidPopupStep<out Spring>(val storedDelay: Double, val spring: Spring) {
    fun dueThreshold(effectiveSpeed: Double, amplify: Boolean, drag: Double) = fluidStoredDelay(storedDelay, effectiveSpeed, amplify) * drag
}
internal data class FluidPropertySpringControls(
    val delay: Double, val startingVelocity: Double?, val interactive: Boolean, val additive: Boolean,
) {
    /** The scalar body handed from the property owner to the ordinary zero-delay spring. */
    fun immediateBody(
        requestedStart: Double, target: Double, retained: FluidScalarSpringBody?, previousTarget: Double?,
        spring: FluidPopupSpringSettings?, pendingVelocity: Double?,
    ): FluidScalarSpringBody {
        val value = retained?.value ?: requestedStart
        val velocity = when {
            pendingVelocity != null -> pendingVelocity
            startingVelocity != null -> (target - requestedStart) * startingVelocity
            retained == null -> 0.0
            previousTarget != null && spring != null && spring.retargetImpulse > 0.0 && spring.dampingRatio > 0.0 && spring.response > 0.0 ->
                retained.velocity + (target - previousTarget) * (spring.retargetImpulse * spring.stiffness)
            else -> retained.velocity
        }
        return FluidScalarSpringBody(value, velocity)
    }
}

internal data class FluidScalarSpringBody(val value: Double, val velocity: Double) {
    fun finished(target: Double, velocityScalingFactor: Double, epsilonSquared: Double, hasPending: Boolean): Boolean {
        if (hasPending) return false
        if (!value.isFinite() || !velocity.isFinite()) return true
        val combined = (target - value) + (velocity * velocityScalingFactor)
        return !combined.isFinite() || abs(combined) <= epsilonSquared
    }
}

/** Publication state of one running property entry. The owner supplies its value equality rule. */
internal class FluidScalarPublication {
    private var previousValue: Double? = null
    private var pending: FluidScalarSpringBody? = null

    fun queueTick(body: FluidScalarSpringBody, target: Double, finished: Boolean, sameValue: (Double, Double) -> Boolean) {
        val sample = if (finished) FluidScalarSpringBody(target, 0.0) else body
        val previous = previousValue
        if (previous == null || !sameValue(sample.value, previous)) pending = sample
        previousValue = sample.value
    }

    fun consume(write: (FluidScalarSpringBody) -> Unit) {
        val sample = pending ?: return
        pending = null
        write(sample)
    }
}

internal fun fluidThroughMainSpring(growing: Boolean, eject: FluidPopupSpringSettings, absorb: FluidPopupSpringSettings) =
    if (growing) eject else absorb

internal enum class FluidTimelineAction {
    Source, AddSdfElements, CollapsedSource, ExpandedOverrounded, FullyExpanded, Kick, Unkick,
    KickEjectee, UnkickEjectee, KickSource, UnkickSource, SmoothOn, SmoothOff,
    LensOn, ChangeLensDimension, LensOff, BlurOut, BlurIn,
}

internal data class FluidTimelineStep(
    val action: FluidTimelineAction, val timing: FluidPopupStep<FluidPopupSpringSettings?>, val lens: FluidLensingSpec.Request? = null,
) {
    fun propertySpring(effectiveSpeed: Double) = timing.spring?.scaledResponses(effectiveSpeed)
}

/** One supplied behavior drives position X/Y, width, height, content morph and transform. */
internal data class FluidGeometrySpringBinding(val sharedSpring: FluidPopupSpringSettings?) {
    val tracksPresentationLayer get() = true
}

internal fun FluidTimelineStep.geometrySpringBinding(effectiveSpeed: Double) =
    FluidGeometrySpringBinding(propertySpring(effectiveSpeed))

internal fun fluidPositionSpring(resolved: FluidPopupSpringSettings?) = resolved ?: FluidPopupSpringSettings(1.0, 0.4)

internal fun fluidThroughQueue(
    growing: Boolean, mainEject: FluidPopupSpringSettings, content: FluidContentSettings,
    lensRequests: () -> List<FluidLensingSpec.Request>,
): List<FluidTimelineStep> = buildList {
    fun step(action: FluidTimelineAction, delay: Double = 0.0, spring: FluidPopupSpringSettings? = null) {
        add(FluidTimelineStep(action, FluidPopupStep(delay, spring)))
    }
    if (growing) {
        step(FluidTimelineAction.Source)
        step(FluidTimelineAction.AddSdfElements)
        step(FluidTimelineAction.CollapsedSource, spring = FluidPopupSpringSettings(1.0, 0.3))
        step(FluidTimelineAction.ExpandedOverrounded, spring = mainEject)
        step(FluidTimelineAction.FullyExpanded, 0.1, mainEject)
        step(FluidTimelineAction.Kick, spring = FluidPopupSpringSettings(1.0, 0.2))
        step(FluidTimelineAction.Unkick, 0.1, mainEject)
    } else {
        step(FluidTimelineAction.FullyExpanded)
        step(FluidTimelineAction.AddSdfElements)
        step(FluidTimelineAction.Source)
        step(FluidTimelineAction.KickEjectee)
        step(FluidTimelineAction.UnkickEjectee, 0.1)
        step(FluidTimelineAction.KickSource)
        step(FluidTimelineAction.UnkickSource, Double.fromBits(0x3fc3333333333334L))
    }
    step(FluidTimelineAction.SmoothOn, spring = FluidPopupSpringSettings(1.0, 0.1))
    step(FluidTimelineAction.SmoothOff, if (growing) 0.3 else 0.1, FluidPopupSpringSettings(1.0, 0.6))
    content.blurSequence(growing)?.let { blur ->
        val (on, change, off) = lensRequests()
        add(FluidTimelineStep(FluidTimelineAction.LensOn, on.timing, on))
        add(FluidTimelineStep(FluidTimelineAction.ChangeLensDimension, change.timing, change))
        add(FluidTimelineStep(FluidTimelineAction.LensOff, off.timing, off))
        add(FluidTimelineStep(FluidTimelineAction.BlurOut, blur.blurOut))
        add(FluidTimelineStep(FluidTimelineAction.BlurIn, blur.blurIn))
    }
}.sortedBy { it.timing.storedDelay }

internal fun ArrayDeque<FluidTimelineStep>.replaceFluidTimeline(steps: List<FluidTimelineStep>) {
    clear()
    addAll(steps)
}

internal fun ArrayDeque<FluidTimelineStep>.drainFluidTimeline(
    elapsed: Double, effectiveSpeed: Double, amplify: Boolean, drag: () -> Double, holdingStart: Boolean,
    run: (FluidTimelineStep, Double) -> Unit,
) {
    val due = buildList {
        do {
            val action = this@drainFluidTimeline.firstOrNull() ?: break
            if (!holdingStart && !(action.timing.dueThreshold(effectiveSpeed, amplify, drag()) <= elapsed)) break
            add(this@drainFluidTimeline.removeFirst())
        } while (!holdingStart)
    }
    due.forEach { run(it, effectiveSpeed) }
}

internal object FluidKickSpec {
    const val growingSlot = 0
    const val unkickTarget = 0.0
    fun translationY(amount: Double, scale: Double) = scale * amount
}

internal data class FluidContentBlurSequence(
    val blurOut: FluidPopupStep<FluidPopupSpringSettings>, val blurIn: FluidPopupStep<FluidPopupSpringSettings>,
)
internal data class FluidContentBlurRequest(val portalScale: Double, val gaussianRadius: Double)
internal fun fluidContentBlurOut(height: Double, maxScale: () -> Double, minScale: () -> Double): FluidContentBlurRequest {
    val fraction = (height + (-215.0)) / 305.0
    val t = if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.0, 1.0)
    return FluidContentBlurRequest((1.0 - t) * maxScale() + t * minScale(), 4.0)
}
internal val FluidContentBlurIn = FluidContentBlurRequest(1.0, 0.0)

internal enum class FluidPopupChannel(val key: String, val value: Value) {
    DestinationOpacity("opacity", Value.PresentationScalar), ContentZoom("sublayerTransform", Value.Transform),
    GaussianRadius("filters.gaussianBlur.inputRadius", Value.ScaledScalar), LensHeight("effect.height", Value.CoarseScalar),
    LensAmount("filters.displacementMap.inputAmount", Value.CoarseScalar),
    KickAmount("MagicMorphAnimation.kickAmount", Value.StandardScalar), KickScale("MagicMorphAnimation.kickScale", Value.StandardScalar);

    enum class Value { PresentationScalar, Transform, ScaledScalar, CoarseScalar, StandardScalar }
}

internal fun fluidLensSuppressesAction(old: Double, new: Double, latest: Double?, bypass: Boolean) =
    !bypass && abs(new - old) <= 0.05 && (latest == null || abs(new - latest) <= 0.05)

/** Appends to the shared property owner's request queue and chooses its existing commit hook. */
internal fun <Request> MutableList<Request>.submitFluidPropertyPublication(
    request: Request, hasCoordinator: () -> Boolean, currentPhase: () -> Int,
    firstCommit: () -> Unit, afterCommit: () -> Unit,
) {
    add(request)
    if (hasCoordinator()) return
    if (currentPhase() == -1) firstCommit() else afterCommit()
}

internal fun <Coordinator> List<Pair<() -> Coordinator, (Coordinator) -> Unit>>.registerFluidPropertyPublications() {
    for (index in indices) {
        val (factory, register) = this[index]
        register(factory())
    }
}

internal data class FluidModifierFormat(val valueCount: Int, val additive: Boolean)

/** The admitted presentation owner supplies one store per receiving layer. */
internal fun <Modifier : Any> MutableMap<String, Modifier>.writeFluidPresentationModifier(
    channel: FluidPopupChannel, ordinaryReuse: Boolean, requested: FluidModifierFormat,
    format: (Modifier) -> FluidModifierFormat, update: (Modifier) -> Unit,
    cancelCreate: (String) -> Unit, enqueueCreate: (String) -> Unit,
) {
    val key = channel.key
    val modifier = this[key]
    if (modifier != null && ordinaryReuse && format(modifier) == requested) {
        update(modifier)
        return
    }
    clearFluidModifier(key, cancelCreate)
    enqueueCreate(key)
}

internal fun <Modifier : Any> MutableMap<String, Modifier>.completeFluidPresentationModifier(
    channel: FluidPopupChannel, cancelCreate: (String) -> Unit, removesImmediately: () -> Boolean,
    removeNow: (Modifier) -> Unit, enqueueRemoval: (Modifier) -> Unit,
) {
    val modifier = clearFluidModifier(channel.key, cancelCreate) ?: return
    if (removesImmediately()) removeNow(modifier) else enqueueRemoval(modifier)
}

private fun <Modifier : Any> MutableMap<String, Modifier>.clearFluidModifier(key: String, cancelCreate: (String) -> Unit): Modifier? {
    val modifier = remove(key)
    cancelCreate(key)
    return modifier
}

/** The original layer's resolved view/window supplies the live scale. Null means the view or window is absent. */
internal fun fluidLayerPointScale(resolvedContext: Boolean, windowScale: () -> Double?): Double =
    if (resolvedContext) windowScale() ?: 1.0 else 1.0

internal fun fluidRadiusEpsilonSquared(pointScale: Double): Double {
    val unit = 1.0 / pointScale
    return unit * unit
}

internal fun fluidZoomEpsilonSquared(pointScale: Double, size: Pair<Double, Double>?, empty: Boolean, anchorX: Double, anchorY: Double): DoubleArray {
    val translation = fluidRadiusEpsilonSquared(pointScale)
    val fixed = Double.fromBits(0x3eb0c6f7a0b5ed8dL)
    if (size == null) return doubleArrayOf(0.0001, fixed, translation, fixed, fixed)
    if (empty) return doubleArrayOf(0.01 * 0.01, 0.001 * 0.001, translation, 0.001 * 0.001, 0.001 * 0.001)
    val (width, height) = size
    val x = maxOf(anchorX, 1.0 - anchorX) * width
    val y = maxOf(anchorY, 1.0 - anchorY) * height
    val length = sqrt(x * x + y * y)
    val spatial = (1.0 / pointScale) / (if (length <= 1.0) 1.0 else length)
    val square = spatial * spatial
    return doubleArrayOf(square, square, translation, square, square)
}

internal fun fluidDestinationCrossBlur(
    explicit: Boolean?, previousId: Any?, incomingId: Any?, previous: Boolean, incomingGlass: Boolean,
) = explicit ?: if (previousId != null && previousId == incomingId) false else previous || incomingGlass

internal data class FluidDestinationBlend(val opacity: Double, val gaussianRadius: Double) {
    val layerOpacity get() = opacity.toFloat()

    /** The presentation owner receives absolute values with nil velocity, cap zero and immediate false. */
    fun present(write: ((FluidPopupChannel, Double) -> Unit)?) {
        write ?: return
        write(FluidPopupChannel.DestinationOpacity, opacity)
        write(FluidPopupChannel.GaussianRadius, gaussianRadius)
    }
}

internal fun fluidDestinationBlend(
    progress: Double, crossBlur: Boolean, allOpaque: Boolean, lastDestination: Boolean,
): FluidDestinationBlend {
    if (crossBlur) return FluidDestinationBlend(progress, progress * 0.0 + (1.0 - progress) * 4.0)
    if (allOpaque && !lastDestination) return FluidDestinationBlend(1.0, 0.0)
    val fraction = progress / 0.65
    val opacity = when {
        fraction.isNaN() || fraction > 1.0 -> 1.0
        fraction < 0.0 -> 0.0
        else -> fraction
    }
    return FluidDestinationBlend(opacity, 0.0)
}

internal object FluidGaussianSpec {
    enum class NarrowWidth { Taps7, Taps11, Taps15, Taps19, Taps23, Taps27 }
    enum class NarrowTexel { GaussianColor, Zero, ClampedSource, ExistingDestination }

    class NarrowComputePass(val function: Int, val textureFunction: Int, private val finalPass: Boolean) {
        val writeOptions get() = if (finalPass) 3 else 2

        fun texel(inside: Boolean, edgePolicy: Int) = when {
            inside -> NarrowTexel.GaussianColor
            finalPass -> NarrowTexel.ExistingDestination
            edgePolicy == 0 -> NarrowTexel.Zero
            else -> NarrowTexel.ClampedSource
        }
    }

    fun narrowCompute(width: NarrowWidth, finalRequested: Boolean, destinationFlags: Int): NarrowComputePass {
        val finalPass = finalRequested && (destinationFlags and 0xa0000000.toInt()) == 0x80000000.toInt()
        val base = if (finalPass) 0x18 else 0x12
        return NarrowComputePass(base + width.ordinal, 0x21 + width.ordinal, finalPass)
    }

    fun decodeBounds(encoded: DoubleArray?): DoubleArray? =
        encoded?.takeIf { it.size >= 4 }?.let { doubleArrayOf(it[0], it[1], it[2] - it[0], it[3] - it[1]) }

    fun edgeMode(normalizeEdges: Int, hardEdges: Int, explicitBounds: Boolean): Int = when {
        normalizeEdges != 0 -> 2
        hardEdges != 0 || explicitBounds -> 1
        else -> 0
    }

    fun effectiveRadius(inputRadius: Double, fillRadius: Double) =
        if (inputRadius > fillRadius) inputRadius else fillRadius

    fun isIdentity(inputRadius: Double, fillRadius: Double = 0.0) =
        !(effectiveRadius(inputRadius, fillRadius) > 0.0)

    fun edgePadding(inputRadius: Double, fillRadius: Double, scaleX: Double, scaleY: Double): Pair<Float, Float> {
        val padding = (effectiveRadius(inputRadius, fillRadius) * Double.fromBits(0x4006666660000000L)).toFloat()
        return (padding * scaleX.toFloat()) to (padding * scaleY.toFloat())
    }
}

internal object FluidLensingSpec {
    enum class Filter { GaussianBlur, DisplacementMap }
    val filterOrder get() = Filter.entries
    fun <Image : Any> colorChain(content: Image, emit: (Filter, Image) -> Image?): Image =
        filterOrder.fold(content) { color, filter -> emit(filter, color) ?: color }

    private val lensPropertyControls = FluidPropertySpringControls(delay = 0.0, startingVelocity = null, interactive = false, additive = false)

    fun publishSample(publication: FluidScalarPublication, write: (Double, Double, Int, Boolean, Boolean) -> Unit) {
        publication.consume { sample -> write(sample.value, sample.velocity, 0, false, lensPropertyControls.additive) }
    }

    data class Request(val timing: FluidPopupStep<FluidPopupSpringSettings>, val gain: Double, val dimension: Double?) {
        val propertyControls get() = lensPropertyControls
    }

    fun shortDimension(scale: Double, width: Double, height: Double): Double {
        val w = scale * width
        val h = scale * height
        return if (h < w) h else w
    }

    fun requests(
        enabled: Boolean, growing: Boolean, smallerShort: Double, largerShort: Double,
        largerAnchorY: Double, convertedLargerHeight: Double,
        mainEject: FluidPopupSpringSettings, mainAbsorb: FluidPopupSpringSettings,
    ): List<Request> {
        if (!enabled) return emptyList()
        val d0 = if (largerAnchorY == 0.5) convertedLargerHeight else smallerShort
        return listOf(
            Request(FluidPopupStep(0.0, FluidPopupSpringSettings(1.0, 0.2)), 1.0, if (growing) d0 else largerShort),
            Request(FluidPopupStep(0.0, fluidThroughMainSpring(growing, mainEject, mainAbsorb)), 1.0, if (growing) largerShort else d0),
            Request(FluidPopupStep(fluidThroughTailDelay(growing), FluidPopupSpringSettings(1.0, if (growing) 0.3 else 0.4)), 0.0, null),
        )
    }

    const val trackStoredDelay = 0.0
    const val trackedHitTestsAsFill = true
    val trackedProperties = listOf("bounds", "position", "cornerRadius", "cornerRadii", "cornerCurve", "hidden", "meshTransform")

    const val displacementFunction = 44
    const val namedSourceTexture = 1
    const val displacementVertexType = 6
    const val freshHeight = 20.0
    const val freshAmount = -0.001
    val freshOffset get() = offset(null)
    fun skipsDisplacementSample(maskBlue: Float) = maskBlue < Float.fromBits(0x3b808081)

    fun surfaceCoordinates(x: Float, y: Float, width: Int, height: Int, normalized: Boolean, kind: Int, flipped: Boolean): Pair<Float, Float> {
        val sx = if (normalized) 1f / width.toFloat() else 1f
        val sy = if (normalized) 1f / height.toFloat() else 1f
        val flip = flipped && ((1L shl (kind and 63)) and 0x0000042000fffe00L) == 0L
        return (x * sx + 0f) to if (flip) y * -sy + 1f else y * sy + 0f
    }

    fun <Layer : Any, Portal : Any> registerPortals(
        animations: Iterable<Layer>, lensingEnabled: Boolean, retained: IdentityHashMap<Layer, Portal>,
        clippingLayer: (Layer) -> Layer, outermostLayer: (Layer) -> Layer, mask: (Layer, Boolean) -> Unit,
        track: (Layer) -> Unit, attach: (Layer, Layer) -> Portal,
    ) {
        for (animation in animations) {
            mask(clippingLayer(animation), !lensingEnabled)
            track(animation)
            val clipping = clippingLayer(animation)
            if (!retained.containsKey(clipping)) retained[clipping] = attach(clipping, outermostLayer(animation))
        }
    }

    data class Vertex(
        val x: Float, val y: Float,
        val contentX: Float, val contentY: Float, val mapX: Float, val mapY: Float,
    )

    fun namedMapQuad(
        contentX: Int, contentY: Int, width: Int, height: Int, mapX: Int, mapY: Int,
        contentScale: Float, mapScale: Float, fusedMultiplyAdd: (Float, Float, Float) -> Float,
    ): List<Vertex> {
        val ratio = mapScale / contentScale
        val left = contentX.toFloat()
        val right = (contentX + width).toFloat()
        val top = contentY.toFloat()
        val bottom = (contentY + height).toFloat()
        val x0 = fusedMultiplyAdd(ratio, left, -mapX.toFloat())
        val x1 = fusedMultiplyAdd(ratio, right, -mapX.toFloat())
        val y0 = fusedMultiplyAdd(ratio, top, -mapY.toFloat())
        val y1 = fusedMultiplyAdd(ratio, bottom, -mapY.toFloat())
        val w = width.toFloat()
        val h = height.toFloat()
        return listOf(
            Vertex(contentScale * left, contentScale * top, 0f, 0f, x0, y0),
            Vertex(contentScale * right, contentScale * top, w, 0f, x1, y0),
            Vertex(contentScale * right, contentScale * bottom, w, h, x1, y1),
            Vertex(contentScale * left, contentScale * bottom, 0f, h, x0, y1),
        )
    }

    fun amountUniform(transform: DoubleArray, width: Int, height: Int, renderScale: Float, inputAmount: Double = 1.0): FloatArray {
        val amount = renderScale * inputAmount.toFloat()
        val b0 = transform[0].toFloat()
        val b1 = transform[4].toFloat()
        val b2 = transform[1].toFloat()
        val b3 = transform[5].toFloat()
        fun lane(b: Float, z: Float, extent: Int) = (b * amount) / extent.toFloat() + z * 0f
        return floatArrayOf(lane(b0, b2, width), lane(b1, b3, width), lane(b2, b0, height), lane(b3, b1, height))
    }

    fun usedExtent(usedX: Int, usedY: Int, width: Int, height: Int) =
        ((usedX.toDouble() - 0.5) / width).toFloat() to ((usedY.toDouble() - 0.5) / height).toFloat()

    fun offset(values: DoubleArray?): Pair<Float, Float> {
        val x = values?.getOrNull(0)?.toFloat() ?: 0f
        return x to (values?.getOrNull(1)?.toFloat() ?: x)
    }
}

internal object FluidCornerSpec {
    const val continuous = 1
    fun freshCurve(sdkVersionSet: Int) = if (sdkVersionSet >= 0x07e80000) continuous else 0
    fun matchedRadius(source: Double) = source.toFloat().let { if (it < 0f) 0f else it }
    fun matchedRadii(source: DoubleArray) = DoubleArray(source.size) { if (source[it] < 0.0) 0.0 else source[it] }

    fun rawRadii(precomputed: FloatArray?, suppressFallback: Boolean, vector: DoubleArray?, scalar: Float, shortSide: Double): FloatArray? {
        if (precomputed != null) return precomputed
        if (suppressFallback) return null
        if (vector != null) return FloatArray(4) { vector[it * 2].toFloat() }
        val radius = when {
            scalar.toRawBits() == 0x7fe00000 -> (shortSide * 0.5).toFloat()
            scalar.isNaN() -> 0f
            else -> scalar
        }
        return FloatArray(4) { radius }
    }

    data class FieldTransform(val scale: Float, val normalMatrix: FloatArray)

    fun fieldTransform(affine: DoubleArray?, fusedMultiplyAdd: (Float, Float, Float) -> Float): FieldTransform {
        if (affine == null) return FieldTransform(1f, floatArrayOf(1f, 0f, 0f, 1f))
        val a = affine[0].toFloat()
        val b = affine[1].toFloat()
        val c = affine[4].toFloat()
        val d = affine[5].toFloat()
        val determinant = fusedMultiplyAdd(d, a, -(c * b))
        val first = sqrt(fusedMultiplyAdd(d, d, b * b))
        val second = sqrt(fusedMultiplyAdd(c, c, a * a))
        val longest = when {
            first.isNaN() -> second
            second.isNaN() -> first
            else -> maxOf(first, second)
        }
        val inverse = 1f / longest
        val factor = if (determinant < 0f) -inverse else inverse
        return FieldTransform(inverse * abs(determinant), floatArrayOf(d * factor, -(b * factor), -(c * factor), a * factor))
    }

    data class BoundsEncoding(
        val imageFunction: Int, val family: Float, val normalMatrix: FloatArray, val radii: FloatArray, val expansion: Double,
    ) {
        fun splitsIntoRect(pad: Double, largerHalfExtent: Double) = !(expansion + pad >= largerHalfExtent)
    }

    fun boundsEncoding(raw: FloatArray, transform: FieldTransform, curveCode: Int, halfWidth: Float, halfHeight: Float): BoundsEncoding {
        val floor = Float.fromBits(0x3c23d70a)
        val clamped = FloatArray(4) { if (raw[it] < floor) floor else raw[it] }
        val uniform = clamped[0] == clamped[1] && clamped[1] == clamped[2] && clamped[2] == clamped[3]
        val scaled = FloatArray(4) { clamped[it] * transform.scale }
        val continuous = curveCode and 3 != 0
        val maxRadius = (if (uniform) scaled[0] else scaled.reduce { left, right -> if (left > right) left else right }).toDouble()
        val expansion = if (continuous) maxRadius * Double.fromBits(0x3ff875696e58a32fL) else maxRadius
        if (!uniform) return BoundsEncoding(22, 5f, transform.normalMatrix, if (continuous) FloatArray(4) { -scaled[it] } else scaled, expansion)
        fun weight(half: Float): Float {
            if (!continuous) return 1f
            val coefficient = Double.fromBits(0x400721e8a7a4b61bL)
            return ((half + half).toDouble() * -coefficient / expansion + coefficient).coerceIn(0.0, 1.0).toFloat()
        }
        return BoundsEncoding(21, 4f, transform.normalMatrix, floatArrayOf(weight(halfWidth), weight(halfHeight), scaled[0], 0f), expansion)
    }

    // Group texture selection is independent of the per-element image kind.
    fun groupTexture(subtract: Boolean, roiDirect: Boolean, roiOverlap: Boolean) = when {
        subtract -> 59
        roiDirect -> 1
        roiOverlap -> 58
        else -> 3
    }
    const val rectImageFunction = 20
    const val inlineEffectTexture = 66
    fun scansOverlap(parentAdmitted: Boolean, disableOption: Boolean) = parentAdmitted && !disableOption
    fun inlinesBounds(parentAllowsInline: Boolean, eligibleBoundsUnionChildren: Int) =
        parentAllowsInline && eligibleBoundsUnionChildren == 1
}

internal fun <Item, Assertion : Any> fluidHoldEndpoints(from: Item, to: Item, hide: (Item) -> Assertion?) =
    listOfNotNull(hide(from), hide(to))

internal fun fluidCompleteGroup(callback: (() -> Unit)?, releasePortals: () -> Unit,
    clearMorphables: () -> Unit, clearAnimations: () -> Unit, releaseHiding: () -> Unit,
) {
    callback?.invoke()
    releasePortals()
    clearMorphables()
    clearAnimations()
    releaseHiding()
}

internal fun fluidCompleteTransition(dismissing: Boolean, teardown: () -> Unit, removeContainer: () -> Unit, alongside: (() -> Unit)?) {
    if (dismissing) {
        teardown()
        removeContainer()
    }
    alongside?.invoke()
}

internal fun fluidImmediateUnhideSites(through: Boolean, dismissingToDrag: Boolean, hasStashedPreview: Boolean, transitionLoop: Boolean) =
    (!through && dismissingToDrag && hasStashedPreview) to (!through && transitionLoop)
/** #860: the slowest and fastest animation speed. Every engine duration divides by the speed and every spring omega multiplies by it. */
const val MinimumAnimationSpeed = .5f
const val MaximumAnimationSpeed = 1.5f
internal const val GlassShadowShift = 8f
internal const val GlassShadowMaskRadius = 24f
internal const val GlassShadowFill = .20f
internal fun glassShadowReach(spread: Float) = 2f * GlassShadowMaskRadius * spread
internal fun glassShadowInset(spread: Float) = (glassShadowReach(spread) + GlassShadowShift).dp
/** Layout padding and hit areas beside a shadow: independent of the draw reach (22.8 dp at the default multiplier). */
internal fun glassLayoutInset(spread: Float) = (6f + 16.8f * spread).dp
@Immutable data class MaterialTokens(val blur: Float, val whiteTint: Float, val shadowStrength: Float, val shadowSpread: Float, val contrast: Float, val liftShadowStrength: Float, val shapeDamping: Float, val depth: Float, val mode: GlassMode)
@Immutable class UiSpec internal constructor(val space: Map<Space,Dp>, val radius: Map<Radius,Dp>, val control: Map<ControlSize,Dp>, val clearance: Map<Clearance,Dp>, val motion: Map<MotionProfile,MotionSpec>, val spring: Map<SpringProfile,SpringProfileSpec>, val expansion: ExpansionSpec, val material: Map<MaterialRole,MaterialTokens>, val access: MotionAccess, internal val geometry: ExpansionGeometryDomain?=null, val sheetExpansion: ExpansionSpec=expansion, val lyricsExpansion: ExpansionSpec=expansion, val selectionExpansion: ExpansionSpec=expansion, val popupMotion: PopupMotionModel=StockPopupMotion, val speed: Float=1f)
@Immutable data class UiSpecData(val space: Map<Space,Dp>? = null, val radius: Map<Radius,Dp>? = null, val control: Map<ControlSize,Dp>? = null, val clearance: Map<Clearance,Dp>? = null, val motion: Map<MotionProfile,MotionSpec>? = null, val spring: Map<SpringProfile,SpringProfileSpec>? = null, val expansion: ExpansionSpec? = null, val material: Map<MaterialRole,MaterialTokens>? = null, val access: MotionAccess? = null, val sheetExpansion: ExpansionSpec? = null, val lyricsExpansion: ExpansionSpec? = null, val selectionExpansion: ExpansionSpec? = null, val popupMotion: PopupMotionModel? = null, val speed: Float? = null)
sealed interface SpecResult { data class Accepted(val spec: UiSpec) : SpecResult; data class Rejected(val reasons: List<String>) : SpecResult }
const val SettleThreshold = .0003f
const val SettleVelocityThreshold = .003f
const val vMax = 10f
internal const val MinimumContactBand = 1.1920928955078125e-7f
internal const val StrainMotionSpan = .24f
internal const val StrainPressureSpan = .34f
internal const val StrainDepthSpan = .48f
/** Pure admission envelope, never a substitute reference for motion owners. */ internal fun finiteExpansionFinish(e: ExpansionSpec,maxRatio: Double,maxDisplay: Double,clockSpan: Boolean=true): Boolean { val time=max(e.floorMs/1000.0,e.referenceDurationMs/1000.0*maxRatio.pow(e.sizeExponent.toDouble())); val finish=max(time*(e.positionFloor+(1.0-e.positionFloor)*max(1.0+e.band,(1f+e.band).toDouble())),2*maxDisplay); return time.isFinite() && time>0 && finish.isFinite() && finish.toFloat().isFinite() && (!clockSpan || finish*1e9<=Long.MAX_VALUE.toDouble()) }
private fun slopeBound(e: EasingSpec): Float {
    val x1=e.x1.toDouble(); val x2=e.x2.toDouble(); val y1=e.y1.toDouble(); val y2=e.y2.toDouble(); val x=doubleArrayOf(x1,2*(x2-2*x1),1-3*x2+3*x1); val y=doubleArrayOf(y1,2*(y2-2*y1),1-3*y2+3*y1)
    fun ratio(t: Double): Double { val u=1-t; val dx=u*u*x1+2*u*t*(x2-x1)+t*t*(1-x2); val dy=u*u*y1+2*u*t*(y2-y1)+t*t*(1-y2); return if(dx!=0.0) abs(dy/dx) else if(dy!=0.0) Double.POSITIVE_INFINITY else { val ddx=x[1]+2*t*x[2]; val ddy=y[1]+2*t*y[2]; if(ddx!=0.0) abs(ddy/ddx) else if(ddy!=0.0) Double.POSITIVE_INFINITY else abs(y[2]/x[2]) } }
    val roots=quadraticRoots(y[2]*x[1]-y[1]*x[2],2*(y[2]*x[0]-y[0]*x[2]),y[1]*x[0]-y[0]*x[1]).toList()
    return (listOf(0.0,1.0)+roots.filter { it>0 && it<1 }).maxOf(::ratio).let { Math.nextUp(it.toFloat()) }
}
internal fun quadraticRoots(a: Double,b: Double,c: Double): DoubleArray {
    if(a==0.0) return if(b==0.0) doubleArrayOf() else doubleArrayOf(-c/b); val d=b*b-4*a*c; if(d<0) return doubleArrayOf(); val q=-.5*(b+Math.copySign(sqrt(d),b)); return if(q==0.0) doubleArrayOf(-b/(2*a)) else doubleArrayOf(q/a,c/q)
}
private fun springSlopeBound(span: Float): Float { val a=span.toDouble(); return Math.nextUp((a/(E*(1-(1+a)*exp(-a)))).toFloat()) }
/** Value extrema of a unit curve; the spring is monotone. */
internal fun curveExtrema(c: UnitCurveSpec): ClosedFloatingPointRange<Double> = when(c) { is EasingSpec -> easingExtrema(c); is SpringCurveSpec -> 0.0..1.0 }
internal fun easingExtrema(e: EasingSpec): ClosedFloatingPointRange<Double> {
    val a=e.y1.toDouble(); val b=e.y2.toDouble(); val points=listOf(0.0,1.0)+quadraticRoots(3*(1+3*a-3*b),6*(b-2*a),3*a).filter { it>0 && it<1 }; val values=points.map { t -> val u=1-t; 3*u*u*t*a+3*u*t*t*b+t*t*t }; return values.min()..values.max()
}
private val slow = EasingSpec(0f,0f,.2f,1f)
private val fast = EasingSpec(.4f,0f,.2f,1f)
private val linear = EasingSpec(0f,0f,1f,1f)
private fun <K,V> frozen(base: Map<K,V>, overlay: Map<K,V>?) = Collections.unmodifiableMap(LinkedHashMap(base).apply { if(overlay!=null) putAll(overlay) })
private fun <K> dimensions(keys: List<K>, values: List<Float>) = frozen(keys.zip(values.map { it.dp }).toMap(),null)
/** Drawers (the player sheet and the Queue drawer: open, close and drag releases) are the #608 measured model: 426.086832 ms
 * cubic-bezier(.242192, .999967, .511935, .997652) at the full player, T = 426.1 ms x (.5004 + .4996 x remaining) at rest,
 * its velocity knee and exponent, and #608's square-root size rule with a 140 ms floor. 1x references; the platform scale
 * applies as to every law (operator 2026-11-11 3:17-3:20 AM, #846). */
private val StockSheetExpansion = ExpansionSpec(EasingSpec(.242192f,.999967f,.511935f,.997652f),426.086832f,.500401615f,1.41952128f,.73607278f,.5f,140f,.25f,.5f,.05f)
/** The player lyrics presence keeps v0.33.21's 412.5 ms FastOutSlowIn layout tween (motionDuration(550)) and its peak speed (#846). */
private val StockLyricsExpansion = ExpansionSpec(EasingSpec(.4f,0f,.2f,1f),412.5f,.500401615f,1.41952128f,.73607278f,0f,412.5f,.25f,.5f,.05f)
/** The floating selection panel and its scroll room keep v0.33.21's quickInsetAnimationSpec: tween(Selection 220 ms / speed) on the
 * Scroll easing cubic-bezier(.4, 0, .2, 1), snapped when reduced. At the default speed 4/3 (slider 1x) that is 165 ms; the old
 * Faster's speed 2 is the slider's 1.5x (110 ms). Size independent. It is not a popup, so it never takes the popup spring (#846, #869). */
internal const val SelectionPanelMs = 220f*3f/4f
private val StockSelectionExpansion = ExpansionSpec(EasingSpec(.4f,0f,.2f,1f),SelectionPanelMs,.500401615f,1.41952128f,.73607278f,0f,SelectionPanelMs,.25f,.5f,.05f)
/** v0.33.21 popups ran glassMorphSpring(4/3): critically damped, stiffness 90(4/3)^2, ending at its .0001 visibility threshold,
 * (1+a)e^(-a) = 1e-4. Size independent (size exponent 0); Faster's 2/3 time scale is its speed 2 (#846). */
internal const val PopupSpringSpan = 11.756371f
internal val PopupSpringMs = (PopupSpringSpan/(sqrt(90.0)*4/3)*1000).toFloat()
internal const val StockPopupExpansionBand = .05f
private val StockPopupExpansion = ExpansionSpec(SpringCurveSpec(PopupSpringSpan),PopupSpringMs,.500401615f,1.41952128f,.73607278f,0f,PopupSpringMs,.25f,.5f,StockPopupExpansionBand)
val StockUiSpec: UiSpec = UiSpec(dimensions(Space.entries,listOf(1f,4f,8f,12f,16f,24f,28f)), dimensions(Radius.entries.dropLast(1),listOf(8f,16f,20f,24f,28f)), dimensions(ControlSize.entries,listOf(64f,51.2f,32f,64f,28f,24f,56f,48f,24f)), dimensions(Clearance.entries,listOf(8f,4f,1f,56f,80f)),
    frozen<MotionProfile,MotionSpec>(MotionProfile.entries.take(10).zip(listOf(180,270,220,220,300,240,300,165,300,225).mapIndexed { i,d -> MotionProfileSpec(d,if(i in listOf(0,1,2,3,6)) slow else fast) }).toMap()+mapOf(MotionProfile.LyricEmphasis to MotionProfileSpec(350,fast),MotionProfile.Marquee to MarqueeSpec(1800,220,260,30f,EasingSpec(1f/3,0f,2f/3,1f)),MotionProfile.Scroll to ListMotionSpec(350,500,120,fast,linear),MotionProfile.SettingsScroll to SettingsScrollSpec(262,175,fast),MotionProfile.SettingsHighlight to SettingsHighlightSpec(150,450,600,1200,.18f,fast)),null),
    frozen(mapOf(SpringProfile.GlassMorph to SpringProfileSpec(1f,90f,2),SpringProfile.Page to SpringProfileSpec(.85f,380f),SpringProfile.GlassMode to SpringProfileSpec(1f,90f,2)),null), StockPopupExpansion, frozen(MaterialRole.entries.associateWith { MaterialTokens(.2f,.06f,1f,1f,.25f,1f,8f,1.35f,GlassMode.Normal) },null),MotionAccess.Standard,null,StockSheetExpansion,StockLyricsExpansion,StockSelectionExpansion)
fun uiSpec(settings: InterfaceSettings, overrides: UiSpecData? = null, geometry: ExpansionGeometryDomain? = null): SpecResult {
    val o=overrides?:UiSpecData(); val b=StockUiSpec; val g=settings.glass.normalized(); val errors=mutableListOf<String>()
    fun valid(ok: Boolean, key: String) { if(!ok) errors+=key }; fun bounded(v: Float,lo: Float,hi: Float)=v.isFinite() && v in lo..hi
    val rendererMode = GlassMode.valueOf(g.renderer.name)
    val space=frozen(b.space,o.space); val radius=frozen(b.radius,o.radius); val control=frozen(b.control,o.control); val material=frozen(MaterialRole.entries.associateWith { MaterialTokens(g.blur,g.whiteTint,g.shadowStrength,g.shadowSpread,g.contrast,g.liftShadowStrength,g.shapeDamping,g.depth,rendererMode) },o.material)
    val clearance=frozen(b.clearance+mapOf(Clearance.ShadowInset to glassShadowInset(material.getValue(MaterialRole.Glass).shadowSpread)),o.clearance); val motion=frozen(b.motion,o.motion); val spring=frozen(b.spring,o.spring); val expansion=o.expansion?:b.expansion; val sheetExpansion=o.sheetExpansion?:b.sheetExpansion; val lyricsExpansion=o.lyricsExpansion?:b.lyricsExpansion; val selectionExpansion=o.selectionExpansion?:b.selectionExpansion; val popupMotion=o.popupMotion?:if(settings.popupMotion==PopupMotionStyle.Fluid) fluidPopupMotion() else b.popupMotion; val speed=o.speed?:settings.animationSpeed
    valid(space.values.all { bounded(it.value,0f,Float.MAX_VALUE) } && Space.entries.zipWithNext().all { (a,b)->space.getValue(a)<space.getValue(b) },"space"); valid(Radius.Pill !in radius && radius.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"radius"); valid(control.values.all { it.value.isFinite() && it.value>0 },"control"); valid(clearance.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"clearance")
    fun ease(e: EasingSpec)=bounded(e.x1,0f,1f) && bounded(e.x2,0f,1f) && e.y1.isFinite() && e.y2.isFinite() && e.maxSlope.isFinite()
    fun curve(c: UnitCurveSpec)=when(c) { is EasingSpec -> ease(c); is SpringCurveSpec -> bounded(c.span,1f,40f) && c.maxSlope.isFinite() }
    fun duration(d: Int)=d in 16..2000
    for((key,m) in motion) valid(when(m) { is MotionProfileSpec -> key !in setOf(MotionProfile.Marquee,MotionProfile.Scroll,MotionProfile.SettingsHighlight,MotionProfile.SettingsScroll) && duration(m.durationMs) && ease(m.easing); is MarqueeSpec -> key==MotionProfile.Marquee && m.pauseMs in 0..2000 && duration(m.fadeMs) && duration(m.returnMs) && m.speedDpPerSecond.isFinite() && m.speedDpPerSecond>0 && ease(m.easing); is SettingsScrollSpec -> key==MotionProfile.SettingsScroll && duration(m.normalMs) && duration(m.fasterMs) && ease(m.easing); is SettingsHighlightSpec -> key==MotionProfile.SettingsHighlight && duration(m.revealMs) && m.holdMs in 0..2000 && duration(m.fadeMs) && m.reducedHoldMs in 0..2000 && bounded(m.opacity,0f,1f) && ease(m.easing) && m.easing.x1>0f && m.easing.x2<1f && m.easing.y1==0f && m.easing.y2==1f && easingExtrema(m.easing).let { it.start>=0.0 && it.endInclusive<=1.0 }; is ListMotionSpec -> key==MotionProfile.Scroll && duration(m.listMs) && duration(m.gridMs) && duration(m.fadeMs) && ease(m.easing) && ease(m.fadeEasing) },"motion.$key")
    for((key,s) in spring) valid(s.dampingRatio.isFinite() && s.dampingRatio>0 && bounded(s.stiffness,1f,10000f) && s.speedExponent in setOf(0,2) && springHorizon(s)<=2,"spring.$key")
    for((key,law) in listOf("expansion" to expansion,"sheetExpansion" to sheetExpansion,"lyricsExpansion" to lyricsExpansion,"selectionExpansion" to selectionExpansion)) with(law) { valid(finiteExpansionFinish(this,geometry?.let { it.maxViewportDiagonalPx.toDouble()/it.referenceTravelPx } ?: (sqrt(2.0)*Int.MAX_VALUE/Float.MIN_VALUE.toDouble()),geometry?.maxDisplayIntervalSeconds?.toDouble() ?: (Long.MAX_VALUE/1e9),geometry!=null),"$key.duration exceeds finite device geometry domain; provide measured geometry for a narrower admission domain"); valid(bounded(band,MinimumContactBand,.1f),"$key.band requires at least ulp(1f) contact room"); valid(curve(core) && curveExtrema(core).let { it.start>=0.0 && it.endInclusive<=1.0+band/2 },"$key.contact core extrema require minimum >= 0 and maximum <= 1 + band/2"); valid(curve(core) && curveExtrema(core).let { it.start>=-band.toDouble() && it.endInclusive<=(1f+band).toDouble() } && bounded(referenceDurationMs,Float.MIN_VALUE,2000f) && bounded(floorMs,Float.MIN_VALUE,2000f) && bounded(positionFloor,Float.MIN_VALUE,1f) && velocityKnee.isFinite() && velocityKnee>0 && velocityExponent.isFinite() && velocityExponent>0 && sizeExponent.isFinite() && sizeExponent>=0 && deadBand.isFinite() && deadBand>=0 && bounded(stationaryOpenAt,0f,1f) && bounded(band,0f,.1f),key) }
    valid(speed.isFinite() && speed in MinimumAnimationSpeed..MaximumAnimationSpeed,"speed")
    // The model's own band: admission and the engine's extremum proofs read the same value (#854 review S2).
    for(reason in popupMotionAdmission(popupMotion)) errors+="popupMotion.$reason"
    for((key,m) in material) with(m) { valid(listOf(blur,whiteTint).all { bounded(it,0f,1f) } && listOf(shadowStrength,shadowSpread).all { bounded(it,0f,2f) } && bounded(contrast,0f,.5f) && bounded(liftShadowStrength,.5f,1.5f) && bounded(shapeDamping,2f,40f) && bounded(depth,.6f,2f),"material.$key") }
    return if(errors.isNotEmpty()) SpecResult.Rejected(errors.toList()) else SpecResult.Accepted(UiSpec(space,radius,control,clearance,motion,spring,expansion,material,if(settings.reducedMotion || o.access==MotionAccess.Reduced) MotionAccess.Reduced else o.access?:MotionAccess.Standard,geometry,sheetExpansion,lyricsExpansion,selectionExpansion,popupMotion,speed))
}
/** U11.00 root ingress: a rejected payload falls back to the stock catalog with the same access, speed and measured geometry.
 * [systemReduced] is a respected platform animator scale of 0 (#862), which is Reduced like the app setting. */
fun uiSpec(settings: InterfaceSettings, geometry: ExpansionGeometryDomain, density: Density, systemReduced: Boolean = false): ResolvedUiSpec {
    val access = if (systemReduced) UiSpecData(access = MotionAccess.Reduced) else null
    return ResolvedUiSpec(((uiSpec(settings, access, geometry) as? SpecResult.Accepted)
        ?: uiSpec(InterfaceSettings(reducedMotion = settings.reducedMotion, animationSpeedTenths = settings.animationSpeedTenths,
            respectSystemAnimationScale = settings.respectSystemAnimationScale), access, geometry) as SpecResult.Accepted).spec, density)
}
/** The from-rest overshoot of a spring with damping ratio [zeta]: exp(-pi zeta/sqrt(1-zeta^2)), and 0 when zeta >= 1. */
internal fun springOvershoot(zeta: Double): Double = if(zeta>=1) 0.0 else exp(-PI*zeta/sqrt(1-zeta*zeta))
/** #854 §3.4 admission: finite laws with omega in [1,400] and zeta in [.5,4]; the open overshoot fits the band; the close, gate
 * and cross laws never overshoot from rest; every law settles within 2 s of its worst admitted state at the slowest speed. */
internal fun popupMotionAdmission(m: PopupMotionModel): List<String> {
    val errors=mutableListOf<String>(); val band=m.band
    if(!(band.isFinite() && band>0f && band<=.1f)) return listOf("band")
    if(m.centre!=null && !(m.centreBand.isFinite() && m.centreBand>0f)) return listOf("centreBand")
    for((key,law) in listOf("open" to m.open,"close" to m.close,"crossOpen" to m.crossOpen,"gate" to m.gate)+listOfNotNull(m.centre?.let { "centre" to it })) {
        if(!(law.omega.isFinite() && law.zeta.isFinite() && law.omega in 1f..400f && law.zeta in (if(key=="centre") .3f else .5f)..4f)) { errors+=key; continue }
        if(key!="open" && key!="centre" && law.zeta<1f) errors+="$key.overshoot"
        if((key=="open" || key=="centre") && springOvershoot(law.zeta.toDouble())>(if(key=="centre") m.centreBand else band)) errors+="$key.overshoot"
        if(popupLawHorizon(law,MinimumAnimationSpeed.toDouble(),if(key=="centre") 1.0+m.centreBand else 1.05)>2.0) errors+="$key.horizon"
    }
    if(!(m.snapAspect.isFinite() && m.snapAspect>1f)) errors+="snapAspect"
    if(!(m.preparedRamp.isFinite() && m.preparedRamp in 0f..(.5f) && m.rampBlend.isFinite() && m.rampBlend>0f && m.rampBlend<=.25f)) errors+="ramp"
    if(errors.isEmpty()) for((pair,peak) in m.reversalReach) { val limit=if(pair.startsWith("centre")) m.centreBand else band; if(peak.start< -limit || peak.endInclusive>1+limit) errors+="reversal.$pair" }
    if(PopupKind.entries.any { it !in m.variants } || m.variants[PopupKind.Legacy]!=PopupMotionVariant.Legacy || m.variants.any { (k,v) -> k!=PopupKind.Legacy && v==PopupMotionVariant.Legacy }) errors+="variants"
    return errors
}
/** #854 (rule 12): the one closed-form damped spring. The engine's Damped flies it and the admission below composes it (the spec
 * layer cannot depend on the engine). Overdamped laws use expm1, so near-critical laws keep their precision. */
internal class SpringKernel(private val omega: Double, private val zeta: Double, installedDamping: Double? = null) {
    private val installed = installedDamping != null
    private val a = installedDamping?.times(0.5) ?: zeta * omega
    private val underdamped = if (installed) a < omega else zeta < 1.0
    private val critical = if (installed) a == omega else zeta == 1.0
    private val wd = if (installed) sqrt(abs(omega * omega - a * a)) else omega * sqrt(abs(1 - zeta * zeta))
    internal val slow = if (zeta > 1) omega / (zeta + sqrt(zeta * zeta - 1)) else a
    private val fast = if (zeta > 1) omega * (zeta + sqrt(zeta * zeta - 1)) else omega

    /** The offset from the target ([d] false) or the velocity ([d] true) [t] seconds after offset [offset] and velocity [v]. */
    fun response(offset: Double, v: Double, t: Double, d: Boolean): Double {
        if (underdamped) {
            val b = if (installed) (1 / wd) * (a * offset + v) else (v + a * offset) / wd
            val angle = wd * t
            val cosine = cos(angle)
            val sine = sin(angle)
            val envelope = exp(-a * t)
            val displacement = offset * cosine + b * sine
            if (!d) return envelope * displacement
            if (installed) return envelope * ((wd * (cosine * b)) - (wd * (offset * sine))) - envelope * (displacement * a)
            return envelope * (v * cosine - (a * b + offset * wd) * sine)
        }
        if (critical) {
            val b = v + a * offset
            val displacement = offset + b * t
            val envelope = exp(-a * t)
            if (!d) return envelope * displacement
            if (installed) return envelope * b - envelope * (displacement * a)
            return envelope * (v - a * b * t)
        }
        if (installed) {
            val b = (1 / wd) * (a * offset + v)
            val angle = wd * t
            val sinh = sinh(angle)
            val cosh = cosh(angle)
            val envelope = exp(-a * t)
            val displacement = cosh * offset + sinh * b
            return if (d) envelope * ((wd * (offset * sinh)) + (wd * (cosh * b))) - envelope * (displacement * a)
            else envelope * displacement
        }
        val difference = fast - slow
        val b = -(v + slow * offset) / difference
        val extra = expm1(-difference * t)
        return exp(-slow * t) * if (d) -slow * offset + b * (-slow * extra - difference * exp(-difference * t)) else offset + b * extra
    }

    internal fun advanceBody(body: FluidScalarSpringBody, target: Double, dt: Double): FluidScalarSpringBody {
        if (dt <= 0.0 || (body.value == target && body.velocity == 0.0)) return body
        val offset = body.value - target
        return FluidScalarSpringBody(target + response(offset, body.velocity, dt, false), response(offset, body.velocity, dt, true))
    }

    /** The first velocity zeros, possibly negative (later underdamped extrema shrink by the overshoot factor each half period). */
    fun velocityZeros(offset: Double,v: Double): List<Double> = when { zeta<1 -> { val c=(v+a*offset)/wd; val first=(atan2(v,a*c+offset*wd)%PI+PI)%PI; (0..2).map { (first+it*PI)/wd } }
        zeta==1.0 -> { val d=omega*(v+omega*offset); if(d!=0.0) listOf(v/d) else emptyList() }
        else -> { val b=-(v+slow*offset)/(fast-slow); val ratio=-fast*b/(slow*(offset-b)); if(ratio>0) listOf(ln(ratio)/(fast-slow)) else emptyList() } }
}
/** The analytic state of a damped spring from (x, v) toward q after t seconds. */
internal fun springState(x: Double,v: Double,q: Double,w: Double,z: Double,t: Double): Pair<Double,Double> =
    SpringKernel(w,z).let { q+it.response(x-q,v,t,false) to it.response(x-q,v,t,true) }
/** Exact value extrema of a damped spring from (x, v) toward q over all t >= 0: the start, the target and the first velocity zeros. */
internal fun springExtrema(x: Double,v: Double,q: Double,w: Double,z: Double): ClosedFloatingPointRange<Double> {
    val kernel=SpringKernel(w,z); val values=listOf(x,q)+kernel.velocityZeros(x-q,v).filter { it>0 }.map { q+kernel.response(x-q,v,it,false) }
    return values.min()..values.max()
}
/** #854 review S1: the reachable phase of every single reversal at one speed (a uniform omega scale is a pure time scale, so speed 1
 * stands for all). An open from rest is reversed by a close (or a gate approach) at every switch time on a 1 ms grid over its settle,
 * and a close from the open rest by an open; the result is each pair's exact extremum range. */
internal fun PopupMotionModel.admitsCross(settled: Boolean,target: Boolean) = settled || "cross${if(target) "CloseOpen" else "OpenClose"}" in reversalReach
internal fun popupReversalReach(m: PopupMotionModel): Map<String,ClosedFloatingPointRange<Double>> {
    fun reach(first: SpringLaw,from: Double,to: Double,second: SpringLaw,back: Double): ClosedFloatingPointRange<Double> {
        val w1=first.omega.toDouble(); val z1=first.zeta.toDouble(); val horizon=popupLawHorizon(first,1.0); var lo=min(from,to); var hi=max(from,to)
        var t=0.0; while(t<=horizon) { val (x,v)=springState(from,0.0,to,w1,z1,t); val e=springExtrema(x,v,back,second.omega.toDouble(),second.zeta.toDouble()); lo=min(lo,e.start); hi=max(hi,e.endInclusive); t+=.001 }
        return springExtrema(from,0.0,to,w1,z1).let { min(lo,it.start)..max(hi,it.endInclusive) }
    }
    fun pairs(open: SpringLaw,prefix: String)=mapOf(prefix+"openClose" to reach(open,0.0,1.0,m.close,0.0),prefix+"closeOpen" to reach(m.close,1.0,0.0,open,1.0),
        prefix+"openGate" to reach(open,0.0,1.0,m.gate,m.preparedRamp.toDouble()),prefix+"gateOpen" to reach(m.gate,0.0,m.preparedRamp.toDouble(),open,1.0))
    return pairs(m.open,"")+m.centre?.let { pairs(it,"centre") }.orEmpty()
}
/** The settle horizon of [spring] at [speed] from its worst admitted state, |x0| <= 1.05 and |v0| <= 1.1 omega_eff (A2.2). The parameter is not
 * named law: #884 reserves that name for motion-selecting arguments, and this is the spec's own admission math. */
internal fun popupLawHorizon(spring: SpringLaw, speed: Double, extent: Double=1.05): Double {
    val w=spring.omega*speed; val shape=SpringProfileSpec(spring.zeta,(w*w).toFloat())
    return listOf(-extent,0.0,extent).flatMap { x -> listOf(-1.1*w,0.0,1.1*w).map { v -> if(x==0.0 && v==0.0) 0.0 else springHorizon(shape,x,v,1.0,1.0) } }.max()
}
internal fun springHorizon(s: SpringProfileSpec, x0: Double = 1.05, v0: Double = vMax.toDouble(), range: Double = 1.0, speed: Double = 4.0/3): Double {
    val w=sqrt(s.stiffness.toDouble())*if(s.speedExponent==2) speed else 1.0; val z=s.dampingRatio.toDouble(); val x=x0/range; val v=v0/range; val critical=abs(v+w*x)
    fun envelopes(t: Double): Pair<Double,Double> = when { z<1 -> { val c=hypot(x,(v+z*w*x)/(w*sqrt(1-z*z))); c*exp(-z*w*t) to c*w*exp(-z*w*t) }; z==1.0 -> { (abs(x)+critical*t)*exp(-w*t) to (abs(v)+w*critical*t)*exp(-w*t) }; else -> { val ls=w/(z+sqrt(z*z-1)); val lf=w*(z+sqrt(z*z-1)); val a=(v+lf*x)/(lf-ls); val b=-(v+ls*x)/(lf-ls); (abs(a)+abs(b))*exp(-ls*t) to (abs(a)*ls+abs(b)*lf)*exp(-ls*t) } }
    var lo=if(z==1.0 && critical>0) maxOf(0.0,1/w-abs(x)/critical,1/w-abs(v)/(w*critical)) else 0.0; var hi=maxOf(2.0,lo); while(envelopes(hi).let { it.first>SettleThreshold || it.second>SettleVelocityThreshold }) { hi*=2; if(!hi.isFinite()) return hi }; repeat(64) { val mid=(lo+hi)/2; if(envelopes(mid).let { it.first>SettleThreshold || it.second>SettleVelocityThreshold }) lo=mid else hi=mid }; return hi
}
@Immutable class ResolvedUiSpec internal constructor(val spec: UiSpec, val density: Density) {
    init { require(density.density.isFinite() && density.density>0 && density.fontScale.isFinite() && density.fontScale>0) }
    private val spaces=spec.space.mapValues { it.value.value*density.density }; private val controls=spec.control.mapValues { it.value.value*density.density }; private val clearances=spec.clearance.mapValues { it.value.value*density.density }; private val radii=spec.radius.mapValues { it.value.value*density.density }
    init { require(listOf(spaces,controls,clearances,radii).all { map -> map.values.all { it.isFinite() } }) }
    fun px(space: Space)=spaces.getValue(space)
    fun px(control: ControlSize)=controls.getValue(control)
    fun px(clearance: Clearance)=clearances.getValue(clearance)
    fun radiusPx(radius: Radius,heightPx: Float): Float { require(heightPx.isFinite() && heightPx>=0); return if(radius==Radius.Pill) heightPx/2 else radii.getValue(radius) }
}
val LocalUiSpec = staticCompositionLocalOf<ResolvedUiSpec> { error("Accepted UiSpec required") }
