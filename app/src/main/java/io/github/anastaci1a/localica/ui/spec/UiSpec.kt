package io.github.anastaci1a.localica.ui.spec

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import java.util.Collections
import kotlin.math.*

/** Neutral extent for an absent inset; independent of configurable spacing. */
val EmptyInset: Dp = Dp.Hairline

enum class Space { Hairline, Tight, Compact, Base, Comfortable, Section, Panel }
enum class Radius { Artwork, Card, Menu, LargeArtwork, Panel, Pill }
enum class ControlSize { NavigationPill, Compact, MiniArtwork, CollapsedQueue, DragBubble, Icon, PreferenceRow, PreferenceCompact, SettingsIcon }
enum class Clearance { FloatingGap, LiftMax, LiftClearance, ShadowInset, BottomChrome }
enum class MotionProfile { Control, ControlOff, Selection, Hover, Reveal, ResolutionBlend, Floating, Heading, ArtworkLayout, CropRetention, Marquee, Scroll, SettingsHighlight, SettingsScroll, LyricEmphasis }
enum class SpringProfile { GlassMorph, Page, GlassMode, Strain }
enum class MaterialRole { Glass, Panel, Selection, DragPane, Page, Scrim }
enum class MotionAccess { Standard, Faster, Reduced }
enum class GlassMode { Normal, SingleToneBlur, FlatNone }
@Immutable data class EasingSpec(val x1: Float, val y1: Float, val x2: Float, val y2: Float) { val maxSlope: Float by lazy { slopeBound(this) } }
@Immutable sealed interface MotionSpec
@Immutable data class MotionProfileSpec(val durationMs: Int, val easing: EasingSpec) : MotionSpec
@Immutable data class MarqueeSpec(val pauseMs: Int, val fadeMs: Int, val returnMs: Int, val speedDpPerSecond: Float, val easing: EasingSpec) : MotionSpec
@Immutable data class SettingsScrollSpec(val normalMs: Int, val fasterMs: Int, val easing: EasingSpec) : MotionSpec
@Immutable data class SettingsHighlightSpec(val revealMs: Int, val holdMs: Int, val fadeMs: Int, val reducedHoldMs: Int, val opacity: Float, val easing: EasingSpec) : MotionSpec
@Immutable data class ListMotionSpec(val listMs: Int, val gridMs: Int, val fadeMs: Int, val easing: EasingSpec, val fadeEasing: EasingSpec) : MotionSpec
@Immutable data class SpringProfileSpec(val dampingRatio: Float, val stiffness: Float, val speedExponent: Int = 0)
@Immutable data class ExpansionGeometryDomain(val referenceTravelPx: Float,val maxViewportDiagonalPx: Float,val maxDisplayIntervalSeconds: Float) { /** Largest admitted [expansionTravel] on this device; [referenceTravelPx] is the full player's travel in the same measure (#846). */ val maxTravel: Double get()=expansionTravelBound(maxViewportDiagonalPx.toDouble()); init { require(referenceTravelPx.isFinite() && referenceTravelPx>0 && maxViewportDiagonalPx.isFinite() && maxViewportDiagonalPx>=1 && maxDisplayIntervalSeconds.isFinite() && maxDisplayIntervalSeconds>0) } }
@Immutable data class ExpansionSpec(val core: EasingSpec, val referenceDurationMs: Float, val positionFloor: Float, val velocityKnee: Float, val velocityExponent: Float, val sizeExponent: Float, val floorMs: Float, val deadBand: Float, val stationaryOpenAt: Float, val band: Float)
@Immutable data class MaterialTokens(val blur: Float, val whiteTint: Float, val shadowStrength: Float, val shadowSpread: Float, val contrast: Float, val liftShadowStrength: Float, val shapeDamping: Float, val depth: Float, val mode: GlassMode)
@Immutable class UiSpec internal constructor(val space: Map<Space,Dp>, val radius: Map<Radius,Dp>, val control: Map<ControlSize,Dp>, val clearance: Map<Clearance,Dp>, val motion: Map<MotionProfile,MotionSpec>, val spring: Map<SpringProfile,SpringProfileSpec>, val expansion: ExpansionSpec, val material: Map<MaterialRole,MaterialTokens>, val access: MotionAccess, internal val geometry: ExpansionGeometryDomain?=null, val sheetExpansion: ExpansionSpec=expansion, val lyricsExpansion: ExpansionSpec=expansion)
@Immutable data class UiSpecData(val space: Map<Space,Dp>? = null, val radius: Map<Radius,Dp>? = null, val control: Map<ControlSize,Dp>? = null, val clearance: Map<Clearance,Dp>? = null, val motion: Map<MotionProfile,MotionSpec>? = null, val spring: Map<SpringProfile,SpringProfileSpec>? = null, val expansion: ExpansionSpec? = null, val material: Map<MaterialRole,MaterialTokens>? = null, val access: MotionAccess? = null, val sheetExpansion: ExpansionSpec? = null, val lyricsExpansion: ExpansionSpec? = null)
sealed interface SpecResult { data class Accepted(val spec: UiSpec) : SpecResult; data class Rejected(val reasons: List<String>) : SpecResult }
const val SettleThreshold = .0003f
const val SettleVelocityThreshold = .003f
const val vMax = 10f
internal const val MinimumContactBand = 1.1920928955078125e-7f
internal const val StrainMotionSpan = .24f
internal const val StrainPressureSpan = .34f
internal const val StrainDepthSpan = .48f
/** #846 (operator 2026-11-11): the ONE expansion duration-travel measure, timing only, never the motion path. It is van Wijk and Nuij's
 * 2003 optimal zoom-and-pan path length S (dimensionless): w = sqrt(width x height), each side at least 1 px; u = center drift; rho = sqrt 2.
 * Size change counts as a log ratio and drift relative to size. Switching measures is this one function; ExpansionPeakVelocityTest pins it. */
