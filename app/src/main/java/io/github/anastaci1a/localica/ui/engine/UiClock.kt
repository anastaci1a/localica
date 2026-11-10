package io.github.anastaci1a.localica.ui.engine

import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import io.github.anastaci1a.localica.BuildConfig
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.channels.Channel

@Immutable class FrameStamp internal constructor(val nanos: Long,val sequence: Long) { fun secondsSince(other: FrameStamp)=(nanos-other.nanos)/1e9f }
/** [onFrame] runs inside the frame callback, so state it writes is read by that same frame's composition, layout and draw. */
interface FrameSource { suspend fun awaitFrame(): Long; suspend fun <R> awaitFrame(onFrame: (Long)->R): R=onFrame(awaitFrame()) }
internal interface ActiveMotion { val id: Long; val presented: Boolean; val settled: Boolean; val reducesWithAccess: Boolean get()=true; fun endsAt(stamp: FrameStamp): Boolean; fun finish(stamp: FrameStamp); fun reduce(stamp: FrameStamp) }
internal fun interface FrameObserver { fun onFrame(stamp: FrameStamp,active: List<ActiveMotion>) }
internal data class EngineContext(val displayIntervalSeconds: Float,val fullPlayerReferenceTravelPx: Float,val access: MotionAccess) { init { require(displayIntervalSeconds.isFinite() && displayIntervalSeconds>0 && (2*displayIntervalSeconds).isFinite() && 2e9*displayIntervalSeconds<=Long.MAX_VALUE.toDouble() && fullPlayerReferenceTravelPx.isFinite() && fullPlayerReferenceTravelPx>0) } }
internal val LocalUiClock=staticCompositionLocalOf<UiClock> { error("UiClock is provided by LocalicaTheme") }
internal val LocalClockOwner=staticCompositionLocalOf<UiClock?> { null }
internal val LocalEngineContext=staticCompositionLocalOf<EngineContext> { requireNotNull<EngineContext>(null) { "Publish real EngineContext through U11.00 before creating a motion owner" } }
@Stable class UiClock internal constructor(private val source: FrameSource,private val nowNanos: ()->Long=System::nanoTime,private val checkOwner: ()->Unit={ if(BuildConfig.DEBUG) check(Looper.myLooper()==Looper.getMainLooper()) { "Engine mutation requires Main" } }) {
    private var last=FrameStamp(nowNanos(),0); private val state=mutableStateOf(last); private val active=linkedSetOf<ActiveMotion>(); private val wake=Channel<Unit>(Channel.CONFLATED); private var observing=false; private var nextId=0L
    internal var context: EngineContext?=null; private set
    internal var spec: UiSpec=StockUiSpec; private set
    internal var events: MotionEvents?=null
    internal val observers=mutableListOf<FrameObserver>()
    init { checkOwner() }
    val stamp: FrameStamp get()=state.value
    val stampNow: FrameStamp get() { checkOwner(); return FrameStamp(maxOf(nowNanos(),last.nanos),last.sequence) }
    val activeCount: Int get()=active.size
    internal val presentedStamp: FrameStamp get()=last
    internal val isObserving: Boolean get()=observing
    internal fun checkMutation() { checkOwner(); check(!observing) { "An observer cannot drive presentation" } }
    internal fun allocateId(): Long { checkMutation(); return ++nextId }
    internal fun register(motion: ActiveMotion) { checkMutation(); if(active.add(motion)) wake.trySend(Unit) }
    internal fun unregister(motion: ActiveMotion) { checkMutation(); active.remove(motion) }
    internal fun observe(block: ()->Unit) { checkOwner(); check(!observing); observing=true; try { Snapshot.withoutReadObservation(block) } finally { observing=false } }
    internal fun configure(value: EngineContext,data: UiSpec) { checkMutation(); val reduced=context?.access!=MotionAccess.Reduced && value.access==MotionAccess.Reduced; context=value; spec=data; if(reduced) { val s=stampNow; while(true) { val motion=active.firstOrNull { it.reducesWithAccess }?:break; motion.reduce(s) } } }
    internal suspend fun run(): Nothing {
        checkMutation()
        try { while(true) {
            if(active.isEmpty()) { wake.receive(); continue }
            source.awaitFrame { nanos -> checkMutation(); check(nanos>last.nanos) { "Frame source must advance monotonically" }; last=FrameStamp(nanos,last.sequence+1); state.value=last
            // Finishing a motion may retire a surface or admit a queued transfer, changing other active owners.
            active.toList().forEach { motion -> if(motion in active && motion.endsAt(last)) { active.remove(motion); motion.finish(last) } }
            if(observers.isNotEmpty()) observe { val frame=active.toList(); observers.forEach { it.onFrame(last,frame) } } }
        } } finally { active.clear() }
    }
}
@Composable internal fun ProvideUiClock(content: @Composable ()->Unit) {
    val inherited=LocalClockOwner.current
    val base=remember { FrameBase() }
    val clock=inherited?:remember { UiClock(object: FrameSource { override suspend fun awaitFrame()=awaitFrame { it }
        override suspend fun <R> awaitFrame(onFrame: (Long)->R)=withFrameNanos { base.frame(it); onFrame(it) } },base::now) }
    if(inherited==null) LaunchedEffect(clock) { withFrameNanos { base.frame(it) }; clock.run() }
    CompositionLocalProvider(LocalClockOwner provides clock,LocalUiClock provides clock,content=content)
}
@Composable internal fun ownerClock(): UiClock {
    val context=LocalEngineContext.current; val clock=LocalUiClock.current; val spec=LocalUiSpec.current.spec; val domain=requireNotNull(spec.geometry) { "Publish measured ExpansionGeometryDomain through U11.00 before creating a motion owner" }; require(domain.referenceTravelPx==context.fullPlayerReferenceTravelPx && context.displayIntervalSeconds<=domain.maxDisplayIntervalSeconds) { "Engine context and admitted geometry must come from one root record" }
    if(clock.context==null) clock.configure(context,spec); SideEffect { clock.configure(context,spec) }; return clock
}
/** Intent time in the frame source's base: Choreographer frames use System.nanoTime; a virtual (test) frame clock stamps
 * intents at its last frame. Before the first frame nothing is presented, so no presented frame can precede the origin. */
internal class FrameBase(private val system: ()->Long=System::nanoTime) { private var offset: Long?=null; private var frame=Long.MIN_VALUE/4; private var latest=Long.MIN_VALUE/4
    fun frame(nanos: Long) { frame=nanos; offset=nanos-system() }
    fun now(): Long=maxOf(latest,offset?.let { if(kotlin.math.abs(it)<1_000_000_000L) system() else frame }?:latest).also { latest=it } }
