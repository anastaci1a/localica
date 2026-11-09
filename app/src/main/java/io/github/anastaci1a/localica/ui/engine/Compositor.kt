package io.github.anastaci1a.localica.ui.engine
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.geometry.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Outline
import io.github.anastaci1a.localica.ui.spec.*
enum class Stratum { Page,Lifted,Floating,Drawer,Player,Modal,Overlay,Diagnostics }; enum class ShadowRole { None,Glass,Lift,Glow }
@Immutable data class SurfaceTransform(val translation: Offset=Offset.Zero,val scale: Float=1f,val pivot: Offset=Offset.Zero) { init { require(translation.isFinite && pivot.isFinite && scale.isFinite() && scale>0) { "A surface transform needs finite offsets and a positive scale" } }
    internal fun map(r: Rect)=Rect(pivot.x+(r.left-pivot.x)*scale+translation.x,pivot.y+(r.top-pivot.y)*scale+translation.y,pivot.x+(r.right-pivot.x)*scale+translation.x,pivot.y+(r.bottom-pivot.y)*scale+translation.y) }
@Immutable data class SurfaceTarget(val stratum: Stratum,val opacity: Float,val shadow: ShadowRole,val transform: SurfaceTransform=SurfaceTransform()) { init { require(opacity in 0f..1f) { "Surface opacity is a finite fraction" } } }
@Stable interface SurfaceView { val key: Any; val shape: ShapeView; val stratum: Stratum; val opacity: MotionValue; val shadowOwnership: MotionValue; val transform: MotionRectView; val retained: Boolean }
/** D37: a native window and its registry-allocated route epoch. Identity equality: an outgoing host keeps its key, a reopened route gets a new one. */
@Immutable class HostKey internal constructor(val window: Any,val routeEpoch: Long) { internal companion object { private var epochs=0L; fun next(window: Any)=HostKey(window,++epochs) } }
/** D25/D28: one plane per host. A key registers once per plane; [acknowledge] is the host's draw receipt, which admits transfers waiting for this plane. */
@Stable class ShadowPlane internal constructor(private val clock: UiClock,val host: HostKey) {
    private val registered=linkedMapOf<Any,SurfaceNode>(); private var registrations=0; internal var generation=0L; private set
    internal fun sameClock(value: UiClock)=clock===value
    internal fun node(key: Any) = registered[key]
    internal fun requestDraw() { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { revision++ }; view?.invalidate() }
    internal val casters: List<SurfaceNode> get() { revision; return registered.values.toList() }
    internal fun enter(node: SurfaceNode): Int { clock.checkMutation(); require(registered[node.key].let { it==null || it===node }) { "One SurfaceNode per key per host" }; if(registered[node.key]===node) return registrations; registered[node.key]=node; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { revision++ }; return ++registrations }
    internal fun leave(node: SurfaceNode) { clock.checkMutation(); if(registered[node.key]===node) { registered.remove(node.key); androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { revision++ } } }
    /** I3/D27: exactly one plane draws a caster's field, at strength × opacity; the others contribute 0. Never a partition: an alpha split (A(1−w), Aw) dips by A²w(1−w), and a strength split, exact before quantization, misses the drawn field by .011 at 16 rings (PlaneOracleTest). */
    internal fun contributionAt(node: SurfaceNode,stamp: FrameStamp)=if(node.owns(this)) node.opacity.valueAt(stamp) else 0f
    internal fun acknowledge() { clock.checkMutation(); receipt(); admit() }
    /** The host's draw-pass receipt writes plain fields only (no snapshot state in draw); true when a queued transfer waits for [admit] on the next frame. */
    internal fun receipt(): Boolean { generation++; return registered.values.any { it.waiting } }
    internal fun admit() { clock.checkMutation(); casters.forEach { it.advance() } }
    internal var layout: LayoutCoordinates?=null; internal var view: android.view.View?=null; internal var revision by mutableIntStateOf(0)
}
/** U04.01a foundation (no production caller before U04.01b/U11.00, D03). The field has one [owner] plane: the destination from the moment its transfer starts, the source again only once a reversal settles there (D27). w is the continuous exclusion weight of [high] against 1−w of [low] (D27/D29): at most {low, high, pending}; A→B→A reverses w, a third plane waits for w to settle and for its draw receipt (D28). */
/** One return request, fenced to the actual host, source attachment and expansion gate.
 * Submission is acknowledged outside draw; it does not attest physical presentation. */
