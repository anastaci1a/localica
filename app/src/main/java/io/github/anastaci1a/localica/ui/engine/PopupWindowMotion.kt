package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.*

/** #920: a popup's one stored takeover phase (SPEC.md section 2); every takeover flag is a view of it. */
internal enum class PopupTakeover { Closed, Admitting, InWindow, Restoring }
/** A retained presentation owns takeover; its trigger and shared content borrow the current view. */
internal interface PopupTakeoverOwner {
    val takeover: PopupTakeover
    val expanded: Boolean get() = takeover == PopupTakeover.InWindow
}

/** #854: the surface kind of the enclosing popup; retained faces inside it (rememberFaceExpansion) inherit its motion. */
internal val LocalPopupKind = staticCompositionLocalOf { PopupKind.Legacy }

/** The native presenter owns one expansion and one measured rectangle. A native
 * receipt releases its gate; changing the envelope rebases those same edges. */
@Stable internal class PopupWindowMotion(
    private val clock: UiClock, spec: ExpansionSpec, key: Any, prepared: Boolean, quiet: Boolean, val kind: PopupKind = PopupKind.Legacy
) {
    val expansion = ExpandCollapse(clock, spec, false, key).also { it.usePopupLaw(clock.spec.popupMotion, kind) }
    private val model = clock.spec.popupMotion
    /** Whether this window runs the #854 spring model rather than the Legacy v0.33.21 popup law. */
    val spring = expansion.popupLaw != null
    private val gate = if (!prepared) 0f else if (spring) model.preparedRamp else .25f
    // #854 §1.5: prepared origins ramp C1 off the gate and pass the overshoot; quiet row menus leave the trigger at once.
    val geometryCurve = if (prepared) (if (spring) Curve.ramp(model.preparedRamp, model.rampBlend) else Curve.window(.25f, 1f))
        else if (quiet && !spring) Curve.window(.14f, 1f) else Curve.linear(0f, 1f)
    val popupCurve = if (prepared) geometryCurve else Curve.linear(0f, 1f)
    val quietStrength=Curve.window(0f,.14f)
    val quietContent=Curve.window(0f,.25f)
    val foreground=Curve.window(.18f,.83f)
    /** Detached Pages and anchorless dialogs fade their layer late in the same phase, following v0.33's perceived fade:
     * opaque above .85, visibly fading through the middle, and gone below .05. */
    val detachedOpacity=Curve.window(.05f,.85f)
    val homeWeight=Curve.constant(1f)+Curve.window(0f,.5f).scaled(-1f)
    val face=Curve.linear(1f,1f-1f/.55f)
    val preparationCurve = Curve.window(0f, .25f).scaled(.68359375f) +
        Curve.window(.25f, .75f).scaled(.31640625f)
    private val ranges = requireNotNull(clock.spec.geometry).maxViewportDiagonalPx.let { EdgeRanges(it, it, it, it) }
    internal var rectangle by mutableStateOf<MotionRect?>(null); private set
    val bounds: MotionRectView? get() = rectangle?.let { expansion.aperture }
    /** The intent: what [show] last asked for. Foreground semantics follow it, so Hide releases them at its event. */
    var shown by mutableStateOf(false); private set
    val openAtRest get() = expansion.retirementReady && expansion.boundedProgress(popupCurve) == 1f
    val moving get() = if (shown && !expansion.hasOutline) expansion.boundedProgress(popupCurve) != 1f else !expansion.retirementReady
    private var holding = false
    private var openingEnvelope: Rect? = null
    private var nativeWindow: java.lang.ref.WeakReference<Any>? = null
    private var firstNativeMeasurement = false
    private var nativeHost: java.lang.ref.WeakReference<ShadowPlane>? = null
    private data class FluidPlacement(val basis: ExpansionBasis, val preview: PopupOutlinePreview<Rect>?, val intent: Boolean? = null,
        val outline: PopupOutline<Rect>? = null, val request: Pair<FluidTimelineStep,Double>? = null)
    private var placement: FluidPlacement? = null
    internal fun outlineBodies()=expansion.outlineBodies()
    internal fun apertureRadius(density: Density, fallback: ()->Dp)=expansion.outlineRadius()?.let { with(density) { it.toDp() } }?:fallback()
    var fluidBlurRadius by mutableDoubleStateOf(0.0); private set
    var fluidLensHeight by mutableDoubleStateOf(FluidLensingSpec.freshHeight); private set
    var fluidLensAmount by mutableDoubleStateOf(FluidLensingSpec.freshAmount); private set
    private val fluidBlur = FluidPropertyMotion(clock, Double::equals, { sample -> FluidSharedBlurSpec.publishRadius(sample) { radius, _, _ -> fluidBlurRadius = radius } })
    private val lensHeight = FluidPropertyMotion(clock, Double::equals, { sample ->
        FluidLensingSpec.publishSample(sample) { height, _, _, _, _ -> fluidLensHeight = height }
    })
    private val lensAmount = FluidPropertyMotion(clock, Double::equals, { sample ->
        FluidLensingSpec.publishSample(sample) { amount, _, _, _, _ -> fluidLensAmount = amount }
    })
    private val fluidBlurControls = FluidPropertySpringControls(0.0, null, false, false)
    private val fluidTimeline = FluidTimelineMotion(clock) { step, speed ->
        deliverOutline(step,speed)
        val radius = when (step.action) {
            FluidTimelineAction.BlurOut -> FluidSharedBlurSpec.blurOutRadius
            FluidTimelineAction.BlurIn -> FluidContentBlurIn.gaussianRadius
            else -> null
        }
        val spring = step.propertySpring(speed)
        // The attached window's density is the point scale; before attachment it is 1.
        val scale = fluidLayerPointScale(nativeWindow?.get() != null) { placement?.basis?.density?.toDouble() }
        if (radius != null && spring != null) fluidBlur.animate(fluidBlurRadius, radius, fluidBlurControls, spring, fluidRadiusEpsilonSquared(scale))
        val lens = step.lens
        if (lens != null && spring != null) {
            val height = if (step.action == FluidTimelineAction.LensOff) 0.0
                else lens.dimension?.let { FluidLensingSpec.height(it, lens.gain) }
            if (height != null) {
                val epsilonSquared = FluidLensingSpec.epsilonSquared
                lensHeight.animate(fluidLensHeight, height, lens.propertyControls, spring, epsilonSquared)
                lensAmount.animate(fluidLensAmount, minOf(-height, FluidLensingSpec.freshAmount), lens.propertyControls, spring, epsilonSquared)
            }
        }
    }
    val fluid get() = expansion.popupLaw?.model?.style == PopupMotionStyle.Fluid
    init { expansion.nativeGate(gate) }
    fun attached(window: Any, host: ShadowPlane) {
        nativeHost = java.lang.ref.WeakReference(host)
        if (nativeWindow?.get() !== window) {
            firstNativeMeasurement = rectangle != null
            nativeWindow = java.lang.ref.WeakReference(window)
        }
    }
    fun show(visible: Boolean) {
        if(!shown && visible && expansion.retirementReady) expansion.clearOutline()
        expansion.refreshPopupLaw()
        shown = visible
        if(placement?.preview!=null && placement?.intent!=visible) fluidTimeline.replace(emptyList())
        if (rectangle != null) expansion.target(visible)
        startFluid()
    }
    internal fun acknowledgeNativeGate(generation: Long) { if(expansion.acknowledgeNativeGate(generation)) startFluid() }
    fun hold() {
        holding = true
        fluidTimeline.hold()
        if (rectangle != null) expansion.hold()
    }
    fun release() {
        holding = false
        expansion.release()
        fluidTimeline.release()
    }
    fun place(home: Rect, envelope: Rect, density: Float, detached: Boolean = false, preview: PopupOutlinePreview<Rect>? = null) {
        if (shown || openingEnvelope == null) openingEnvelope = envelope
        val capturedEnvelope = requireNotNull(openingEnvelope)
        // A detached window can present beyond its root viewport; its duration never exceeds the admitted travel.
        val basis = ExpansionBasis(home, capturedEnvelope, density, if (detached) minOf(maxOf(capturedEnvelope.width,
            capturedEnvelope.height), requireNotNull(clock.spec.geometry).maxViewportDiagonalPx) else null)
        if (rectangle == null) {
            expansion.setBasis(basis)
            val rect = MotionRect(clock, home, ranges, expansion)
            expansion.bind(rect, geometryCurve)
            rectangle = rect
        } else {
            val initialized = !holding && firstNativeMeasurement && nativeHost?.get()?.let { expansion.initializeNativeHome(basis, it) } == true
            if (!initialized) expansion.setBasis(basis)
        }
        firstNativeMeasurement = false
        expansion.target(shown)
        if (holding) expansion.hold()
        val old=placement
        if(fluid && (old?.basis!=basis || old?.preview!=preview)) {
            val sameIntent=old?.intent==shown
            val outline=if(sameIntent) preview?.prepare(basis.startBounds,basis.endBounds,basis.density,shown) else null
            placement=FluidPlacement(basis,preview,old?.intent,outline,old?.request.takeIf { sameIntent })
            placement?.request?.let { (step,speed) -> deliverOutline(step,speed) }
        }
        startFluid()
    }
    private fun deliverOutline(step: FluidTimelineStep,speed: Double) {
        val current=placement?:return
        val outline=current.outline?.takeIf { current.intent==shown }?:return
        val root=fluidThroughMainSpring(outline.growing,FluidMainEject,FluidMainAbsorb)
        val spring=step.geometrySpringBinding(speed).sharedSpring ?: (if(step.action==FluidTimelineAction.KickEjectee) FluidKickSpec.ejecteeSpring else root).scaledResponses(speed)
        outline.outline(step.action)?.let { poses ->
            placement=current.copy(request=step to speed)
            expansion.replaceOutline(poses,outline.apertureSlot,outline.kickScale,spring)
        }
        outline.kick(step.action)?.let { (slot,target) -> expansion.kickOutline(slot,target,spring) }
    }
    private fun startFluid() {
        val current=placement?:return
        if (!fluid || current.intent==shown || current.intent==null && !shown) return
        if(current.preview!=null && shown && expansion.nativeOpening==true) return
        val basis=current.basis
        val outline=current.preview?.prepare(basis.startBounds,basis.endBounds,basis.density,shown)
        placement=current.copy(intent=shown,outline=outline,request=null)
        val from = if (shown) basis.startBounds else basis.endBounds
        val to = if (shown) basis.endBounds else basis.startBounds
        val growing = outline?.growing?:fluidThroughGrows(from.width.toDouble(), from.height.toDouble(), to.width.toDouble(), to.height.toDouble())
        // Lens heights are in points: the prepared endpoints' short dimensions at the window density.
        fun short(r: Rect) = FluidLensingSpec.shortDimension(1.0 / basis.density, r.width.toDouble(), r.height.toDouble())
        val (smaller, larger) = if (growing) from to to else to to from
        fluidTimeline.replace(fluidThroughQueue(growing, FluidMainEject, FluidContent) {
            FluidLensingSpec.requests(FluidContent.lensingEnabled, growing, short(smaller), short(larger), FluidMainEject, FluidMainAbsorb)
        })
        if(outline!=null) for(slot in 0..1) expansion.kickOutline(slot,FluidKickSpec.unkickTarget.toFloat(),FluidMainAbsorb.scaledResponses(FluidContent.speed))
        fluidTimeline.start(FluidContent.speed, FluidContent.amplifyDelays, holding)
    }
    fun updateSpeed(speed: Float) = expansion.updateSpeed(speed)
    fun updateSpec(spec: ExpansionSpec) = expansion.updateSpec(spec)
    fun dispose() {
        clock.unregister(fluidTimeline)
        clock.unregister(fluidBlur)
        clock.unregister(lensHeight)
        clock.unregister(lensAmount)
        expansion.dispose()
    }
}