internal const val TravelRho = 1.4142135623730951
internal fun expansionTravel(start: DoubleArray, end: DoubleArray): Double {
    fun scale(r: DoubleArray) = sqrt(max(r[2]-r[0], 1.0)*max(r[3]-r[1], 1.0))
    val w0 = scale(start); val w1 = scale(end); val u = hypot((end[0]+end[2]-start[0]-start[2])/2, (end[1]+end[3]-start[1]-start[3])/2); val r2 = TravelRho*TravelRho
    if (u == 0.0) return abs(ln(w1/w0))/TravelRho
    // r_i = ln(-b_i + sqrt(b_i^2 + 1)) = -asinh(b_i); the asinh form stays exact as u -> 0.
    val b0 = (w1*w1-w0*w0+r2*r2*u*u)/(2*w0*r2*u); val b1 = (w1*w1-w0*w0-r2*r2*u*u)/(2*w1*r2*u)
    return abs(asinh(b0)-asinh(b1))/TravelRho
}
/** Bound of [expansionTravel] between rectangles with sides of 1 px..[diagonal] and centers within [diagonal] of a common point: S is a
 * hyperbolic distance, convex in each coordinate, so twice the largest distance from a 1 px point to the domain's corners bounds it. */
internal fun expansionTravelBound(diagonal: Double): Double {
    fun from(u: Double, w: Double) = expansionTravel(doubleArrayOf(-.5, -.5, .5, .5), doubleArrayOf(u-w/2, -w/2, u+w/2, w/2))
    return 2*maxOf(from(diagonal, 1.0), from(diagonal, diagonal), from(0.0, diagonal))
}
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
internal fun easingExtrema(e: EasingSpec): ClosedFloatingPointRange<Double> {
    val a=e.y1.toDouble(); val b=e.y2.toDouble(); val points=listOf(0.0,1.0)+quadraticRoots(3*(1+3*a-3*b),6*(b-2*a),3*a).filter { it>0 && it<1 }; val values=points.map { t -> val u=1-t; 3*u*u*t*a+3*u*t*t*b+t*t*t }; return values.min()..values.max()
}
private val slow = EasingSpec(0f,0f,.2f,1f)
private val fast = EasingSpec(.4f,0f,.2f,1f)
private val linear = EasingSpec(0f,0f,1f,1f)
private fun <K,V> frozen(base: Map<K,V>, overlay: Map<K,V>?) = Collections.unmodifiableMap(LinkedHashMap(base).apply { if(overlay!=null) putAll(overlay) })
private fun <K> dimensions(keys: List<K>, values: List<Float>) = frozen(keys.zip(values.map { it.dp }).toMap(),null)
/** Sheets (player, Queue) keep v0.33.21's 300 ms tween curve and peak speed: its (.22,0,.12,1) easing is the law core at every travel (size exponent 0), never slower than before (#846). */
private val StockSheetExpansion = ExpansionSpec(EasingSpec(.22f,0f,.12f,1f),300f,.500401615f,1.41952128f,.73607278f,0f,300f,.25f,.5f,.05f)
/** The player lyrics presence keeps v0.33.21's 412.5 ms FastOutSlowIn layout tween (motionDuration(550)) and its peak speed (#846). */
private val StockLyricsExpansion = ExpansionSpec(EasingSpec(.4f,0f,.2f,1f),412.5f,.500401615f,1.41952128f,.73607278f,0f,412.5f,.25f,.5f,.05f)
val StockUiSpec: UiSpec = UiSpec(dimensions(Space.entries,listOf(1f,4f,8f,12f,16f,24f,28f)), dimensions(Radius.entries.dropLast(1),listOf(8f,16f,20f,24f,28f)), dimensions(ControlSize.entries,listOf(64f,51.2f,32f,64f,28f,24f,56f,48f,24f)), dimensions(Clearance.entries,listOf(8f,4f,1f,22.8f,80f)),
    frozen<MotionProfile,MotionSpec>(MotionProfile.entries.take(10).zip(listOf(180,270,220,220,300,240,300,165,300,225).mapIndexed { i,d -> MotionProfileSpec(d,if(i in listOf(0,1,2,3,6)) slow else fast) }).toMap()+mapOf(MotionProfile.LyricEmphasis to MotionProfileSpec(350,fast),MotionProfile.Marquee to MarqueeSpec(1800,220,260,30f,EasingSpec(1f/3,0f,2f/3,1f)),MotionProfile.Scroll to ListMotionSpec(350,500,120,fast,linear),MotionProfile.SettingsScroll to SettingsScrollSpec(262,175,fast),MotionProfile.SettingsHighlight to SettingsHighlightSpec(150,450,600,1200,.18f,fast)),null),
    frozen(mapOf(SpringProfile.GlassMorph to SpringProfileSpec(1f,90f,2),SpringProfile.Page to SpringProfileSpec(.85f,380f),SpringProfile.GlassMode to SpringProfileSpec(1f,90f,2),SpringProfile.Strain to SpringProfileSpec(8f/(2*sqrt(380f)),380f,2)),null), ExpansionSpec(EasingSpec(.229f,.19f,.21f,1f),415.3104f,.500401615f,1.41952128f,.73607278f,.5f,140f,.25f,.5f,.05f), frozen(MaterialRole.entries.associateWith { MaterialTokens(.2f,.06f,.12f,.6f,.25f,1f,8f,1.35f,GlassMode.Normal) },null),MotionAccess.Standard,null,StockSheetExpansion,StockLyricsExpansion)
