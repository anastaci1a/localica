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
enum class Clearance { FloatingGap, LiftMax, LiftClearance, BottomChrome }
enum class MotionProfile { Control, ControlOff, Selection, Hover, Reveal, ResolutionBlend, Floating, Heading, ArtworkLayout, CropRetention, Marquee, Scroll, SettingsHighlight, SettingsScroll, LyricEmphasis }
enum class SpringProfile { Page }
enum class MotionAccess { Standard, Reduced }
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
    val band: Float = StockPopupExpansionBand, val centre: PopupSecondPhase? = null,
    val style: PopupMotionStyle = PopupMotionStyle.EdgeSpring) {
    fun variant(kind: PopupKind): PopupMotionVariant = variants[kind] ?: PopupMotionVariant.Base
    internal val reversalReach by lazy { popupReversalReach(this) }
    /** One channel's law: the main axis by [direction]; only a [cross] open uses the snap law; close and gate run the main law on every edge. */
    @Suppress("UNUSED_PARAMETER") fun law(kind: PopupKind, direction: PopupDirection, cross: Boolean): SpringLaw =
        if(cross && direction==PopupDirection.Open) centre?.spring ?: crossOpen else when(direction) { PopupDirection.Open -> open; PopupDirection.Close -> close; PopupDirection.Gate -> gate }
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
internal fun fluidPopupMotion() = StockPopupMotion.copy(style = PopupMotionStyle.Fluid)
internal fun fluidPreviewMorphs(compact: Boolean, overlapsPreview: () -> Boolean) =
    compact && overlapsPreview()
internal fun fluidIntermediateY(sourceY: Double, targetY: Double, ratio: Double, cap: Double) =
    sourceY + (ratio * (targetY - sourceY)).coerceIn(-cap, cap)
internal fun fluidStoredDelay(storedDelay: Double, speed: Double, amplify: Boolean) =
    storedDelay * speed * (if (amplify) 10.0 else 1.0)
internal fun fluidThroughGrows(fromWidth: Double, fromHeight: Double, toWidth: Double, toHeight: Double) =
    fromWidth * fromHeight < toWidth * toHeight
private fun fluidThroughTailDelay(growing: Boolean) = if (growing) 0.12 else 0.1
private fun fluidSpringOmega(response: Double) = 2 * PI / response

internal class FluidPopupSpringSettings(
    val dampingRatio: Double, val response: Double, val retargetImpulse: Double = 0.0, val trackingResponse: Double = 0.15,
) {
    val mass get() = 1.0
    val omega = fluidSpringOmega(response)
    val stiffness = omega * omega
    val damping = 2 * dampingRatio * omega
    val installedDamping = (sqrt(stiffness) + sqrt(stiffness)) * dampingRatio
    val velocityScalingFactor get() = installedDamping / stiffness
    fun advanceUnsmoothed(entry: FluidScalarSpringEntry, dt: Double) = entry.advanceUnsmoothed(stiffness, installedDamping, dt)
    /** The portal's two equal scale coordinates follow the typed transform arithmetic. */
    fun advancePlanarZoom(entry: FluidScalarSpringEntry, dt: Double): FluidScalarSpringEntry {
        if (!(dt > 0.0)) return entry
        val state = entry.body.value to entry.body.velocity
        val (scale, velocity) = SpringKernel.fromInstalled(stiffness, installedDamping)
            .advanceTransform(state, entry.target, dt, FluidPlanarZoomValues)
        return entry.copy(body = FluidScalarSpringBody(scale, velocity),
            intermediate = FluidScalarSpringBody(entry.target, 0.0))
    }
    fun velocityWithImpulse(velocity: Double, targetDelta: Double) =
        if (retargetImpulse > 0.0 && dampingRatio > 0.0 && response > 0.0)
            velocity + targetDelta * (retargetImpulse * stiffness)
        else velocity

    fun scaledResponses(multiplier: Double) =
        FluidPopupSpringSettings(dampingRatio, multiplier * response, retargetImpulse, multiplier * trackingResponse)

    fun nontrackingModifiers(
        enabled: Boolean, priorInteractive: Boolean,
        dampingSmoothing: Double, responseSmoothing: Double, targetSmoothingRatio: Double, projectionDeceleration: Double,
    ): FluidSpringModifiers {
        val followsInteraction = enabled && priorInteractive
        return FluidSpringModifiers(
            if (enabled) dampingSmoothing else 0.0,
            if (enabled) responseSmoothing else 0.0,
            if (followsInteraction) targetSmoothingRatio * trackingResponse else 0.0,
            if (followsInteraction) projectionDeceleration else 0.0,
        )
    }
}

internal data class FluidSpringModifiers(
    val dampingSmoothing: Double, val responseSmoothing: Double,
    val targetSmoothing: Double, val projectionDeceleration: Double,
)

/** The constructed root settings: the through main springs and, in [FluidContent], scales, blur springs and speed. */
internal val FluidMainEject = FluidPopupSpringSettings(dampingRatio = 0.75, response = 0.5)
internal val FluidMainAbsorb = FluidPopupSpringSettings(dampingRatio = 0.8, response = 0.7)
private val FluidSourceOutlineSpring = FluidPopupSpringSettings(0.8, 0.3, retargetImpulse = 0.016, trackingResponse = 0.3)
private val FluidReducedSourceGeometrySpring = FluidPopupSpringSettings(1.0, 0.15, retargetImpulse = 0.0001, trackingResponse = 0.3)

@Immutable data class PopupSecondPhase(val spring: SpringLaw, val band: Float, val edgeShares: (DoubleArray) -> DoubleArray)

internal interface PopupOutlinePreview<Bounds> {
    fun prepare(home: Bounds, envelope: Bounds, density: Float, opening: Boolean): PopupOutline<Bounds>
}
internal interface PopupOutline<Bounds> {
    val growing: Boolean
    val apertureSlot: Int
    val hostScaleY: Double
    val contentInputs: FluidOutlineContentInputs
    fun outline(action: FluidTimelineAction): List<PopupOutlinePose<Bounds>>?
    fun kick(action: FluidTimelineAction): Pair<Int,Float>?
}

internal data class FluidOutlineItemInputs(
    val isGlass: Boolean,
    val matchedViewIdentifier: Any? = null,
    val contentScaleAxis: Int = 0,
    val prefersUnclippedContents: Boolean = false,
)

internal data class FluidOutlineContentInputs(val home: FluidOutlineItemInputs, val popup: FluidOutlineItemInputs)

internal data class PopupOutlinePose<Bounds>(val bounds: Bounds, val radius: Float, val scale: Float, val preservesCenter: Boolean = false)

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
internal val FluidContent = FluidContentSettings(
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
    /** The scalar entry handed from the property owner to the ordinary zero-delay spring. */
    fun immediateEntry(
        requestedStart: Double, target: Double, retained: FluidScalarSpringEntry?, previousTarget: Double?,
        spring: FluidPopupSpringSettings?, pendingVelocity: Double?,
    ): FluidScalarSpringEntry {
        val oldBody = retained?.body
        val value = oldBody?.value ?: requestedStart
        val velocity = when {
            pendingVelocity != null -> pendingVelocity
            startingVelocity != null -> (target - requestedStart) * startingVelocity
            oldBody == null -> 0.0
            previousTarget != null && spring != null -> spring.velocityWithImpulse(oldBody.velocity, target - previousTarget)
            else -> oldBody.velocity
        }
        val intermediate = retained?.intermediate ?: FluidScalarSpringBody(requestedStart, 0.0)
        return FluidScalarSpringEntry(FluidScalarSpringBody(value, velocity), target, intermediate)
    }
}

internal data class FluidScalarSpringEntry(
    val body: FluidScalarSpringBody, val target: Double, val intermediate: FluidScalarSpringBody,
) {
    fun advanceUnsmoothed(stiffness: Double, damping: Double, dt: Double): FluidScalarSpringEntry {
        if (!(dt > 0.0)) return this
        val nextIntermediate = FluidScalarSpringBody(target, 0.0)
        val nextBody = SpringKernel.fromInstalled(stiffness, damping).advanceBody(body, nextIntermediate.value, dt)
        return copy(body = nextBody, intermediate = nextIntermediate)
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

internal enum class ScalarSpringUse { Property, Parameter, Intermediate }

internal data class FluidSmoothingParameter(
    val body: FluidScalarSpringBody, val target: Double, val response: Double, val mode: Int,
) {
    val disabled get() = response == 0.0
    private val spring = FluidPopupSpringSettings(1.0, response)

    fun advance(dt: Double, use: ScalarSpringUse) =
        if (disabled) body else SpringKernel.fromInstalled(spring.stiffness, spring.installedDamping)
            .advanceBody(body, target, dt, use, mode)
}

internal data class FluidRadiusSmoothingDescription(
    val flags: Long, val damping: Double, val response: Double,
    val dampingStart: Double, val responseStart: Double,
    val dampingSmoothing: Double, val responseSmoothing: Double, val targetSmoothing: Double,
) {
    fun install(previous: FluidRadiusSpring, reset: Boolean): FluidRadiusSpring? {
        if (flags >= 0L) return null
        fun parameter(old: FluidSmoothingParameter, bit: Long, start: Double, target: Double, smoothing: Double): FluidSmoothingParameter {
            val specified = (flags and bit) != 0L
            val smoothingResponse = if (reset && !specified) 0.0 else smoothing
            val body = when {
                smoothingResponse == 0.0 -> FluidScalarSpringBody(target, 0.0)
                specified -> old.body.copy(value = start)
                else -> old.body
            }
            return FluidSmoothingParameter(body, target, smoothingResponse, old.mode)
        }
        val installed = FluidPopupSpringSettings(damping, response)
        return previous.copy(
            damping = parameter(previous.damping, 1L, dampingStart, damping, dampingSmoothing),
            response = parameter(previous.response, 256L, responseStart, response, responseSmoothing),
            targetSmoothing = targetSmoothing,
            stiffness = installed.stiffness,
            physicalDamping = installed.installedDamping,
            velocityScalingFactor = installed.velocityScalingFactor,
        )
    }
}

/** Supplied finite radius state after pending actions have been admitted. */
internal data class FluidRadiusSpring(
    val entry: FluidScalarSpringEntry, val damping: FluidSmoothingParameter, val response: FluidSmoothingParameter,
    val targetSmoothing: Double, val stiffness: Double, val physicalDamping: Double, val velocityScalingFactor: Double,
) {
    fun advance(dt: Double): FluidRadiusSpring {
        if (dt <= 0.0) return this
        val nextDamping = damping.advance(dt, ScalarSpringUse.Parameter)
        val nextResponse = response.advance(dt, ScalarSpringUse.Parameter)
        val smoothed = !damping.disabled || !response.disabled
        val (k, c) = if (smoothed) {
            val omega = fluidSpringOmega(nextResponse.value)
            val stiffness = omega * omega
            val w = sqrt(stiffness)
            stiffness to (nextDamping.value * (w + w))
        } else stiffness to physicalDamping
        val intermediate = if (targetSmoothing == 0.0) FluidScalarSpringBody(entry.target, 0.0) else {
            FluidSmoothingParameter(entry.intermediate, entry.target, targetSmoothing, 0).advance(dt, ScalarSpringUse.Intermediate)
        }
        val body = SpringKernel.fromInstalled(k, c).advanceBody(entry.body, intermediate.value, dt)
        return copy(
            entry = entry.copy(body = body, intermediate = intermediate),
            damping = damping.copy(body = nextDamping), response = response.copy(body = nextResponse),
            stiffness = k, physicalDamping = c,
        )
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
    val content: FluidContentBlurRequest? = null,
) {
    fun propertySpring(effectiveSpeed: Double) = timing.spring?.scaledResponses(effectiveSpeed)
}

/** One supplied behavior drives position X/Y, width, height, content morph and transform. */
internal data class FluidGeometrySpringBinding(val sharedSpring: FluidPopupSpringSettings?) {
    val tracksPresentationLayer get() = true
}

internal fun FluidTimelineStep.geometrySpringBinding(effectiveSpeed: Double) =
    FluidGeometrySpringBinding(propertySpring(effectiveSpeed))

internal fun fluidOutlineSpring(resolved: FluidPopupSpringSettings?) = resolved ?: FluidSourceOutlineSpring
internal fun fluidOutlineGeometrySpring(spring: FluidPopupSpringSettings, access: MotionAccess) =
    if (access == MotionAccess.Reduced && spring === FluidSourceOutlineSpring) FluidReducedSourceGeometrySpring else spring

internal fun fluidThroughQueue(
    growing: Boolean, mainEject: FluidPopupSpringSettings, content: FluidContentSettings, largerHeight: Double,
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
        step(FluidTimelineAction.Kick, spring = FluidKickSpec.ejecteeSpring)
        step(FluidTimelineAction.Unkick, 0.1, mainEject)
    } else {
        step(FluidTimelineAction.FullyExpanded, spring = FluidMainAbsorb)
        step(FluidTimelineAction.AddSdfElements)
        step(FluidTimelineAction.Source, spring = FluidMainAbsorb)
        step(FluidTimelineAction.KickEjectee, spring = FluidKickSpec.ejecteeSpring)
        step(FluidTimelineAction.UnkickEjectee, 0.1, FluidMainAbsorb)
        step(FluidTimelineAction.KickSource, spring = FluidMainAbsorb)
        step(FluidTimelineAction.UnkickSource, Double.fromBits(0x3fc3333333333334L), FluidMainAbsorb)
    }
    step(FluidTimelineAction.SmoothOn, spring = FluidPopupSpringSettings(1.0, 0.1))
    step(FluidTimelineAction.SmoothOff, if (growing) 0.3 else 0.1, FluidPopupSpringSettings(1.0, 0.6))
    content.blurSequence(growing)?.let { blur ->
        val (on, change, off) = lensRequests()
        add(FluidTimelineStep(FluidTimelineAction.LensOn, on.timing, on))
        add(FluidTimelineStep(FluidTimelineAction.ChangeLensDimension, change.timing, change))
        add(FluidTimelineStep(FluidTimelineAction.LensOff, off.timing, off))
        add(FluidTimelineStep(FluidTimelineAction.BlurOut, blur.blurOut,
            content = fluidContentBlurOut(largerHeight, { content.maxScale }, { content.minScale })))
        add(FluidTimelineStep(FluidTimelineAction.BlurIn, blur.blurIn, content = FluidContentBlurIn))
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

/** One completion group for the admitted morph. The existing owner supplies its held requests. */
internal class FluidMorphCompletion {
    private var count = 0L
    private var blocks = mutableListOf<() -> Unit>()

    fun add(incrementing: Boolean = true, block: () -> Unit) {
        blocks.add(block)
        if (incrementing) increment()
    }

    fun increment() {
        count = Math.addExact(count, 1L)
        if (count == 0L) fire()
    }

    fun complete() {
        val remaining = count - 1L
        if (remaining < 0L) return
        count = remaining
        if (count == 0L) fire()
    }

    fun completeImmediately() {
        val active = count != 0L
        count = 0L
        if (active) fire()
    }

    private fun fire() {
        val pending = blocks
        blocks = mutableListOf()
        pending.forEach { it() }
    }

    fun start(block: () -> Unit, drain: () -> Unit, propagateHold: () -> Unit) {
        add(block = block)
        drain()
        propagateHold()
        complete()
    }

    fun acquireInteraction(previousAssertions: Int) {
        if (previousAssertions == 0) increment()
    }

    fun releaseInteraction(remainingAssertions: Int, resetOrigin: () -> Unit, drain: () -> Unit, requestTimer: () -> Unit) {
        if (remainingAssertions != 0) return
        resetOrigin()
        drain()
        requestTimer()
        complete()
    }

    fun finishWithoutTimeline(applyRequests: () -> Unit, afterGroup: (() -> Unit)?, completion: (() -> Unit)?) {
        applyRequests()
        completeImmediately()
        afterGroup?.invoke()
        completion?.invoke()
    }
}

/** The host forwards the request release after its state has been installed. */
internal fun releaseFluidMorphRequest(stateInstalled: Boolean, release: (() -> Unit)?) {
    if (stateInstalled) release?.invoke()
}

internal fun completeFluidTimeline(
    callback: (() -> Unit)?, prepareCleanup: () -> Unit, removeViews: () -> Unit,
    clearOwnerState: () -> Unit, setState: (Int) -> Unit, resetTimeline: () -> Unit, removeTrackedSdf: () -> Unit,
) {
    callback?.invoke()
    prepareCleanup()
    removeViews()
    clearOwnerState()
    setState(2)
    resetTimeline()
    removeTrackedSdf()
}

internal object FluidKickSpec {
    const val epsilonSquared = 0.001 * 0.001
    const val growingSlot = 0
    const val growingAmount = -80.0
    const val sourceAmount = 40.0
    val ejecteeSpring = FluidPopupSpringSettings(1.0, 0.2)
    const val unkickTarget = 0.0
    fun translationY(amount: Double, scale: Double) = scale * amount
}

internal data class FluidContentBlurSequence(
    val blurOut: FluidPopupStep<FluidPopupSpringSettings>, val blurIn: FluidPopupStep<FluidPopupSpringSettings>,
)
internal data class FluidContentBlurRequest(val portalScale: Double, val gaussianRadius: Double) {
    fun zoomMatrix() = fluidZoomDiagonal(portalScale, portalScale, 1.0)
    fun typedZoom() = FluidZoomTransform.fromPlanarScale(portalScale)
}

private fun fluidZoomDiagonal(x: Double, y: Double, z: Double) = DoubleArray(16).apply {
    this[0] = x
    this[5] = y
    this[10] = z
    this[15] = 1.0
}

internal fun fluidContentBlurOut(height: Double, maxScale: () -> Double, minScale: () -> Double): FluidContentBlurRequest {
    val fraction = (height + (-215.0)) / 305.0
    val t = if (fraction.isNaN()) 1.0 else fraction.coerceIn(0.0, 1.0)
    return FluidContentBlurRequest((1.0 - t) * maxScale() + t * minScale(), FluidSharedBlurSpec.blurOutRadius)
}
internal val FluidContentBlurIn = FluidContentBlurRequest(1.0, 0.0)

internal object FluidSharedBlurSpec {
    const val additive = false
    const val blurOutRadius = 4.0

    fun zoomOrigin(model: DoubleArray?, resolvedDefault: () -> DoubleArray?) = model ?: resolvedDefault()
    fun skipsZoomMutation(current: DoubleArray, requested: DoubleArray) =
        current.size == 16 && requested.size == 16 && current.indices.all { current[it] == requested[it] }
    fun radiusOrigin(model: Double?) = model ?: 0.0

    fun publishRadius(sample: FluidScalarSpringBody, write: (Double, Double, Boolean) -> Unit) =
        write(if (sample.value <= 0.0) 0.0 else sample.value, sample.velocity, additive)
}

/** Decomposed rotation, scale, translation, shear and perspective, in that order. */
internal class FluidZoomTransform(private val components: DoubleArray, val rotationDirection: Int) {
    companion object {
        fun fromPlanarScale(scale: Double): FluidZoomTransform? {
            val squared = scale * scale
            if (!(scale > 0.0 && squared > 0.0 && squared.isFinite())) return null
            val extent = sqrt(squared)
            val normalized = scale * (1.0 / extent)
            val typed = DoubleArray(16)
            typed[0] = atan2(0.0, 1.0)
            typed[1] = asin(-0.0)
            typed[2] = atan2(0.0, normalized)
            typed[3] = extent
            typed[4] = extent
            typed[5] = 1.0
            typed[15] = 1.0
            return FluidZoomTransform(typed, 0)
        }
    }

    init {
        require(components.size == 16)
    }

    operator fun get(index: Int) = components[index]

    fun diagonalMatrix(): DoubleArray? {
        if ((0..2).any { this[it] != 0.0 } || (6..11).any { this[it] != 0.0 }) return null
        if ((12..14).any { this[it].toRawBits() != 0L } || this[15] != 1.0) return null
        if ((3..5).any { !(this[it] > 0.0 && this[it].isFinite()) }) return null
        return fluidZoomDiagonal(this[3], this[4], this[5])
    }
}

internal class FluidZoomValues(
    private val sameValue: (FluidZoomTransform, FluidZoomTransform) -> Boolean,
    private val displacementMode: Int = 0,
) : SpringTransformValues<FluidZoomTransform> {
    override val zeroVelocity = FluidZoomTransform(DoubleArray(16), 0)
    override fun equal(left: FluidZoomTransform, right: FluidZoomTransform) = sameValue(left, right)

    override fun displacement(from: FluidZoomTransform, to: FluidZoomTransform): FluidZoomTransform {
        val direction = to.rotationDirection
        val components = DoubleArray(16) { index ->
            val difference = from[index] - to[index]
            if (index < 3) {
                fluidWrappedAngle(difference, (direction and 1) != 0, ((4 ushr (direction and 7)) and 1) != 0)
            } else fluidModeDisplacement(difference, displacementMode)
        }
        return FluidZoomTransform(components, direction)
    }

    override fun scaledCopy(value: FluidZoomTransform, factor: Double) =
        FluidZoomTransform(DoubleArray(16) { value[it] * factor }, value.rotationDirection)

    override fun add(left: FluidZoomTransform, right: FluidZoomTransform): FluidZoomTransform {
        val direction = when {
            left.rotationDirection == 2 || right.rotationDirection == 2 -> 2
            left.rotationDirection == 1 || right.rotationDirection == 1 -> 1
            left.rotationDirection == 0 -> 0
            else -> error("Invalid left rotation direction")
        }
        return FluidZoomTransform(DoubleArray(16) { left[it] + right[it] }, direction)
    }
}

private object FluidPlanarZoomValues : SpringTransformValues<Double> {
    override val zeroVelocity = 0.0
    override fun equal(left: Double, right: Double) = left == right
    override fun displacement(from: Double, to: Double) = from - to
    override fun scaledCopy(value: Double, factor: Double) = value * factor
    override fun add(left: Double, right: Double) = left + right
}

private fun fluidModeDisplacement(difference: Double, mode: Int) =
    if (mode in 1..3) fluidWrappedAngle(difference, mode == 2, mode == 3) else difference

private fun fluidWrappedAngle(difference: Double, negative: Boolean, positive: Boolean): Double {
    val period = Double.fromBits(0x401921fb54442d18L)
    val wrapped = atan2(sin(difference), cos(difference))
    val directed = if (negative && wrapped > 0.0) wrapped + -period else wrapped
    return if (positive && directed < 0.0) directed + period else directed
}

internal enum class FluidPopupChannel(val key: String, val value: Value) {
    DestinationOpacity("opacity", Value.PresentationScalar), ContentZoom("sublayerTransform", Value.Transform),
    GaussianRadius("filters.gaussianBlur.inputRadius", Value.ScaledScalar), LensHeight("effect.height", Value.CoarseScalar),
    LensAmount("filters.displacementMap.inputAmount", Value.CoarseScalar),
    CornerRadii("cornerRadii", Value.CornerRadii),
    KickAmount("MagicMorphAnimation.kickAmount", Value.StandardScalar), KickScale("MagicMorphAnimation.kickScale", Value.StandardScalar);

    enum class Value { PresentationScalar, Transform, ScaledScalar, CoarseScalar, CornerRadii, StandardScalar }
}

internal fun fluidLensSuppressesAction(old: Double, new: Double, latest: Double?, bypass: Boolean) =
    !bypass && abs(new - old) <= FluidLensingSpec.epsilon && (latest == null || abs(new - latest) <= FluidLensingSpec.epsilon)

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

/** Time values supplied by the existing property owner after it clears the priming flag. */
internal data class FluidPropertyFrameTime(val previousTime: Double, val delta: Double, val refreshInterval: Double) {
    fun primed(now: Double, driverInterval: () -> Double): FluidPropertyFrameTime {
        val interval = if (refreshInterval != 0.0) refreshInterval else {
            val driver = driverInterval()
            if (driver > 0.0) driver * Double.fromBits(0x3ff004189374bc6aL) else Double.fromBits(0x3f91156f8c384071L)
        }
        return FluidPropertyFrameTime(now + interval * -0.4, 0.0, interval)
    }

    fun advance(timestamp: Double): FluidPropertyFrameTime {
        val elapsed = timestamp - previousTime
        return if (timestamp > 0.0 && elapsed > 0.0) copy(previousTime = timestamp, delta = elapsed) else copy(delta = 0.0)
    }

    fun entryDelta(accumulated: Double, drag: Double) = (accumulated + delta) / drag
}

internal fun runFluidPropertyFrame(
    targetTimestamp: Double, preparationFlags: Int, solve: () -> Int,
    consumeSamples: () -> Unit, postTick: MutableList<() -> Unit>, normalizedChange: () -> Double,
    flush: (Double, Boolean, Boolean) -> Unit,
) {
    val solverFlags = solve()
    consumeSamples()
    while (postTick.isNotEmpty()) {
        val callbacks = postTick.toList()
        postTick.clear()
        callbacks.forEach { it() }
    }
    flush(targetTimestamp, ((preparationFlags or solverFlags) and 1) != 0, normalizedChange() >= 1.0)
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

internal data class PopupPortalBounds(val x: Double, val y: Double, val width: Double, val height: Double) {
    val midX get() = minX + kotlin.math.abs(width) * 0.5
    val midY get() = minY + kotlin.math.abs(height) * 0.5
    private val minX get() = if (width < 0.0) x + width else x
    private val minY get() = if (height < 0.0) y + height else y

    fun standardized() = PopupPortalBounds(minX, minY, kotlin.math.abs(width), kotlin.math.abs(height))

    fun centeredAt(centerX: Double, centerY: Double) =
        copy(x = centerX - width * 0.5, y = centerY - height * 0.5)
}

internal data class PopupPortalInsets(val top: Double, val leading: Double, val bottom: Double, val trailing: Double) {
    val horizontal get() = leading + trailing
    val vertical get() = top + bottom

    fun signedCrop(bounds: PopupPortalBounds?): PopupPortalBounds? = bounds?.standardized()?.let {
        PopupPortalBounds(it.x + leading, it.y + top, it.width - horizontal, it.height - vertical)
    }
}

internal data class FluidDestinationPlacement(val layer: PopupPortalBounds, val scaleX: Double, val scaleY: Double)

internal fun fluidDestinationPlacement(
    contentWidth: Double, contentHeight: Double, apertureInsets: PopupPortalInsets, item: PopupPortalBounds,
    destinationInsets: PopupPortalInsets, axis: Int, leftToRight: Boolean = true,
): FluidDestinationPlacement {
    val destination = destinationInsets.signedCrop(item)?.takeIf { it.width >= 0.0 && it.height >= 0.0 }
    val width = kotlin.math.abs(contentWidth)
    val height = kotlin.math.abs(contentHeight)
    val apertureWidth = width + apertureInsets.horizontal
    val apertureHeight = height + apertureInsets.vertical
    val rx = destination?.width?.takeUnless { it == 0.0 }?.let { width / it } ?: 1.0
    val ry = destination?.height?.takeUnless { it == 0.0 }?.let { height / it } ?: 1.0
    val middle = apertureWidth * 0.5
    return when (axis) {
        1 -> {
            val span = item.width * rx
            val left = if (leftToRight) apertureInsets.leading else apertureInsets.trailing
            val destinationLeft = if (leftToRight) destinationInsets.leading else destinationInsets.trailing
            val x = left - destinationLeft * rx
            val fraction = 1.0 - (middle - x) / span
            FluidDestinationPlacement(
                PopupPortalBounds(middle + span * (fraction - 0.5), apertureInsets.top - destinationInsets.top * rx,
                    item.width, apertureHeight / rx), rx, rx,
            )
        }
        2 -> FluidDestinationPlacement(PopupPortalBounds(middle, 0.0, apertureWidth / ry, item.height), ry, ry)
        else -> FluidDestinationPlacement(PopupPortalBounds(middle, 0.0, item.width, item.height), rx, ry)
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

internal data class FluidUniformEncoding(val contextOffset: Int, val descriptor: Int) {
    val channels get() = descriptor and 7
    val elementCount get() = channels * ((descriptor ushr 3) and 15)
    val elementBytes get() = if ((descriptor and 0x80) != 0) 2 else 4
    val alignment get() = channels * elementBytes
}

internal object FluidGaussianSpec {
    data class Capabilities(
        val familySupportsCompute: Boolean, val computeDisableByte: Int,
        val tileShaders: Boolean, val simdShuffleAndFill: Boolean, val tilePreferenceByte: Int,
    ) {
        val promotesCompute get() = familySupportsCompute && (computeDisableByte and 1) == 0
        val jointTiles get() = tileShaders && (tilePreferenceByte xor 1) != 0

        fun admitsSimd(edgeMode: Int, hasShape: Boolean, reinterpretedSource: Boolean) =
            jointTiles && simdShuffleAndFill && edgeMode != 1 && !hasShape && !reinterpretedSource
    }

    enum class SimdWidth(val countThreshold: Int, val radius: Int, val tileId: Int, val computeId: Int) {
        Taps27(12, 13, 4, 0x24), Taps23(10, 11, 5, 0x23), Taps19(8, 9, 6, 0x22),
        Taps15(6, 7, 7, 0x21), Taps11(4, 5, 8, 0x20), Taps7(3, 3, 9, 0x1f), Taps5(0, 2, 10, 0x1e);

        val taps get() = 2 * radius + 1
        val tileName get() = "tile_simd_blur_$taps"
        val computeName get() = "compute_simd_blur_$taps"
    }

    data class SimdTapUpload(val width: SimdWidth, val halfWeights: ShortArray) {
        val weightIndex get() = 1
        val weightBytes get() = 2 * halfWeights.size
    }

    /** The admitted axis supplies the generated weights from the outermost tap to the centre. */
    fun simdTapUpload(weights: FloatArray, narrowHalf: (Float) -> Short): SimdTapUpload {
        val width = SimdWidth.entries.firstOrNull { weights.size > it.countThreshold } ?: SimdWidth.Taps5
        val centre = width.radius
        val first = centre + 1 - weights.size
        require(first >= 0)
        val packed = ShortArray(width.taps)
        weights.forEachIndexed { index, weight -> packed[first + index] = narrowHalf(weight) }
        for (index in 0 until centre) packed[centre + 1 + index] = packed[centre - 1 - index]
        return SimdTapUpload(width, packed)
    }

    enum class Decimation(
        val factor: Int, val textureFunction: Int, val computeFunction: Int, val threadDivisor: UInt, val tileWeightBits: Int,
    ) {
        By2(2, 1, 0x25, 2u, 0x3400), By4(4, 0x1b, 0x0d, 2u, 0x2c00), By8(8, 0x1d, 0x0f, 4u, 0x2400);
    }

    enum class Axis { X, Y }

    data class MeltSamples(val offsets: FloatArray, val weights: FloatArray)

    private val meltTwofold = MeltSamples(
        floatWords(0x3ff9c511, 0x4079d40a, 0x40bb7131),
        floatWords(0x3e81c0e9, 0x3e567bc2, 0x3df22b33, 0x3d3ab6f0),
    )
    private val meltFourfold = MeltSamples(
        floatWords(0x3ffc6032, 0x407c6329, 0x40bd4e0a, 0x40fc6ecd, 0x411dca90, 0x413d6114),
        floatWords(0x3e418790, 0x3e2d1035, 0x3df7842a, 0x3d8d897c, 0x3d016ea8, 0x3c3d4627, 0x3b5d489b, 0),
    )

    private fun floatWords(vararg words: Int) = FloatArray(words.size) { Float.fromBits(words[it]) }

    fun meltDownsample(size: Decimation) = when (size) {
        Decimation.By2 -> meltTwofold
        Decimation.By4 -> meltFourfold
        Decimation.By8 -> error("Box-only decimation")
    }

    data class MeltNarrow(val width: NarrowWidth, val samples: MeltSamples)

    /** SDF narrow always supplies capacity 27 and uses the ordinary pass for both axes. */
    fun meltNarrow(variance: Float, fusedMultiplyAdd: (Double, Double, Double) -> Double): MeltNarrow {
        require(variance > 0f && variance.isFinite())
        val inverse = 1.0 / variance.toDouble()
        val density = DoubleArray(14)
        var sum = 0.0
        for (tap in 13 downTo 1) {
            density[tap] = exp((tap * tap * -0.5) * inverse)
            sum += density[tap]
        }
        val scale = 1.0 / fusedMultiplyAdd(sum, 2.0, 1.0)
        for (tap in 1..13) density[tap] *= scale
        val offsets = FloatArray(7)
        val weights = DoubleArray(8)
        offsets[0] = (density[1] / fusedMultiplyAdd(scale, 0.5, density[1])).toFloat()
        for (pair in 1..6) {
            val even = 2 * pair
            val weight = density[even + 1] + density[even]
            if (weight >= 0.002) {
                weights[pair] = weight
                offsets[pair] = (even + density[even + 1] / weight).toFloat()
            }
        }
        weights[0] = 0.5 - (((weights[1] + weights[2]) + weights[5]) + ((weights[3] + weights[4]) + weights[6]))
        val uniformWeights = FloatArray(weights.size) { weights[it].toFloat() }
        var widthIndex = 0
        while (widthIndex < 5 && uniformWeights[widthIndex + 2] != 0f) widthIndex++
        return MeltNarrow(NarrowWidth.entries[widthIndex], MeltSamples(offsets, uniformWeights))
    }

    data class MeltResidual(val varianceX: Double, val varianceY: Double, val singlePass: Boolean) {
        val narrowPassesX get() = narrowPasses(varianceX)
        val narrowPassesY get() = narrowPasses(varianceY)

        private fun narrowPasses(variance: Double) =
            if (singlePass) 0 else ceil(variance / (6.1 * 6.1)).toInt().coerceAtLeast(0)
    }

    fun admitsMeltSinglePass(varianceX: Double, varianceY: Double, supported: Boolean) =
        supported && maxOf(varianceX, varianceY) <= 0.6

    /** SDF mode 1 and flags 0x184. The caller supplies the admitted layer scales and backend capabilities. */
    fun meltPasses(
        radius: Double, drawScale: Double, layerScaleX: Float, layerScaleY: Float,
        fourfoldSupported: Boolean, singlePassSupported: Boolean,
        downsample: (Decimation, Axis) -> Unit,
    ): MeltResidual? {
        if (isIdentity(radius)) return null
        val scaledRadius = (radius * drawScale).toFloat()
        fun variance(scale: Float): Double {
            val sigma = (scaledRadius * scale).toDouble()
            return minOf(sigma * sigma, 1e8)
        }
        var varianceX = variance(layerScaleX)
        var varianceY = variance(layerScaleY)
        val big = maxOf(varianceX, varianceY) > 100.0
        if (fourfoldSupported) {
            val minimum = if (big) 7.5 else 3.5
            while (true) {
                val nextX = varianceX / 16.0 - 1.1025
                val nextY = varianceY / 16.0 - 1.1025
                val admitsX = nextX >= minimum
                val admitsY = nextY >= minimum
                if (!admitsX && !admitsY) break
                if (admitsX) {
                    downsample(Decimation.By4, Axis.X)
                    varianceX = nextX
                }
                if (admitsY) {
                    downsample(Decimation.By4, Axis.Y)
                    varianceY = nextY
                }
            }
        }
        val maximum = if (big) 56.25 else 12.25
        while (varianceX > maximum || varianceY > maximum) {
            if (varianceX > maximum) {
                downsample(Decimation.By2, Axis.X)
                varianceX = varianceX / 4.0 - 2.5600000000000005
            }
            if (varianceY > maximum) {
                downsample(Decimation.By2, Axis.Y)
                varianceY = varianceY / 4.0 - 2.5600000000000005
            }
        }
        return MeltResidual(varianceX, varianceY, admitsMeltSinglePass(varianceX, varianceY, singlePassSupported))
    }

    fun admitsTileDecimation(
        size: Decimation, hasShape: Boolean, blurFlags: Int, mode: Int,
        capabilities: Capabilities, isCurrentSurface: Boolean, sourceLevel: Int,
    ) = !hasShape && (blurFlags and 2) == 0 && ((blurFlags and 1) == 0 || mode != 1) &&
        capabilities.jointTiles && isCurrentSurface && (sourceLevel and 15) >= size.factor

    data class TileDecimationPass(
        val size: Decimation, val threads: Pair<UInt, UInt>, val offsetLanes: Int, val gainInputs: Pair<Float, Float>,
    ) {
        val function get() = "tile_downsample_${size.factor}"
        val quadFold get() = size != Decimation.By2

        fun forEachTap(threadX: Int, threadY: Int, emit: (x: Int, y: Int, halfWeightBits: Int) -> Unit) {
            val weight = size.tileWeightBits
            if (size == Decimation.By8) {
                for (x in 0..3) for (y in 0..3) {
                    emit(((threadX shl 2) or x) and 0xffff, ((threadY shl 2) or y) and 0xffff, weight)
                }
            } else {
                for (y in 0..1) for (x in 0..1) {
                    emit(((threadX shl 1) or x) and 0xffff, ((threadY shl 1) or y) and 0xffff, weight)
                }
            }
        }
    }

    fun tileDecimation(
        size: Decimation, encoderLive: Boolean, destinationFlags: Int, sourceFlags: Int,
        tileSize: Pair<UInt, UInt>, sourceOrigin: Pair<Int, Int>, destinationOrigin: Pair<Int, Int>,
        destinationScale: Float, sourceScale: Float, contextScale: Float,
    ): TileDecimationPass? {
        if (!encoderLive || (destinationFlags and 0x40) == 0 || (sourceFlags and 8) == 0) return null
        val width = tileSize.first / size.threadDivisor
        val height = tileSize.second / size.threadDivisor
        if (width * height == 0u) return null
        val x = sourceOrigin.first / size.factor - destinationOrigin.first
        val y = sourceOrigin.second / size.factor - destinationOrigin.second
        val offsets = (x and 0xffff) or (y shl 16)
        return TileDecimationPass(
            size, width to height, offsets,
            (destinationScale / sourceScale) to (1f / contextScale),
        )
    }

    data class BoxDecimationPass(
        val textureFunction: Int, val computeFunction: Int?, val scale: Pair<Float, Float>,
        val offsets: List<Pair<Float, Float>>, val weights: List<Float>?,
    ) {
        val computeWriteOptions get() = when (computeFunction) {
            null -> null
            Decimation.By2.computeFunction -> 2
            else -> 3
        }

        fun computeTexel(inside: Boolean, edgePolicy: Int) = selectComputeTexel(inside, finalPass = false, edgePolicy)

        fun forEachWeightedTap(emit: (offsetX: Float, offsetY: Float, weight: Float, uvFirst: Boolean) -> Unit) {
            val compute = computeFunction != null
            if (weights != null) {
                offsets.forEachIndexed { index, (x, y) -> emit(x, y, weights[index], compute && index == 0) }
            } else if (textureFunction == Decimation.By8.textureFunction) {
                for (group in 0..3) {
                    offsets.forEachIndexed { index, (x, y) ->
                        val dx = if ((group and 2) == 0) x else -x
                        val dy = if ((group and 1) == 0) y else -y
                        emit(dx, dy, 0.0625f, compute && index == 0 && group != 3)
                    }
                }
            }
        }
    }

    fun boxDecimation(
        size: Decimation, compute: Boolean, directBox4Supported: Boolean,
        axisFactors: Pair<Float, Float>, imageScale: Pair<Float, Float>,
    ): BoxDecimationPass? {
        val computeFunction = if (compute) size.computeFunction else null
        return when (size) {
            Decimation.By2 -> BoxDecimationPass(size.textureFunction, computeFunction, axisFactors, emptyList(), null)
            Decimation.By4 -> {
                if (!compute && !directBox4Supported) return null
                val offsets = listOf(-1f to 1f, 1f to 1f, -1f to -1f, 1f to -1f)
                BoxDecimationPass(size.textureFunction, computeFunction, 4f to 4f, offsets, List(4) { 0.25f })
            }
            Decimation.By8 -> {
                val (x, y) = if (compute) 1f to 1f else imageScale
                val offsets = listOf(x to y, x to (3f * y), (3f * x) to y, (3f * x) to (3f * y))
                BoxDecimationPass(size.textureFunction, computeFunction, 8f to 8f, offsets, null)
            }
        }
    }

    enum class NarrowWidth { Taps7, Taps11, Taps15, Taps19, Taps23, Taps27 }
    enum class ComputeTexel { FilteredColor, Zero, ClampedSource, ExistingDestination }

    private fun selectComputeTexel(inside: Boolean, finalPass: Boolean, edgePolicy: Int) = when {
        inside -> ComputeTexel.FilteredColor
        finalPass -> ComputeTexel.ExistingDestination
        edgePolicy == 0 -> ComputeTexel.Zero
        else -> ComputeTexel.ClampedSource
    }

    class NarrowComputePass(val function: Int, val textureFunction: Int, private val finalPass: Boolean) {
        val writeOptions get() = if (finalPass) 3 else 2

        fun texel(inside: Boolean, edgePolicy: Int) = selectComputeTexel(inside, finalPass, edgePolicy)
    }

    private fun preparedComputeSurface(flags: Int) = (flags and 0xa0000000.toInt()) == Int.MIN_VALUE

    fun narrowCompute(width: NarrowWidth, finalRequested: Boolean, destinationFlags: Int): NarrowComputePass {
        val finalPass = finalRequested && preparedComputeSurface(destinationFlags)
        val base = if (finalPass) 0x18 else 0x12
        return NarrowComputePass(base + width.ordinal, 0x21 + width.ordinal, finalPass)
    }

    data class NarrowPreclear(val threadgroups: Pair<Long, Long>) {
        val function get() = 0x26
        val destinationTextureSlot get() = 0
        val imageblockSide get() = 32
        fun submittedFlags(current: Int) = (current and 0x5fffffff) or Int.MIN_VALUE
    }

    fun narrowPreclear(
        flags: Int, originalArgumentNonzero: Boolean, size: Pair<Int, Int>, recordedSize: Pair<Int, Int>,
        roundedUpper: Pair<Int, Int>, startEncoder: () -> Boolean,
    ): NarrowPreclear? {
        if (preparedComputeSurface(flags) || (originalArgumentNonzero && size == recordedSize)) return null
        fun gridCeiling(pixels: Int) = (pixels.toLong() + 31L) ushr 5
        val x = gridCeiling(size.first)
        val y = gridCeiling(size.second)
        if (gridCeiling(roundedUpper.first) >= x && gridCeiling(roundedUpper.second) >= y) return null
        return if (startEncoder()) NarrowPreclear(x to y) else null
    }

    data class NarrowCoordinates(val scale: Pair<Float, Float>, val bias: Pair<Float, Float>)

    fun narrowCoordinates(
        pixelOrigin: Pair<Int, Int>, pixelExtent: Pair<Int, Int>, destinationSize: Pair<Int, Int>,
        contextScale: Pair<Float, Float>, contextBias: Pair<Float, Float>,
        sampleOrigin: Pair<Double, Double>, sampleExtent: Pair<Double, Double>,
        fusedMultiplyAdd: (Float, Float, Float) -> Float,
    ): NarrowCoordinates {
        val xBias = contextBias.first + 1f
        fun destinationX(x: Int): Float {
            val normalized = fusedMultiplyAdd(contextScale.first, x.toFloat(), xBias)
            return ((destinationSize.first.toDouble() * 0.5) * normalized.toDouble()).toFloat()
        }
        fun destinationY(y: Int): Float {
            val normalized = 1f - fusedMultiplyAdd(contextScale.second, y.toFloat(), contextBias.second)
            return ((destinationSize.second.toDouble() * 0.5) * normalized.toDouble()).toFloat()
        }
        fun axis(first: Float, last: Float, origin: Double, extent: Double): Pair<Float, Float> {
            val start = origin.toFloat()
            val sampleSpan = (origin + extent).toFloat() - start
            val destinationSpan = last - first
            return (sampleSpan / destinationSpan) to (start - (sampleSpan * first) / destinationSpan)
        }
        val (x, y) = pixelOrigin
        val (dx, dy) = pixelExtent
        val horizontal = axis(destinationX(x), destinationX(x + dx), sampleOrigin.first, sampleExtent.first)
        val vertical = axis(destinationY(y), destinationY(y + dy), sampleOrigin.second, sampleExtent.second)
        return NarrowCoordinates(horizontal.first to vertical.first, horizontal.second to vertical.second)
    }

    val singlePassEncoding = listOf(FluidUniformEncoding(0xb0, 0x42), FluidUniformEncoding(0x100, 0x94), FluidUniformEncoding(0x150, 0x89))

    data class SinglePassUniforms(val offsets: List<Pair<Float, Float>>, val normalization: Float) {
        val textureFunction get() = 0x2d
        val weight get() = 0.25f
    }

    fun singlePassUniforms(
        variance: Pair<Float, Float>, sampleScale: Pair<Float, Float>, mode: Int, final: Boolean, flags: Int,
        fusedMultiplyAdd: (Double, Double, Double) -> Double,
    ): SinglePassUniforms {
        val (vx, vy) = variance
        fun axis(value: Float): Pair<Double, Double> {
            val exponent = exp(-0.5 / value.toDouble())
            val divisor = fusedMultiplyAdd(exponent, 2.0, 1.0)
            return (1.0 / divisor) to (exponent / divisor)
        }
        val x = axis(vx)
        val comparable = !vx.isNaN() && !vy.isNaN() && ((vx >= 0f) != (vy < 0f))
        val nearby = (vx.toRawBits() - vy.toRawBits() + 3).toUInt() < 7u
        val y = if (vx == vy || (comparable && nearby)) x else axis(vy)
        val a = (x.first * 4.0 * x.second).toFloat()
        val b = (y.first * 4.0 * y.second).toFloat()
        val (sx, sy) = sampleScale
        val offsets = listOf((-a * sx) to (b * sy), (a * sx) to (b * sy), (-a * sx) to (-b * sy), (a * sx) to (-b * sy))
        val normalization = if (mode == 2 && (final || (flags and 2) != 0)) 1f else 0f
        return SinglePassUniforms(offsets, normalization)
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
    enum class Filter(val registeredName: String) { GaussianBlur("gaussianBlur"), DisplacementMap("displacementMap") }
    val filterOrder get() = Filter.entries
    data class NamedMap<Surface : Any>(val surface: Surface, val scale: Float)

    /** The renderer retains its own destination and reports whether the selected kind-7 draw emitted. */
    fun <Surface : Any> retainNamedMap(
        existing: NamedMap<Surface>?, field: Surface?, destination: () -> NamedMap<Surface>?,
        render: (textureFunction: Int, fieldSlot: Int, field: Surface, destination: Surface) -> Boolean,
    ): NamedMap<Surface>? {
        if (existing != null) return existing
        if (field == null) return null
        val target = destination() ?: return null
        return if (render(glassEffectFunction, 0, field, target.surface)) target else null
    }

    fun <Image : Any> colorChain(
        content: Image, namedMap: () -> NamedMap<Image>?,
        emit: (Filter, Image, NamedMap<Image>?) -> Image?,
    ): Image = filterOrder.fold(content) { color, filter ->
        val map = if (filter == Filter.DisplacementMap) namedMap() else null
        emit(filter, color, map) ?: color
    }

    private val lensPropertyControls = FluidPropertySpringControls(delay = 0.0, startingVelocity = null, interactive = false, additive = false)

    fun publishSample(publication: FluidScalarPublication, write: (Double, Double, Int, Boolean, Boolean) -> Unit) =
        publication.consume { publishSample(it, write) }

    inline fun publishSample(sample: FluidScalarSpringBody, write: (Double, Double, Int, Boolean, Boolean) -> Unit) =
        write(sample.value, sample.velocity, 0, false, lensPropertyControls.additive)

    // The render owner supplies the active modifier value and the original filter order.
    fun applyPresentation(
        channel: FluidPopupChannel, sample: Double?, effectKind: Int, filterNames: List<String?>,
        writeHeight: (Float) -> Unit, writeAmount: (Int, Double) -> Unit,
    ) {
        if (sample == null) return
        when (channel) {
            FluidPopupChannel.LensHeight -> if (effectKind == 7) writeHeight(sample.toFloat())
            FluidPopupChannel.LensAmount -> {
                val index = filterNames.indexOf(Filter.DisplacementMap.registeredName)
                if (index >= 0) writeAmount(index, sample)
            }
            else -> Unit
        }
    }

    data class Request(val timing: FluidPopupStep<FluidPopupSpringSettings>, val gain: Double, val dimension: Double?) {
        val propertyControls get() = lensPropertyControls
    }

    fun shortDimension(scale: Double, width: Double, height: Double): Double {
        val w = scale * width
        val h = scale * height
        return if (h < w) h else w
    }

    /** D0 and D1 are the short dimensions of the smaller and larger prepared endpoints on every anchor arm. */
    fun requests(
        enabled: Boolean, growing: Boolean, smallerShort: Double, largerShort: Double,
        mainEject: FluidPopupSpringSettings, mainAbsorb: FluidPopupSpringSettings,
    ): List<Request> {
        if (!enabled) return emptyList()
        return listOf(
            Request(FluidPopupStep(0.0, FluidPopupSpringSettings(1.0, 0.2)), 1.0, if (growing) smallerShort else largerShort),
            Request(FluidPopupStep(0.0, fluidThroughMainSpring(growing, mainEject, mainAbsorb)), 1.0, if (growing) largerShort else smallerShort),
            Request(FluidPopupStep(fluidThroughTailDelay(growing), FluidPopupSpringSettings(1.0, if (growing) 0.3 else 0.4)), 0.0, null),
        )
    }

    const val trackStoredDelay = 0.0
    const val trackedHitTestsAsFill = true
    val trackedProperties = listOf("bounds", "position", "cornerRadius", FluidPopupChannel.CornerRadii.key, "cornerCurve", "hidden", "meshTransform")

    const val displacementFunction = 44
    const val glassEffectFunction = 66
    val glassEffectEncoding = listOf(FluidUniformEncoding(0xb0, 0x8c), FluidUniformEncoding(0x100, 0x8c))

    fun glassEffectHalfUniforms(
        height: Float, curvature: Float, effectOffset: Float, maskOffset: Float, angle: Float,
        sinCos: (Float) -> Pair<Float, Float>, narrowHalf: (Float) -> Short,
    ): ShortArray {
        val (sine, cosine) = sinCos(angle)
        val lanes = floatArrayOf(height, curvature, effectOffset, maskOffset, cosine, sine, 0f, 0f)
        return ShortArray(lanes.size) { narrowHalf(lanes[it]) }
    }
    fun deepFramebufferBit(extendedColorQuery: Long, preference: () -> Boolean?): Boolean =
        extendedColorQuery != 0L && (preference() ?: ((extendedColorQuery and 1L) != 0L))

    fun glassEffectFragmentName(feature16: Boolean, fieldFormat: Int?, cachedDeepFramebuffer: () -> Boolean): String {
        val floatPrecision = !feature16 || cachedDeepFramebuffer() || fieldFormat?.let { packed ->
            (0 until 6).any { lane ->
                when ((packed ushr (lane * 5)) and 31) {
                    15, 18, 20, 21 -> true
                    else -> false
                }
            }
        } == true
        return if (floatPrecision) "fixed_frag_lph_cpf" else "fixed_frag_lph_cph"
    }

    const val namedSourceTexture = 1
    const val displacementVertexType = 6
    const val freshHeight = 20.0
    const val freshAmount = -0.001
    const val epsilon = 0.05
    const val epsilonSquared = epsilon * epsilon

    fun height(dimension: Double, gain: Double) = 75.0 * (gain * minOf(dimension / 250.0, 1.0))

    fun isForegroundAtRest(blurRadius: Double, height: Double, amount: Double) =
        FluidGaussianSpec.isIdentity(blurRadius) && amount == freshAmount && (height == 0.0 || height == freshHeight)

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
    fun clampRadius(value: Double, upper: Double): Double {
        val bounded = if (value <= upper) value else upper
        return if (bounded < 0.0) 0.0 else bounded
    }

    fun <Receiver : Any> writeModelRadii(
        readModel: () -> DoubleArray, clipping: Receiver, self: Receiver,
        platformReceivers: Pair<() -> Receiver, () -> Receiver>?, write: (Receiver, DoubleArray) -> Unit,
    ) {
        write(clipping, readModel())
        val first = readModel()
        write(platformReceivers?.first?.invoke() ?: self, first)
        val second = readModel()
        write(platformReceivers?.second?.invoke() ?: self, second)
    }

    fun publishRadii(
        width: Double, height: Double, readPresentation: () -> DoubleArray,
        platform: ((FluidPopupChannel, DoubleArray) -> Unit)?,
        coordinator: ((FluidPopupChannel, DoubleArray, DoubleArray?, Int, Boolean) -> Unit)?,
        clipping: (FluidPopupChannel, DoubleArray, DoubleArray?, Int, Boolean) -> Unit,
    ) {
        val radii = readPresentation()
        val halfWidth = width * 0.5
        val halfHeight = height * 0.5
        val upper = if (halfHeight < halfWidth) halfHeight else halfWidth
        require(upper >= 0.0)
        val presented = DoubleArray(8) { index -> clampRadius(radii[index], upper) }
        val channel = FluidPopupChannel.CornerRadii
        platform?.invoke(channel, presented)
        coordinator?.invoke(channel, presented, null, 0, false)
        clipping(channel, presented, null, 0, false)
    }

    private fun scalarRadius(stored: Float?, width: Double, height: Double) = when {
        stored == null -> 0f
        stored.toRawBits() == 0x7fe00000 -> (0.5 * (if (width < height) width else height)).toFloat()
        stored.isNaN() -> 0f
        else -> stored
    }

    fun matchedRadius(stored: Float?, width: Double, height: Double) =
        scalarRadius(stored, width, height).let { if (it < 0f) 0f else it }

    // A null numeric payload skips the write and keeps the destination's existing vector.
    fun matchedRadii(source: DoubleArray?, capacity: Int): DoubleArray? =
        source?.takeIf { capacity >= 8 && it.size == 8 }?.let { DoubleArray(8) { i -> if (it[i] < 0.0) 0.0 else it[i] } }

    fun rawRadii(
        precomputed: FloatArray?, suppressFallback: Boolean, vector: DoubleArray?, scalar: Float?, width: Double, height: Double,
    ): FloatArray? {
        if (precomputed != null) return precomputed
        if (suppressFallback) return null
        if (vector != null) return FloatArray(4) { vector[it * 2].toFloat() }
        val radius = scalarRadius(scalar, width, height)
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
        val imageFunction: Int, val family: Float, val normalMatrix: FloatArray, val radii: FloatArray,
        val fieldHalfSize: Pair<Float, Float>, val gridCut: Pair<Float, Float>?,
    )

    // Effect kind 7 supplies this first lane to both the direct and inline draws.
    fun parentPadding(
        amount: Float, base: Float, weighted: Float, offset: Float,
        fusedMultiplyAdd: (Float, Float, Float) -> Float,
    ): Float {
        val padding = fusedMultiplyAdd(weighted, Float.fromBits(0x40333333), base) + -amount
        return if (offset > 0f) padding else padding - offset
    }

    fun boundsEncoding(
        raw: FloatArray, transform: FieldTransform, curveCode: Int,
        width: Double, height: Double, inheritedPad: Float, inline: Boolean,
    ): BoundsEncoding {
        val capped = if (inheritedPad > 4096f) 4096f else inheritedPad
        val pad = if (!inline && capped < 1f) 1f else capped
        val high = if (width > height) width else height
        val low = if (width < height) width else height
        val canPad = high < Double.MAX_VALUE && low > 0.0
        val grownBy = if (canPad) (-pad / transform.scale).toDouble() * -2.0 else 0.0
        val paddedWidth = if (canPad) grownBy + width else width
        val paddedHeight = if (canPad) grownBy + height else height
        val collapsed = canPad && (paddedWidth <= 0.0 || paddedHeight <= 0.0)
        val halfWidth = if (collapsed) 0f else (paddedWidth * transform.scale).toFloat() * 0.5f
        val halfHeight = if (collapsed) 0f else (paddedHeight * transform.scale).toFloat() * 0.5f
        val fieldHalfSize = (halfWidth - pad) to (halfHeight - pad)
        val floor = Float.fromBits(0x3c23d70a)
        val clamped = FloatArray(4) { if (raw[it] < floor) floor else raw[it] }
        val uniform = clamped[0] == clamped[1] && clamped[1] == clamped[2] && clamped[2] == clamped[3]
        val scaled = FloatArray(4) { clamped[it] * transform.scale }
        val continuous = curveCode and 3 != 0
        fun orderedMax(left: Float, right: Float) = if (left > right) left else right
        val maxRadius = orderedMax(orderedMax(scaled[0], scaled[1]), orderedMax(scaled[2], scaled[3])).toDouble()
        val expansion = if (continuous) maxRadius * Double.fromBits(0x3ff875696e58a32fL) else maxRadius
        val threshold = expansion + pad.toDouble()
        fun cut(half: Float) = (if (threshold < half.toDouble()) threshold.toFloat() else half) / transform.scale
        val gridCut = if (threshold >= orderedMax(halfWidth, halfHeight).toDouble()) null else cut(halfWidth) to cut(halfHeight)
        if (!uniform) {
            val radii = if (continuous) FloatArray(4) { -scaled[it] } else scaled
            return BoundsEncoding(22, 5f, transform.normalMatrix, radii, fieldHalfSize, gridCut)
        }
        fun weight(half: Float): Float {
            if (!continuous) return 1f
            val coefficient = Double.fromBits(0x400721e8a7a4b61bL)
            return ((half + half).toDouble() * -coefficient / expansion + coefficient).coerceIn(0.0, 1.0).toFloat()
        }
        val radii = floatArrayOf(weight(fieldHalfSize.first), weight(fieldHalfSize.second), scaled[0], 0f)
        return BoundsEncoding(21, 4f, transform.normalMatrix, radii, fieldHalfSize, gridCut)
    }

    // This is the combine byte in draw state, before pipeline flag substitution.
    fun groupCombineSelector(subtract: Boolean, roiDirect: Boolean, roiOverlap: Boolean) = when {
        subtract -> 59
        roiDirect -> 1
        roiOverlap -> 58
        else -> 3
    }
    const val rectImageFunction = 20

    data class FieldFragment(val base: Int, val combine: Int) {
        val imageCount get() = 1
    }

    fun fieldFragment(inline: Boolean, compose: Boolean, combineSelector: Int) =
        FieldFragment(if (inline) FluidLensingSpec.glassEffectFunction else 1, if (!compose || combineSelector == 1) 63 else combineSelector)

    fun scansOverlap(parentAdmitted: Boolean, disableOption: Boolean) = parentAdmitted && !disableOption
    fun <Node : Any> inlineBounds(
        nodes: Iterable<Node>, debugDisablesInline: Boolean, capabilityDisablesInline: Boolean,
        parentRequiresCheck: Boolean, hasInputSurface: Boolean, checkParent: () -> Boolean,
        flags: (Node) -> Long, isBoundsUnion: (Node) -> Boolean, children: (Node) -> Iterable<Node>,
    ): Node? {
        if (debugDisablesInline || capabilityDisablesInline || (parentRequiresCheck && !checkParent())) return null
        var candidate: Node? = null
        fun scan(siblings: Iterable<Node>): Boolean {
            for (node in siblings) {
                val word = flags(node)
                if ((word and 0x30000000000L) == 0x10000000000L) continue
                if ((word and 0x8000000000L) != 0L) {
                    if (candidate != null || !isBoundsUnion(node)) return false
                    candidate = node
                }
                if (!scan(children(node))) return false
            }
            return true
        }
        return if (scan(nodes) && !hasInputSurface) candidate else null
    }
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
internal const val GlassBlurDefault = .2f
internal const val GlassWhiteTint = .06f
internal const val GlassShadowStrength = 1f
internal const val GlassShadowSpread = 1f
internal const val GlassContrast = .25f
internal const val GlassContrastWeightMin = 1f
internal const val GlassContrastWeightDefault = 2f
internal const val GlassContrastWeightMax = 8f
internal const val GlassShapeDamping = 8f
internal const val GlassDepth = 1.35f
internal const val GlassControlExpansion = 1.75f
internal const val GlassLiftShadowStrength = 1f
internal const val GlassTouchLight = 1f
internal const val GlassShadowShift = 8f
internal const val GlassShadowMaskRadius = 24f
internal const val GlassShadowFill = .20f
internal fun glassShadowReach(spread: Float) = 2f * GlassShadowMaskRadius * spread
internal fun glassShadowInset(spread: Float) = (glassShadowReach(spread) + GlassShadowShift).dp
/** Layout padding and hit areas beside a shadow: independent of the draw reach (22.8 dp at the default multiplier). */
internal val GlassLayoutInset = 22.8f.dp
@Immutable class UiSpec internal constructor(val space: Map<Space,Dp>, val radius: Map<Radius,Dp>, val control: Map<ControlSize,Dp>, val clearance: Map<Clearance,Dp>, val motion: Map<MotionProfile,MotionSpec>, val spring: Map<SpringProfile,SpringProfileSpec>, val expansion: ExpansionSpec, val access: MotionAccess, internal val geometry: ExpansionGeometryDomain?=null, val sheetExpansion: ExpansionSpec=expansion, val lyricsExpansion: ExpansionSpec=expansion, val selectionExpansion: ExpansionSpec=expansion, val popupMotion: PopupMotionModel=StockPopupMotion, val speed: Float=1f)
@Immutable data class UiSpecData(val space: Map<Space,Dp>? = null, val radius: Map<Radius,Dp>? = null, val control: Map<ControlSize,Dp>? = null, val clearance: Map<Clearance,Dp>? = null, val motion: Map<MotionProfile,MotionSpec>? = null, val spring: Map<SpringProfile,SpringProfileSpec>? = null, val expansion: ExpansionSpec? = null, val access: MotionAccess? = null, val sheetExpansion: ExpansionSpec? = null, val lyricsExpansion: ExpansionSpec? = null, val selectionExpansion: ExpansionSpec? = null, val popupMotion: PopupMotionModel? = null, val speed: Float? = null)
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
val StockUiSpec: UiSpec = UiSpec(dimensions(Space.entries,listOf(1f,4f,8f,12f,16f,24f,28f)), dimensions(Radius.entries.dropLast(1),listOf(8f,16f,20f,24f,28f)), dimensions(ControlSize.entries,listOf(64f,51.2f,32f,64f,28f,24f,56f,48f,24f)), dimensions(Clearance.entries,listOf(8f,4f,1f,80f)),
    frozen<MotionProfile,MotionSpec>(MotionProfile.entries.take(10).zip(listOf(180,270,220,220,300,240,300,165,300,225).mapIndexed { i,d -> MotionProfileSpec(d,if(i in listOf(0,1,2,3,6)) slow else fast) }).toMap()+mapOf(MotionProfile.LyricEmphasis to MotionProfileSpec(350,fast),MotionProfile.Marquee to MarqueeSpec(1800,220,260,30f,EasingSpec(1f/3,0f,2f/3,1f)),MotionProfile.Scroll to ListMotionSpec(350,500,120,fast,linear),MotionProfile.SettingsScroll to SettingsScrollSpec(262,175,fast),MotionProfile.SettingsHighlight to SettingsHighlightSpec(150,450,600,1200,.18f,fast)),null),
    frozen(mapOf(SpringProfile.Page to SpringProfileSpec(.85f,380f)),null), StockPopupExpansion,MotionAccess.Standard,null,StockSheetExpansion,StockLyricsExpansion,StockSelectionExpansion)
fun uiSpec(settings: InterfaceSettings, overrides: UiSpecData? = null, geometry: ExpansionGeometryDomain? = null): SpecResult {
    val o=overrides?:UiSpecData(); val b=StockUiSpec; val errors=mutableListOf<String>()
    fun valid(ok: Boolean, key: String) { if(!ok) errors+=key }; fun bounded(v: Float,lo: Float,hi: Float)=v.isFinite() && v in lo..hi
    val space=frozen(b.space,o.space); val radius=frozen(b.radius,o.radius); val control=frozen(b.control,o.control)
    val clearance=frozen(b.clearance,o.clearance); val motion=frozen(b.motion,o.motion); val spring=frozen(b.spring,o.spring); val expansion=o.expansion?:b.expansion; val sheetExpansion=o.sheetExpansion?:b.sheetExpansion; val lyricsExpansion=o.lyricsExpansion?:b.lyricsExpansion; val selectionExpansion=o.selectionExpansion?:b.selectionExpansion; val popupMotion=o.popupMotion?:if(settings.popupMotion==PopupMotionStyle.Fluid) fluidPopupMotion() else b.popupMotion; val speed=o.speed?:settings.animationSpeed
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
    return if(errors.isNotEmpty()) SpecResult.Rejected(errors.toList()) else SpecResult.Accepted(UiSpec(space,radius,control,clearance,motion,spring,expansion,if(settings.reducedMotion || o.access==MotionAccess.Reduced) MotionAccess.Reduced else o.access?:MotionAccess.Standard,geometry,sheetExpansion,lyricsExpansion,selectionExpansion,popupMotion,speed))
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
    val centreBand = m.centre?.band ?: band
    if(!(centreBand.isFinite() && centreBand>0f)) return listOf("centreBand")
    for((key,law) in listOf("open" to m.open,"close" to m.close,"crossOpen" to m.crossOpen,"gate" to m.gate)+listOfNotNull(m.centre?.let { "centre" to it.spring })) {
        if(!(law.omega.isFinite() && law.zeta.isFinite() && law.omega in 1f..400f && law.zeta in (if(key=="centre") .3f else .5f)..4f)) { errors+=key; continue }
        if(key!="open" && key!="centre" && law.zeta<1f) errors+="$key.overshoot"
        if((key=="open" || key=="centre") && springOvershoot(law.zeta.toDouble())>(if(key=="centre") centreBand else band)) errors+="$key.overshoot"
        if(popupLawHorizon(law,MinimumAnimationSpeed.toDouble(),if(key=="centre") 1.0+centreBand else 1.05)>2.0) errors+="$key.horizon"
    }
    if(!(m.snapAspect.isFinite() && m.snapAspect>1f)) errors+="snapAspect"
    if(!(m.preparedRamp.isFinite() && m.preparedRamp in 0f..(.5f) && m.rampBlend.isFinite() && m.rampBlend>0f && m.rampBlend<=.25f)) errors+="ramp"
    if(errors.isEmpty()) for((pair,peak) in m.reversalReach) { val limit=if(pair.startsWith("centre")) centreBand else band; if(peak.start< -limit || peak.endInclusive>1+limit) errors+="reversal.$pair" }
    if(PopupKind.entries.any { it !in m.variants } || m.variants[PopupKind.Legacy]!=PopupMotionVariant.Legacy || m.variants.any { (k,v) -> k!=PopupKind.Legacy && v==PopupMotionVariant.Legacy }) errors+="variants"
    return errors
}
internal interface SpringTransformValues<Value : Any> {
    val zeroVelocity: Value
    fun equal(left: Value, right: Value): Boolean
    fun displacement(from: Value, to: Value): Value
    fun scaledCopy(value: Value, factor: Double): Value
    fun add(left: Value, right: Value): Value
}

/** #854 (rule 12): the one closed-form damped spring. The engine's Damped flies it and the admission below composes it (the spec
 * layer cannot depend on the engine). Overdamped laws use expm1, so near-critical laws keep their precision. */
internal class SpringKernel(private val omega: Double, private val zeta: Double, installedDamping: Double? = null) {
    companion object {
        fun fromInstalled(stiffness: Double, damping: Double): SpringKernel {
            val omega = sqrt(stiffness)
            return SpringKernel(omega, damping / (omega + omega), damping)
        }
    }

    private val installed = installedDamping != null
    private val a = installedDamping?.times(0.5) ?: zeta * omega
    private val underdamped = if (installed) a < omega else zeta < 1.0
    private val critical = if (installed) a == omega else zeta == 1.0
    private val wd = if (installed) sqrt(abs(omega * omega - a * a)) else omega * sqrt(abs(1 - zeta * zeta))
    internal val slow = if (zeta > 1) omega / (zeta + sqrt(zeta * zeta - 1)) else a
    private val fast = if (zeta > 1) omega * (zeta + sqrt(zeta * zeta - 1)) else omega

    private inline fun <Result> installedBasis(t: Double, apply: (sine: Double, cosine: Double, envelope: Double) -> Result): Result {
        val envelope = exp(-a * t)
        if (critical) return apply(t, 1.0, envelope)
        val angle = wd * t
        return if (underdamped) apply(sin(angle), cos(angle), envelope) else apply(sinh(angle), cosh(angle), envelope)
    }

    /** The offset from the target ([d] false) or the velocity ([d] true) [t] seconds after offset [offset] and velocity [v]. */
    fun response(offset: Double, v: Double, t: Double, d: Boolean, use: ScalarSpringUse = ScalarSpringUse.Property): Double {
        if (installed) return installedBasis(t) { sine, cosine, envelope ->
            val intermediate = use == ScalarSpringUse.Intermediate
            val property = use == ScalarSpringUse.Property
            val start = if (intermediate || (property && critical)) v + a * offset else a * offset + v
            if (critical) {
                val displacement = offset + if (property) start * t else t * start
                if (d) envelope * start - envelope * (displacement * a) else envelope * displacement
            } else {
                val b = (1 / wd) * start
                val displacement = when {
                    property && underdamped -> offset * cosine + b * sine
                    property || underdamped -> cosine * offset + sine * b
                    intermediate -> offset * cosine + b * sine
                    else -> offset * cosine + sine * b
                }
                if (d) {
                    val first = wd * (offset * sine)
                    val second = if (intermediate && !underdamped) wd * (b * cosine) else wd * (cosine * b)
                    val derivative = when {
                        underdamped -> second - first
                        intermediate -> second + first
                        else -> first + second
                    }
                    envelope * derivative - envelope * (displacement * a)
                } else envelope * displacement
            }
        }
        if (underdamped) {
            val b = (v + a * offset) / wd
            val angle = wd * t
            val cosine = cos(angle)
            val sine = sin(angle)
            val envelope = exp(-a * t)
            val displacement = offset * cosine + b * sine
            if (!d) return envelope * displacement
            return envelope * (v * cosine - (a * b + offset * wd) * sine)
        }
        if (critical) {
            val b = v + a * offset
            val displacement = offset + b * t
            val envelope = exp(-a * t)
            if (!d) return envelope * displacement
            return envelope * (v - a * b * t)
        }
        val difference = fast - slow
        val b = -(v + slow * offset) / difference
        val extra = expm1(-difference * t)
        return exp(-slow * t) * if (d) -slow * offset + b * (-slow * extra - difference * exp(-difference * t)) else offset + b * extra
    }

    internal fun advanceBody(
        body: FluidScalarSpringBody, target: Double, dt: Double,
        use: ScalarSpringUse = ScalarSpringUse.Property, displacementMode: Int = 0,
    ): FluidScalarSpringBody {
        if (dt <= 0.0 || (body.value == target && body.velocity == 0.0)) return body
        val offset = fluidModeDisplacement(body.value - target, displacementMode)
        val displacement = response(offset, body.velocity, dt, false, use)
        val velocity = response(offset, body.velocity, dt, true, use)
        return FluidScalarSpringBody(target + displacement, velocity)
    }

    /** Installed transform channels preserve their value type's ordered copy, scale and sum operations. */
    fun <Value : Any> advanceTransform(
        body: Pair<Value, Value>, target: Value, dt: Double, values: SpringTransformValues<Value>,
    ): Pair<Value, Value> {
        if (dt <= 0.0 || (values.equal(body.first, target) && values.equal(body.second, values.zeroVelocity))) return body
        val offset = values.displacement(body.first, target)
        return installedBasis(dt) { sine, cosine, envelope ->
            fun start() = values.add(values.scaledCopy(offset, a), body.second)
            fun waveStart() = values.scaledCopy(start(), 1 / wd)
            fun displaced() = if (critical) values.add(offset, values.scaledCopy(start(), dt))
                else values.add(values.scaledCopy(offset, cosine), values.scaledCopy(waveStart(), sine))

            val displacement = values.scaledCopy(displaced(), envelope)
            val velocityTerm = if (critical) values.scaledCopy(start(), envelope) else {
                val first = values.scaledCopy(values.scaledCopy(offset, if (underdamped) -sine else sine), wd)
                val second = values.scaledCopy(values.scaledCopy(waveStart(), cosine), wd)
                values.scaledCopy(values.add(first, second), envelope)
            }
            val decayTerm = values.scaledCopy(values.scaledCopy(displaced(), -a), envelope)
            val velocity = values.add(velocityTerm, decayTerm)
            values.add(displacement, target) to velocity
        }
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
    return pairs(m.open,"")+m.centre?.let { pairs(it.spring,"centre") }.orEmpty()
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
