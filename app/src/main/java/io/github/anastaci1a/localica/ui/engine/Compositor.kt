package io.github.anastaci1a.localica.ui.engine
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
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
    internal val casters: List<SurfaceNode> get()=registered.values.toList()
    internal fun enter(node: SurfaceNode): Int { clock.checkMutation(); require(registered[node.key].let { it==null || it===node }) { "One SurfaceNode per key per host" }; registered[node.key]=node; return ++registrations }
    internal fun leave(node: SurfaceNode) { clock.checkMutation(); if(registered[node.key]===node) registered.remove(node.key) }
    /** I3/D27: exactly one plane draws a caster's field, at strength × opacity; the others contribute 0. Never a partition: an alpha split (A(1−w), Aw) dips by A²w(1−w), and a strength split, exact before quantization, misses the drawn field by .011 at 16 rings (PlaneOracleTest). */
    internal fun contributionAt(node: SurfaceNode,stamp: FrameStamp)=if(node.owns(this)) node.opacity.valueAt(stamp) else 0f
    internal fun acknowledge() { clock.checkMutation(); receipt(); admit() }
    /** The host's draw-pass receipt writes plain fields only (no snapshot state in draw); true when a queued transfer waits for [admit] on the next frame. */
    internal fun receipt(): Boolean { generation++; return registered.values.any { it.waiting } }
    internal fun admit() { clock.checkMutation(); casters.forEach { it.advance() } }
    internal var layout: LayoutCoordinates?=null; internal var view: android.view.View?=null; internal var revision by mutableIntStateOf(0)
}
/** U04.01a foundation (no production caller before U04.01b/U11.00, D03). The field has one [owner] plane: the destination from the moment its transfer starts, the source again only once a reversal settles there (D27). w is the continuous exclusion weight of [high] against 1−w of [low] (D27/D29): at most {low, high, pending}; A→B→A reverses w, a third plane waits for w to settle and for its draw receipt (D28). */
@Stable class SurfaceNode internal constructor(private val clock: UiClock,override val key: Any,override val shape: ShapeView,initial: SurfaceTarget,home: ShadowPlane): SurfaceView {
    private val px=requireNotNull(clock.spec.geometry) { "Publish measured ExpansionGeometryDomain through U11.00 before creating a surface" }.maxViewportDiagonalPx; private var low: ShadowPlane?=home; private var owner: ShadowPlane=home; private var high: ShadowPlane?=null; private var pending: Triple<ShadowPlane,Long,MotionProfileId>?=null; private var last: Any?=null; private var dismissed=false; private var retired=false; private var disposed=false
    override var stratum by mutableStateOf(initial.stratum); private set; internal var role=initial.shadow; private set
    override val opacity=MotionFloat(clock,initial.opacity,1f,0.0..1.0,key to "opacity"); override val shadowOwnership=MotionFloat(clock,0f,1f,0.0..1.0,key to "ownership"); override val transform=MotionRect(clock,initial.transform.map(bounds()),EdgeRanges(px,px,px,px),key to "transform")
    internal val registration=home.enter(this); internal val order get()=stratum.ordinal+registration/(registration+1f)
    override val retained get()=dismissed && !retired
    init { shadowOwnership.onRest={ settle() }; opacity.onRest={ if(dismissed && opacity.target==0f) retire() } }
    private fun bounds(): Rect { var b=Rect.Zero; clock.observe { b=shape.outlineAt(clock.stamp).bounds }; return b } // Observing read: never marks the shape presented.
    private fun planes()=listOfNotNull(low,high,pending?.first).distinct()
    internal fun owns(plane: ShadowPlane)=!retired && plane===owner; internal val waiting get()=pending!=null
    internal fun shareAt(plane: ShadowPlane,stamp: FrameStamp): Float { if(retired) return 0f; val w=shadowOwnership.valueAt(stamp); return (if(plane===high) w else 0f)+(if(plane===low) 1-w else 0f) }
    /** Equal target, profile, spec and access are idempotent; opacity and the presented rectangle join at one stamp. */
    fun request(target: SurfaceTarget,profile: MotionProfileId) {
        clock.checkMutation(); check(!disposed); val data=clock.spec.motion[MotionProfile.valueOf(profile.name)] as? MotionProfileSpec?:throw IllegalArgumentException("Surface intents require a scalar profile"); val rect=target.transform.map(bounds()); val identity=listOf(target,rect,profile,data,clock.context?.access); if(identity==last) return
        val stamp=clock.stampNow; val edges=floatArrayOf(rect.left,rect.top,rect.right,rect.bottom); commitAtomic(listOf(opacity to opacity.prepare(target.opacity,profile,stamp,force=true)!!)+transform.channels.mapIndexed { i,m -> m to m.prepare(edges[i],profile,stamp,force=true)!! })
        stratum=target.stratum; role=target.shadow; last=identity; if(dismissed && target.opacity>0f) { dismissed=false; if(retired) { retired=false; listOfNotNull(low,high).forEach { it.enter(this) } } }
    }
    fun transfer(to: ShadowPlane,profile: MotionProfileId) {
        clock.checkMutation(); check(!disposed && !retired) { "A retired surface has no field to transfer" }; require(clock.spec.motion[MotionProfile.valueOf(profile.name)] is MotionProfileSpec) { "Transfers require a scalar profile" }; val goal=shadowOwnership.target; val towards=if(goal==1f) high else low; val away=if(goal==1f) low else high
        val queued=pending; pending=null; if(queued!=null && queued.first!==to && queued.first!==low && queued.first!==high) queued.first.leave(this)
        when { to===towards -> Unit; to===away -> shadowOwnership.retarget(1f-goal,profile); else -> { to.enter(this); pending=Triple(to,if(queued?.first===to) queued.second else to.generation,profile); advance() } }
    }
    /** Starts the queued transfer once the pair is at rest and the destination drew after the request. */
    internal fun advance() { val (to,generation,profile)=pending?:return; if(!shadowOwnership.settled || to.generation<=generation) return; pending=null; owner=to; if(shadowOwnership.target==1f) { low=to; shadowOwnership.retarget(0f,profile) } else { high=to; shadowOwnership.retarget(1f,profile) } }
    private fun settle() { owner=(if(shadowOwnership.target==1f) high else low)?:owner; if(shadowOwnership.target==1f) { low?.takeIf { it!==pending?.first }?.leave(this); low=null } else { high?.takeIf { it!==pending?.first }?.leave(this); high=null }; advance() }
    /** Revokes logical presence at once (D30: no profile here); the node retires when the owner's opacity trajectory settles at 0. */
    fun dismiss() { clock.checkMutation(); check(!disposed); if(dismissed) return; dismissed=true; last=null; if(opacity.settled && opacity.target==0f) retire() }
    private fun retire() { planes().forEach { it.leave(this) }; pending=null; retired=true }
    internal fun dispose() { clock.checkMutation(); if(disposed) return; planes().forEach { it.leave(this) }; disposed=true; listOf(opacity,shadowOwnership).forEach { it.dispose() }; transform.dispose() }
}
/** Engine host adapter: one plane per native window; U04.01b installs it on its host with the draw receipt. */ internal val LocalShadowPlane=staticCompositionLocalOf<ShadowPlane?> { null }
@Composable internal fun rememberShadowPlane(): ShadowPlane { val clock=ownerClock(); val view=LocalView.current.rootView; return remember(clock,view) { ShadowPlane(clock,HostKey.next(view)) } }
@Composable fun rememberSurfaceNode(key: Any,shape: ShapeView,initial: SurfaceTarget): SurfaceNode { val clock=ownerClock(); val plane=requireNotNull(LocalShadowPlane.current) { "A surface registers on its host's ShadowPlane" }; val node=remember(clock,plane,key) { SurfaceNode(clock,key,shape,initial,plane) }; require(node.shape===shape) { "A surface keeps one persistent shape" }; DisposableEffect(node) { onDispose { node.dispose() } }; return node }
/** D31: one placement node; z-order is the stratum, then stable registration order within the host. */
fun Modifier.surface(node: SurfaceView): Modifier { require(node is SurfaceNode) { "Surfaces come from rememberSurfaceNode" }; return layout { m,c -> val p=m.measure(c); layout(p.width,p.height) { p.place(0,0,zIndex=node.order) } } }
/** D25/D28: the host draws its owned casters' field after its content, then publishes the draw receipt; queued transfers start on the next frame. */
@Composable internal fun ShadowPlaneHost(plane: ShadowPlane,strength: Float,spread: Float,modifier: Modifier,content: @Composable androidx.compose.foundation.layout.BoxScope.()->Unit) { val cache=rememberGlassShadow(); val view=LocalView.current; SideEffect { plane.view=view }
    androidx.compose.foundation.layout.Box(modifier.onPlaced { plane.layout=it }.drawWithContent { drawContent(); plane.revision; with(cache) { draw(plane.casters.flatMap { n -> if(n.owns(plane) && n.opacity.value()>0f) n.shape.outline().bodies.map { it to n.opacity.value() } else emptyList() },strength,spread) }; if(plane.receipt()) view.postOnAnimation { plane.admit() } },content=content) }