/** #854: a popup's kind follows its placement and its trigger's role, which bind after the first composition, but it changes only
 * while the popup is not present. One owner then serves a whole presentation: its native gate coroutine, window attachment and faces
 * never hold a disposed owner, and a kind that settles while open (a menu first composed visible before its trigger is measured)
 * applies from the next presentation. */
internal class PopupKindLatch(private var committed: PopupKind) {
    fun settle(derived: PopupKind, present: Boolean): PopupKind = if (present) committed else derived
    fun commit(kind: PopupKind) { committed = kind }
}

/** The kind of [key]'s popup this composition: [derived] while not [present], else the kind its present owner was built with.
 * The latch commits only applied compositions. */
@Composable internal fun settledPopupKind(key: Any, derived: PopupKind, present: Boolean): PopupKind {
    val latch = remember(key) { PopupKindLatch(derived) }
    val kind = latch.settle(derived, present)
    SideEffect { latch.commit(kind) }
    return kind
}

/** The Engine owns construction, spec updates and disposal; hosts borrow its view. */
@Composable internal fun rememberPopupWindowMotion(key: Any, prepared: Boolean, quiet: Boolean, kind: PopupKind = PopupKind.Legacy): PopupWindowMotion {
    val clock = ownerClock()
    val spec = io.github.anastaci1a.localica.ui.spec.LocalUiSpec.current.spec.expansion
    // [kind] is settled (settledPopupKind): it changes only at closed rest, and a changed kind is a new owner, never a retarget.
    val motion = remember(clock, key, kind) { PopupWindowMotion(clock, spec, key, prepared, quiet, kind) }
    SideEffect { motion.updateSpec(spec) }
    DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