fun uiSpec(settings: InterfaceSettings, overrides: UiSpecData? = null, geometry: ExpansionGeometryDomain? = null): SpecResult {
    val o=overrides?:UiSpecData(); val b=StockUiSpec; val g=settings.glass.normalized(); val errors=mutableListOf<String>()
    fun valid(ok: Boolean, key: String) { if(!ok) errors+=key }; fun bounded(v: Float,lo: Float,hi: Float)=v.isFinite() && v in lo..hi
    val rendererMode = GlassMode.valueOf(g.renderer.name)
    val space=frozen(b.space,o.space); val radius=frozen(b.radius,o.radius); val control=frozen(b.control,o.control); val material=frozen(MaterialRole.entries.associateWith { MaterialTokens(g.blur,g.whiteTint,g.shadowStrength,g.shadowSpread,g.contrast,g.liftShadowStrength,g.shapeDamping,g.depth,rendererMode) },o.material)
    val clearance=frozen(b.clearance+mapOf(Clearance.ShadowInset to (6+28*material.getValue(MaterialRole.Glass).shadowSpread).dp),o.clearance); val motion=frozen(b.motion,o.motion); val spring=frozen(b.spring+mapOf(SpringProfile.Strain to b.spring.getValue(SpringProfile.Strain).copy(dampingRatio=g.shapeDamping/(2*sqrt(380f)))),o.spring); val expansion=o.expansion?:b.expansion; val sheetExpansion=o.sheetExpansion?:b.sheetExpansion; val lyricsExpansion=o.lyricsExpansion?:b.lyricsExpansion
    valid(space.values.all { bounded(it.value,0f,Float.MAX_VALUE) } && Space.entries.zipWithNext().all { (a,b)->space.getValue(a)<space.getValue(b) },"space"); valid(Radius.Pill !in radius && radius.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"radius"); valid(control.values.all { it.value.isFinite() && it.value>0 },"control"); valid(clearance.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"clearance")
    fun ease(e: EasingSpec)=bounded(e.x1,0f,1f) && bounded(e.x2,0f,1f) && e.y1.isFinite() && e.y2.isFinite() && e.maxSlope.isFinite()
    fun duration(d: Int)=d in 16..2000
    for((key,m) in motion) valid(when(m) { is MotionProfileSpec -> key !in setOf(MotionProfile.Marquee,MotionProfile.Scroll,MotionProfile.SettingsHighlight,MotionProfile.SettingsScroll) && duration(m.durationMs) && ease(m.easing); is MarqueeSpec -> key==MotionProfile.Marquee && m.pauseMs in 0..2000 && duration(m.fadeMs) && duration(m.returnMs) && m.speedDpPerSecond.isFinite() && m.speedDpPerSecond>0 && ease(m.easing); is SettingsScrollSpec -> key==MotionProfile.SettingsScroll && duration(m.normalMs) && duration(m.fasterMs) && ease(m.easing); is SettingsHighlightSpec -> key==MotionProfile.SettingsHighlight && duration(m.revealMs) && m.holdMs in 0..2000 && duration(m.fadeMs) && m.reducedHoldMs in 0..2000 && bounded(m.opacity,0f,1f) && ease(m.easing) && m.easing.x1>0f && m.easing.x2<1f && m.easing.y1==0f && m.easing.y2==1f && easingExtrema(m.easing).let { it.start>=0.0 && it.endInclusive<=1.0 }; is ListMotionSpec -> key==MotionProfile.Scroll && duration(m.listMs) && duration(m.gridMs) && duration(m.fadeMs) && ease(m.easing) && ease(m.fadeEasing) },"motion.$key")
    for((key,s) in spring) valid(s.dampingRatio.isFinite() && s.dampingRatio>0 && bounded(s.stiffness,1f,10000f) && s.speedExponent in setOf(0,2) && (key==SpringProfile.Strain || springHorizon(s)<=2),"spring.$key")
    for((key,law) in listOf("expansion" to expansion,"sheetExpansion" to sheetExpansion,"lyricsExpansion" to lyricsExpansion)) with(law) { valid(finiteExpansionFinish(this,geometry?.let { it.maxTravel/it.referenceTravelPx } ?: (sqrt(2.0)*Int.MAX_VALUE/Float.MIN_VALUE.toDouble()),geometry?.maxDisplayIntervalSeconds?.toDouble() ?: (Long.MAX_VALUE/1e9),geometry!=null),"$key.duration exceeds finite device geometry domain; provide measured geometry for a narrower admission domain"); valid(bounded(band,MinimumContactBand,.1f),"$key.band requires at least ulp(1f) contact room"); valid(ease(core) && easingExtrema(core).let { it.start>=0.0 && it.endInclusive<=1.0+band/2 },"$key.contact core extrema require minimum >= 0 and maximum <= 1 + band/2"); valid(ease(core) && easingExtrema(core).let { it.start>=-band.toDouble() && it.endInclusive<=(1f+band).toDouble() } && bounded(referenceDurationMs,Float.MIN_VALUE,2000f) && bounded(floorMs,Float.MIN_VALUE,2000f) && bounded(positionFloor,Float.MIN_VALUE,1f) && velocityKnee.isFinite() && velocityKnee>0 && velocityExponent.isFinite() && velocityExponent>0 && sizeExponent.isFinite() && sizeExponent>=0 && deadBand.isFinite() && deadBand>=0 && bounded(stationaryOpenAt,0f,1f) && bounded(band,0f,.1f),key) }
    for((key,m) in material) with(m) { valid(listOf(blur,whiteTint,shadowStrength,shadowSpread).all { bounded(it,0f,1f) } && bounded(contrast,0f,.5f) && bounded(liftShadowStrength,.5f,1.5f) && bounded(shapeDamping,2f,40f) && bounded(depth,.6f,2f),"material.$key") }
    return if(errors.isNotEmpty()) SpecResult.Rejected(errors.toList()) else SpecResult.Accepted(UiSpec(space,radius,control,clearance,motion,spring,expansion,material,if(settings.reducedMotion || o.access==MotionAccess.Reduced) MotionAccess.Reduced else o.access?:if(settings.fasterMotion) MotionAccess.Faster else MotionAccess.Standard,geometry,sheetExpansion,lyricsExpansion))
}
/** U11.00 root ingress: a rejected payload falls back to the stock catalog with the same access and measured geometry. */
fun uiSpec(settings: InterfaceSettings, geometry: ExpansionGeometryDomain, density: Density): ResolvedUiSpec = ResolvedUiSpec(((uiSpec(settings, null, geometry) as? SpecResult.Accepted)
    ?: uiSpec(InterfaceSettings(reducedMotion = settings.reducedMotion, fasterMotion = settings.fasterMotion), null, geometry) as SpecResult.Accepted).spec, density)
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