/** D26 live-compat caster (expires U06.01/U09.01a): an Engine-owned implicit node whose shape follows its layout in [plane]'s host; [body] maps that rectangle to the drawn body, or null. */
internal class LayoutShape(override val key: Any,private val plane: ShadowPlane): ShapeView { var coordinates: LayoutCoordinates?=null; var body: (Rect)->RoundRect? = { null }
    /** The laid-out body in host space, read in draw; not a motion read. */
    fun laidOut(): RoundRect? { val host=plane.layout?.takeIf { it.isAttached }; val own=coordinates?.takeIf { it.isAttached }; return if(host==null || own==null) null else body(host.localBoundingBoxOf(own,clipBounds=false)) }
    override fun outline(): ShapeFrame { val b=laidOut(); return ShapeFrame(listOfNotNull(b),b?.boundingRect?:Rect.Zero,b?.topLeftCornerRadius?.x?:0f,GlassStrain.None) }
    override fun outlineAt(stamp: FrameStamp)=outline() }
@Composable internal fun Modifier.glassCaster(plane: ShadowPlane,key: Any,body: (Rect)->RoundRect?): Modifier { val clock=ownerClock(); val shape=remember(plane,key) { LayoutShape(key,plane) }; SideEffect { shape.body=body }
    val node=remember(clock,plane,key) { SurfaceNode(clock,key,shape,SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass),plane) }; DisposableEffect(node) { onDispose { node.dispose() } }
    return onGloballyPositioned { shape.coordinates=it; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { plane.revision++ } } }