internal class OwnedFieldRequest(val plane: ShadowPlane,val sourceGeneration: Long,val expansion: ExpandCollapse?,val nativeGeneration: Long?) {
    var submitted by mutableStateOf(false); internal set
}
@Stable class SurfaceNode internal constructor(private val clock: UiClock,override val key: Any,override val shape: ShapeView,initial: SurfaceTarget,home: ShadowPlane): SurfaceView {
    private val px=requireNotNull(clock.spec.geometry) { "Publish measured ExpansionGeometryDomain through U11.00 before creating a surface" }.maxViewportDiagonalPx.also { require(home.sameClock(clock)) { "A surface and its home share one clock" } }; private var low by mutableStateOf<ShadowPlane?>(home); private var owner by mutableStateOf(home); private var high: ShadowPlane?=null; private var pending: Triple<ShadowPlane,Long,MotionProfileId>?=null; private var last: Any?=null; private var dismissed by mutableStateOf(false); private var retired by mutableStateOf(false); private var disposed=false
    override var stratum by mutableStateOf(initial.stratum); private set; internal var role by mutableStateOf(initial.shadow); private set
    private val ownOpacity=MotionFloat(clock,initial.opacity,1f,0.0..1.0,key to "opacity"); private var following by mutableStateOf<SurfacePhase?>(null)
    override val opacity: MotionValue get()=following?.opacity?:ownOpacity
    private val ownOwnership=MotionFloat(clock,0f,1f,0.0..1.0,key to "ownership");
    private var windowLeases=0; private var sourceReleased=false
    internal var fieldInheritsBodyScale by mutableStateOf(true)
    internal val windowRetained get()=windowLeases>0
    internal val sourcePlane get()=low
    // A native-only caster or a released source has no physical host to acknowledge its return.
    internal val sourceReturnPlane get()=low.takeIf { sourceGeneration>0L && !sourceReleased }
    internal var sourceGeneration=0L; private set
    private var fieldRequest by mutableStateOf<OwnedFieldRequest?>(null)
    internal fun requestOwnedField(plane: ShadowPlane): OwnedFieldRequest {
        clock.checkMutation()
        val expansion=following?.expansion
        // A reopened logical target can still be waiting on the held closing native gate.
        check(owns(plane) && plane===sourceReturnPlane && (expansion==null || expansion.nativeOpening==false))
        return OwnedFieldRequest(plane,sourceGeneration,expansion,expansion?.nativeGeneration).also { fieldRequest=it; plane.requestDraw() }
    }
    internal fun currentFieldRequest(request: OwnedFieldRequest): Boolean = fieldRequest===request && !sourceReleased &&
        sourceGeneration==request.sourceGeneration && sourceReturnPlane===request.plane && owns(request.plane) &&
        following?.expansion===request.expansion && request.expansion?.nativeGeneration==request.nativeGeneration &&
        (request.expansion==null || request.expansion.nativeOpening==false)
    /** Called only after this host actually draws the nonempty owned field. No snapshot writes in draw. */
    internal fun fieldSubmission(plane: ShadowPlane): (()->Unit)? {
        val request=fieldRequest?.takeIf { it.plane===plane && currentFieldRequest(it) && !it.submitted } ?: return null
        return { clock.checkMutation(); if(currentFieldRequest(request)) request.submitted=true }
    }
    internal var recording: CompositorRecording?=null
    private val projections=mutableMapOf<ShadowPlane,()->ShapeFrame>()
    internal fun frameOn(plane: ShadowPlane)=projections[plane]?.invoke()?:shape.outline()
    internal fun project(plane: ShadowPlane,frame: (()->ShapeFrame)?) { if(frame==null) projections.remove(plane) else projections[plane]=frame }
    internal fun attachSource(plane: ShadowPlane): Long { clock.checkMutation(); check(!disposed); require(plane.sameClock(clock)); check(sourceReleased || sourceGeneration==0L || low!==plane) { "A source key has one live attachment" }; fieldRequest=null; if(low!==plane) { low?.leave(this); low=plane; plane.enter(this); if(high==null) owner=plane }; sourceReleased=false; return ++sourceGeneration }
    internal fun releaseSource(generation: Long) { if(generation==sourceGeneration) dispose() }
    override val shadowOwnership: MotionValue get()=following?.ownership?:ownOwnership; private val ownTransform=MotionRect(clock,initial.transform.map(bounds()),EdgeRanges(px,px,px,px),key to "transform")
    override val transform: MotionRectView get()=following?.bounds?:ownTransform
    internal val registration=home.enter(this); internal val order get()=stratum.ordinal+registration/(registration+1f)
    override val retained get()=dismissed && !retired
    init { ownOwnership.onRest={ settle() }; ownOpacity.onRest={ if(dismissed && ownOpacity.target==0f) retire() } }
    private fun bounds(): Rect { var b=Rect.Zero; clock.observe { b=shape.outlineAt(clock.stamp).bounds }; return b } // Observing read: never marks the shape presented.
    private fun planes()=listOfNotNull(low,high,pending?.first).distinct()
    internal fun owns(plane: ShadowPlane)=!disposed && !retired && plane===owner; internal val waiting get()=pending!=null
    internal fun shareAt(plane: ShadowPlane,stamp: FrameStamp): Float { if(disposed || retired) return 0f; if(following!=null && high==null) return if(plane===owner) 1f else 0f; val w=shadowOwnership.valueAt(stamp); return (if(plane===high) w else 0f)+(if(plane===low) 1-w else 0f) }
    /** Equal target, profile, spec and access are idempotent; opacity and the presented rectangle join at one stamp. */
    fun request(target: SurfaceTarget,profile: MotionProfileId) {
        clock.checkMutation(); check(!disposed); check(following==null) { "A followed surface receives intents through its expansion" }; val data=clock.spec.motion[MotionProfile.valueOf(profile.name)] as? MotionProfileSpec?:throw IllegalArgumentException("Surface intents require a scalar profile"); val rect=target.transform.map(bounds()); val identity=listOf(target,rect,profile,data,clock.context?.access); if(identity==last) return
        val stamp=clock.stampNow; val edges=floatArrayOf(rect.left,rect.top,rect.right,rect.bottom); commitAtomic(listOf(ownOpacity to ownOpacity.prepare(target.opacity,profile,stamp,force=true)!!)+ownTransform.channels.mapIndexed { i,m -> m to m.prepare(edges[i],profile,stamp,force=true)!! })
        stratum=target.stratum; role=target.shadow; last=identity; if(dismissed && target.opacity>0f) revive()
    }
    /** D30 amendment: borrow the expansion's bound physical edges and analytic opacity; no second trajectory. */
    internal fun follow(expansion: ExpansionView,opacity: Curve,bounds: MotionRectView,ownership: Curve=Curve.linear(0f,1f)) {
        clock.checkMutation(); check(!disposed); val owner=requireNotNull(expansion as? ExpandCollapse)
        require(owner.ownerClock===clock && owner.owns(bounds)) { "Follow the same clock and expansion-bound rectangle" }
        val old=following; val same=old!=null && old.expansion===owner && old.bounds===bounds
        require(old==null || same || !old.expansion.expanded && old.expansion.retirementReady && this.owner===low && high==null && pending==null) { "A live surface keeps its expansion until its source return completes" }
        if(old!=null && !same) old.expansion.lifecycle.remove(::followLifetime)
        if(!same) { check(ownOpacity.settled && ownTransform.channels.all { it.settled } && ownOwnership.settled) { "Bind before starting standalone surface motion" }; following=SurfacePhase(owner,opacity,bounds,ownership); owner.lifecycle.add(::followLifetime) } else { old.curve=opacity; old.ownershipCurve=ownership }
        followLifetime(true)
    }
    private fun revive() { dismissed=false; if(retired) { retired=false; listOfNotNull(low,high).forEach { it.enter(this) } } }
    private fun followLifetime(intent: Boolean=false) { if(disposed) return; if(intent) fieldRequest=null; val f=following?:return; if(f.opacity.target>0f) { if(intent && dismissed) revive() } else if(dismissed && f.opacity.settled && f.opacity.target==0f) retire(); if(!retired) advance() }
    fun transfer(to: ShadowPlane,profile: MotionProfileId) {
        clock.checkMutation(); check(!disposed && !retired) { "A retired surface has no field to transfer" }; require(to.sameClock(clock)) { "Transfer hosts share the surface clock" }; require(clock.spec.motion[MotionProfile.valueOf(profile.name)] is MotionProfileSpec) { "Transfers require a scalar profile" }; val goal=ownOwnership.target; val towards=if(goal==1f) high else low; val away=if(goal==1f) low else high
        fieldRequest=null
        if(following!=null) {
            val queued=pending; if(queued!=null && queued.first!==to && queued.first!==low && queued.first!==high) queued.first.leave(this)
            pending=null
            if(to!==owner) { to.enter(this); pending=Triple(to,if(queued?.first===to) queued.second else to.generation,profile); to.requestDraw(); advance() }
            return
        }
        val queued=pending; pending=null; if(queued!=null && queued.first!==to && queued.first!==low && queued.first!==high) queued.first.leave(this)
        when { to===towards -> Unit; to===away -> ownOwnership.retarget(1f-goal,profile); else -> { to.enter(this); pending=Triple(to,if(queued?.first===to) queued.second else to.generation,profile); advance() } }
    }
    /** Starts the queued transfer once the pair is at rest and the destination drew after the request. */
    internal fun advance() { val (to,generation,profile)=pending?:return; val bound=following
        if(bound!=null) {
            if(!(bound.expansion.retirementReady || bound.expansion.awaitingNativeGate) || to.generation<=generation) return
            pending=null
            if(to===low) { owner=to; high?.leave(this); high=null }
            else { if(owner!==low) { low?.leave(this); low=owner }; high=to; owner=to }
            return
        }
        if(!ownOwnership.settled || to.generation<=generation) return; pending=null; owner=to
        if(ownOwnership.target==1f) { low=to; ownOwnership.retarget(0f,profile) } else { high=to; ownOwnership.retarget(1f,profile) }
    }
    private fun settle() { owner=(if(ownOwnership.target==1f) high else low)?:owner; if(ownOwnership.target==1f) { low?.takeIf { it!==pending?.first }?.leave(this); low=null } else { high?.takeIf { it!==pending?.first }?.leave(this); high=null }; advance() }
    /** Revokes logical presence at once (D30: no profile here); the node retires when the owner's opacity trajectory settles at 0. */
    fun dismiss() { clock.checkMutation(); check(!disposed); if(dismissed) return; dismissed=true; last=null; if(following!=null) { followLifetime() } else if(ownOpacity.settled && ownOpacity.target==0f) retire() }
    private fun retire() { planes().forEach { it.leave(this) }; pending=null; retired=true }
    /** A native presenter leases the actual trigger node through return; removing the source composition cannot revoke its field. */
    internal fun retainWindow(): ()->Unit { clock.checkMutation(); check(!disposed && !sourceReleased); windowLeases++; var released=false; return { clock.checkMutation(); if(!released) { released=true; windowLeases--; if(sourceReleased && windowLeases==0) disposeNow() } } }
    internal fun dispose() { clock.checkMutation(); if(disposed || sourceReleased) return; fieldRequest=null; sourceReleased=true; if(windowLeases==0) disposeNow() }
    private fun disposeNow() { recording?.dispose(); recording=null; projections.clear(); planes().forEach { it.leave(this) }; disposed=true; following?.expansion?.lifecycle?.remove(::followLifetime); listOf(ownOpacity,ownOwnership).forEach { it.dispose() }; ownTransform.dispose() }
}
/** Engine host adapter: one plane per native window; U04.01b installs it on its host with the draw receipt. */ internal val LocalShadowPlane=staticCompositionLocalOf<ShadowPlane?> { null }
@Composable internal fun rememberShadowPlane(): ShadowPlane { val clock=ownerClock(); val view=LocalView.current.rootView; return remember(clock,view) { ShadowPlane(clock,HostKey.next(view)) } }
@Composable fun rememberSurfaceNode(key: Any,shape: ShapeView,initial: SurfaceTarget): SurfaceNode { val clock=ownerClock(); val plane=requireNotNull(LocalShadowPlane.current) { "A surface registers on its host's ShadowPlane" }; val node=remember(clock,plane,key) { SurfaceNode(clock,key,shape,initial,plane) }; require(node.shape===shape) { "A surface keeps one persistent shape" }; DisposableEffect(node) { onDispose { node.dispose() } }; return node }
/** D31: one placement node; z-order is the stratum, then stable registration order within the host. */
fun Modifier.surface(node: SurfaceView): Modifier { require(node is SurfaceNode) { "Surfaces come from rememberSurfaceNode" }; return layout { m,c -> val p=m.measure(c); layout(p.width,p.height) { p.place(0,0,zIndex=node.order) } } }
/** D25/D28: the host draws its owned casters' field after its content, then publishes the draw receipt; queued transfers start on the next frame. */
@Composable internal fun Modifier.shadowPlaneHost(plane: ShadowPlane,strength: Float,spread: Float): Modifier { val cache=rememberGlassShadow(); val view=LocalView.current; SideEffect { plane.view=view }
    return onPlaced { plane.layout=it }.drawWithContent {
        drawContent(); plane.revision
        val fields=plane.casters.mapNotNull { node ->
            if(!node.owns(plane) || node.role==ShadowRole.None) null else {
                val opacity=node.opacity.value()*((node.shape as? LayoutShape)?.opacity?.invoke()?:1f)
                if(opacity>0f) node.frameOn(plane).bodies.takeIf { it.isNotEmpty() }?.let { node to it.map { body -> body to opacity } } else null
            }
        }
        with(cache) { draw(fields.flatMap { it.second },strength,spread) }
        fields.mapNotNull { it.first.fieldSubmission(plane) }.forEach { submit -> view.post { submit() } }
        if(plane.receipt()) view.postOnAnimation { plane.admit() }
    } }
/** The existing host layout provides its registry to descendant surface owners; the modifier also fits a page's existing layout. */
@Composable internal fun ShadowPlaneHost(plane: ShadowPlane,strength: Float,spread: Float,modifier: Modifier,content: @Composable androidx.compose.foundation.layout.BoxScope.()->Unit) {
    CompositionLocalProvider(LocalShadowPlane provides plane) { androidx.compose.foundation.layout.Box(modifier.shadowPlaneHost(plane,strength,spread),content=content) } }
/** Page host: the original constraints layout, with the field before the caller's existing inset/clip suffix. */
@Composable internal fun ShadowPlaneViewport(plane: ShadowPlane,strength: Float,spread: Float,modifier: Modifier,contentModifier: Modifier,insets: WindowInsets,
    content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.()->Unit) {
    CompositionLocalProvider(LocalShadowPlane provides plane) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.shadowPlaneHost(plane,strength,spread).then(contentModifier).windowInsetsPadding(insets),content=content)
    }
}
/** The stock rounded panel keeps one host layout; its field precedes the original content clip and inherits outer alpha. */
@Composable internal fun ShadowPlanePanel(modifier: Modifier,shape: RoundedCornerShape,shadow: Boolean,strength: Float,spread: Float,
    contentModifier: Modifier=Modifier,content: @Composable androidx.compose.foundation.layout.BoxScope.()->Unit) {
    val plane=if(shadow) rememberShadowPlane() else null
    val density=LocalDensity.current; val direction=LocalLayoutDirection.current; val key=remember { Any() }
    val field=if(plane==null) Modifier else Modifier.shadowPlaneHost(plane,strength,spread).glassCaster(plane,key) { rect ->
        when(val outline=shape.createOutline(rect.size,direction,density)) {
            is Outline.Rounded -> outline.roundRect.translate(rect.topLeft)
            is Outline.Rectangle -> RoundRect(rect,CornerRadius.Zero)
            is Outline.Generic -> error("A rounded stock panel has a rectangular or rounded outline")
        }
    }
    CompositionLocalProvider(LocalShadowPlane provides (plane?:LocalShadowPlane.current)) {
        androidx.compose.foundation.layout.Box(modifier.then(field).then(contentModifier),content=content)
    }
}
/** D26 live-compat caster (expires U06.01/U09.01a): an Engine-owned implicit node whose shape follows its layout in [plane]'s host; [body] maps that rectangle to the drawn body, or null. */
internal class LayoutShape(override val key: Any,internal var plane: ShadowPlane): ShapeView { var coordinates: LayoutCoordinates?=null; var body: (Rect)->RoundRect? = { null }; var opacity by mutableStateOf<()->Float>({ 1f })
    /** The laid-out body in host space, read in draw; not a motion read. */
    fun laidOut(): RoundRect? { val host=plane.layout?.takeIf { it.isAttached }; val own=coordinates?.takeIf { it.isAttached }; return if(host==null || own==null) null else body(host.localBoundingBoxOf(own,clipBounds=false)) }
    override fun outline(): ShapeFrame { val b=laidOut(); return ShapeFrame(listOfNotNull(b),b?.boundingRect?:Rect.Zero,b?.topLeftCornerRadius?.x?:0f,GlassStrain.None) }
    override fun outlineAt(stamp: FrameStamp)=outline() }
@Composable internal fun Modifier.glassCaster(plane: ShadowPlane,key: Any,opacity: ()->Float={ 1f },onNode: ((SurfaceView,Boolean)->Unit)?=null,
    retained: SurfaceView?=null,inheritsBodyScale: Boolean=false,body: (Rect)->RoundRect?): Modifier {
    val clock=ownerClock()
    val node=remember(clock,plane,key) {
        val existing=(retained as? SurfaceNode)?:plane.node(key)
        existing?:SurfaceNode(clock,key,LayoutShape(key,plane),SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),plane)
    }
    val shape=requireNotNull(node.shape as? LayoutShape)
    SideEffect { shape.plane=plane; shape.body=body; shape.opacity=opacity; node.fieldInheritsBodyScale=inheritsBodyScale }
    DisposableEffect(node,plane) {
        val generation=node.attachSource(plane)
        onNode?.invoke(node,true)
        onDispose { if(node.sourceGeneration==generation) onNode?.invoke(node,false); node.releaseSource(generation) }
    }
    return onGloballyPositioned { shape.coordinates=it; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { plane.revision++ } }
}
