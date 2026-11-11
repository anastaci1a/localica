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
enum class MotionAccess { Standard, Reduced }
enum class GlassMode { Normal, SingleToneBlur, FlatNone }
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
enum class PopupVariant { Base, CrossAxisSnap, Auto, Legacy }
enum class PopupDirection { Open, Close, Gate }
/** #854/#855 measured Apple popup motion. Every popup trajectory, test reference and admission bound derives from this.
 * Laws are read only through [law], so a revision can make them per kind without touching callers. */
@Immutable data class PopupMotionModel(val revision: String, val open: SpringLaw, val close: SpringLaw, val crossOpen: SpringLaw, val crossClose: SpringLaw, val gate: SpringLaw,
    val snapAspect: Float, val preparedRamp: Float, val rampBlend: Float, val variants: Map<PopupKind,PopupVariant>) {
    fun variant(kind: PopupKind): PopupVariant = variants[kind] ?: PopupVariant.Base
    /** One channel's law: the main axis by [direction]; [cross] channels use the snap laws (a cross-axis gate approach uses the cross close law). */
    @Suppress("UNUSED_PARAMETER") fun law(kind: PopupKind, direction: PopupDirection, cross: Boolean): SpringLaw =
        if(cross) { if(direction==PopupDirection.Open) crossOpen else crossClose } else when(direction) { PopupDirection.Open -> open; PopupDirection.Close -> close; PopupDirection.Gate -> gate }
}
/** Provenance of [StockPopupMotion]: revision 855-ios262-60fps-interim (feedback/issues/855-apple-popup-motion/pass2/INTERIM-MODEL.md,
 * lead swap 2026-11-11): iOS 26.2 60 fps menu fits open (28, .78), close (20, 1) with the gate = close; cross (120, 1) unchanged from
 * pass 1 (not yet measured in 26.2); dialogs use the open law. The 10–13 % bar-button far-edge bounce is deliberately not reproduced
 * (it would leave the .05 band). Supersedes 855-wwdc25-30fps and the lead close-omega-24 ruling. T1 pins the model to this record. */
internal val StockPopupMotionRecord: Map<String,List<Float>> = mapOf("open" to listOf(28f,.78f), "close" to listOf(20f,1f), "crossOpen" to listOf(120f,1f),
    "crossClose" to listOf(120f,1f), "gate" to listOf(20f,1f), "snapAspect" to listOf(1.4f), "preparedRamp" to listOf(.25f,.05f))
internal const val StockPopupMotionRevision = "855-ios262-60fps-interim"
internal val StockPopupMotion = PopupMotionModel(StockPopupMotionRevision, SpringLaw(28f,.78f), SpringLaw(20f,1f), SpringLaw(120f,1f), SpringLaw(120f,1f), SpringLaw(20f,1f), 1.4f, .25f, .05f,
    mapOf(PopupKind.Menu to PopupVariant.Base, PopupKind.RowMenu to PopupVariant.Base, PopupKind.BottomMenu to PopupVariant.CrossAxisSnap, PopupKind.Dialog to PopupVariant.Base,
        PopupKind.SelectionPill to PopupVariant.Base, PopupKind.Legacy to PopupVariant.Legacy))
/** #860: the slowest and fastest animation speed. Every engine duration divides by the speed and every spring omega multiplies by it. */
const val MinimumAnimationSpeed = .5f
const val MaximumAnimationSpeed = 1.5f
@Immutable data class MaterialTokens(val blur: Float, val whiteTint: Float, val shadowStrength: Float, val shadowSpread: Float, val contrast: Float, val liftShadowStrength: Float, val shapeDamping: Float, val depth: Float, val mode: GlassMode)
@Immutable class UiSpec internal constructor(val space: Map<Space,Dp>, val radius: Map<Radius,Dp>, val control: Map<ControlSize,Dp>, val clearance: Map<Clearance,Dp>, val motion: Map<MotionProfile,MotionSpec>, val spring: Map<SpringProfile,SpringProfileSpec>, val expansion: ExpansionSpec, val material: Map<MaterialRole,MaterialTokens>, val access: MotionAccess, internal val geometry: ExpansionGeometryDomain?=null, val sheetExpansion: ExpansionSpec=expansion, val lyricsExpansion: ExpansionSpec=expansion, val popupMotion: PopupMotionModel=StockPopupMotion, val speed: Float=1f)
@Immutable data class UiSpecData(val space: Map<Space,Dp>? = null, val radius: Map<Radius,Dp>? = null, val control: Map<ControlSize,Dp>? = null, val clearance: Map<Clearance,Dp>? = null, val motion: Map<MotionProfile,MotionSpec>? = null, val spring: Map<SpringProfile,SpringProfileSpec>? = null, val expansion: ExpansionSpec? = null, val material: Map<MaterialRole,MaterialTokens>? = null, val access: MotionAccess? = null, val sheetExpansion: ExpansionSpec? = null, val lyricsExpansion: ExpansionSpec? = null, val popupMotion: PopupMotionModel? = null, val speed: Float? = null)
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
/** v0.33.21 popups ran glassMorphSpring(4/3): critically damped, stiffness 90(4/3)^2, ending at its .0001 visibility threshold,
 * (1+a)e^(-a) = 1e-4. Size independent (size exponent 0); Faster's 2/3 time scale is its speed 2 (#846). */
internal const val PopupSpringSpan = 11.756371f
internal val PopupSpringMs = (PopupSpringSpan/(sqrt(90.0)*4/3)*1000).toFloat()
internal const val StockPopupExpansionBand = .05f
private val StockPopupExpansion = ExpansionSpec(SpringCurveSpec(PopupSpringSpan),PopupSpringMs,.500401615f,1.41952128f,.73607278f,0f,PopupSpringMs,.25f,.5f,StockPopupExpansionBand)
val StockUiSpec: UiSpec = UiSpec(dimensions(Space.entries,listOf(1f,4f,8f,12f,16f,24f,28f)), dimensions(Radius.entries.dropLast(1),listOf(8f,16f,20f,24f,28f)), dimensions(ControlSize.entries,listOf(64f,51.2f,32f,64f,28f,24f,56f,48f,24f)), dimensions(Clearance.entries,listOf(8f,4f,1f,22.8f,80f)),
    frozen<MotionProfile,MotionSpec>(MotionProfile.entries.take(10).zip(listOf(180,270,220,220,300,240,300,165,300,225).mapIndexed { i,d -> MotionProfileSpec(d,if(i in listOf(0,1,2,3,6)) slow else fast) }).toMap()+mapOf(MotionProfile.LyricEmphasis to MotionProfileSpec(350,fast),MotionProfile.Marquee to MarqueeSpec(1800,220,260,30f,EasingSpec(1f/3,0f,2f/3,1f)),MotionProfile.Scroll to ListMotionSpec(350,500,120,fast,linear),MotionProfile.SettingsScroll to SettingsScrollSpec(262,175,fast),MotionProfile.SettingsHighlight to SettingsHighlightSpec(150,450,600,1200,.18f,fast)),null),
    frozen(mapOf(SpringProfile.GlassMorph to SpringProfileSpec(1f,90f,2),SpringProfile.Page to SpringProfileSpec(.85f,380f),SpringProfile.GlassMode to SpringProfileSpec(1f,90f,2),SpringProfile.Strain to SpringProfileSpec(8f/(2*sqrt(380f)),380f,2)),null), StockPopupExpansion, frozen(MaterialRole.entries.associateWith { MaterialTokens(.2f,.06f,.12f,.6f,.25f,1f,8f,1.35f,GlassMode.Normal) },null),MotionAccess.Standard,null,StockSheetExpansion,StockLyricsExpansion)
fun uiSpec(settings: InterfaceSettings, overrides: UiSpecData? = null, geometry: ExpansionGeometryDomain? = null): SpecResult {
    val o=overrides?:UiSpecData(); val b=StockUiSpec; val g=settings.glass.normalized(); val errors=mutableListOf<String>()
    fun valid(ok: Boolean, key: String) { if(!ok) errors+=key }; fun bounded(v: Float,lo: Float,hi: Float)=v.isFinite() && v in lo..hi
    val rendererMode = GlassMode.valueOf(g.renderer.name)
    val space=frozen(b.space,o.space); val radius=frozen(b.radius,o.radius); val control=frozen(b.control,o.control); val material=frozen(MaterialRole.entries.associateWith { MaterialTokens(g.blur,g.whiteTint,g.shadowStrength,g.shadowSpread,g.contrast,g.liftShadowStrength,g.shapeDamping,g.depth,rendererMode) },o.material)
    val clearance=frozen(b.clearance+mapOf(Clearance.ShadowInset to (6+28*material.getValue(MaterialRole.Glass).shadowSpread).dp),o.clearance); val motion=frozen(b.motion,o.motion); val spring=frozen(b.spring+mapOf(SpringProfile.Strain to b.spring.getValue(SpringProfile.Strain).copy(dampingRatio=g.shapeDamping/(2*sqrt(380f)))),o.spring); val expansion=o.expansion?:b.expansion; val sheetExpansion=o.sheetExpansion?:b.sheetExpansion; val lyricsExpansion=o.lyricsExpansion?:b.lyricsExpansion; val popupMotion=o.popupMotion?:b.popupMotion; val speed=o.speed?:settings.animationSpeed
    valid(space.values.all { bounded(it.value,0f,Float.MAX_VALUE) } && Space.entries.zipWithNext().all { (a,b)->space.getValue(a)<space.getValue(b) },"space"); valid(Radius.Pill !in radius && radius.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"radius"); valid(control.values.all { it.value.isFinite() && it.value>0 },"control"); valid(clearance.values.all { bounded(it.value,0f,Float.MAX_VALUE) },"clearance")
    fun ease(e: EasingSpec)=bounded(e.x1,0f,1f) && bounded(e.x2,0f,1f) && e.y1.isFinite() && e.y2.isFinite() && e.maxSlope.isFinite()
    fun curve(c: UnitCurveSpec)=when(c) { is EasingSpec -> ease(c); is SpringCurveSpec -> bounded(c.span,1f,40f) && c.maxSlope.isFinite() }
    fun duration(d: Int)=d in 16..2000
    for((key,m) in motion) valid(when(m) { is MotionProfileSpec -> key !in setOf(MotionProfile.Marquee,MotionProfile.Scroll,MotionProfile.SettingsHighlight,MotionProfile.SettingsScroll) && duration(m.durationMs) && ease(m.easing); is MarqueeSpec -> key==MotionProfile.Marquee && m.pauseMs in 0..2000 && duration(m.fadeMs) && duration(m.returnMs) && m.speedDpPerSecond.isFinite() && m.speedDpPerSecond>0 && ease(m.easing); is SettingsScrollSpec -> key==MotionProfile.SettingsScroll && duration(m.normalMs) && duration(m.fasterMs) && ease(m.easing); is SettingsHighlightSpec -> key==MotionProfile.SettingsHighlight && duration(m.revealMs) && m.holdMs in 0..2000 && duration(m.fadeMs) && m.reducedHoldMs in 0..2000 && bounded(m.opacity,0f,1f) && ease(m.easing) && m.easing.x1>0f && m.easing.x2<1f && m.easing.y1==0f && m.easing.y2==1f && easingExtrema(m.easing).let { it.start>=0.0 && it.endInclusive<=1.0 }; is ListMotionSpec -> key==MotionProfile.Scroll && duration(m.listMs) && duration(m.gridMs) && duration(m.fadeMs) && ease(m.easing) && ease(m.fadeEasing) },"motion.$key")
    for((key,s) in spring) valid(s.dampingRatio.isFinite() && s.dampingRatio>0 && bounded(s.stiffness,1f,10000f) && s.speedExponent in setOf(0,2) && (key==SpringProfile.Strain || springHorizon(s)<=2),"spring.$key")
    for((key,law) in listOf("expansion" to expansion,"sheetExpansion" to sheetExpansion,"lyricsExpansion" to lyricsExpansion)) with(law) { valid(finiteExpansionFinish(this,geometry?.let { it.maxViewportDiagonalPx.toDouble()/it.referenceTravelPx } ?: (sqrt(2.0)*Int.MAX_VALUE/Float.MIN_VALUE.toDouble()),geometry?.maxDisplayIntervalSeconds?.toDouble() ?: (Long.MAX_VALUE/1e9),geometry!=null),"$key.duration exceeds finite device geometry domain; provide measured geometry for a narrower admission domain"); valid(bounded(band,MinimumContactBand,.1f),"$key.band requires at least ulp(1f) contact room"); valid(curve(core) && curveExtrema(core).let { it.start>=0.0 && it.endInclusive<=1.0+band/2 },"$key.contact core extrema require minimum >= 0 and maximum <= 1 + band/2"); valid(curve(core) && curveExtrema(core).let { it.start>=-band.toDouble() && it.endInclusive<=(1f+band).toDouble() } && bounded(referenceDurationMs,Float.MIN_VALUE,2000f) && bounded(floorMs,Float.MIN_VALUE,2000f) && bounded(positionFloor,Float.MIN_VALUE,1f) && velocityKnee.isFinite() && velocityKnee>0 && velocityExponent.isFinite() && velocityExponent>0 && sizeExponent.isFinite() && sizeExponent>=0 && deadBand.isFinite() && deadBand>=0 && bounded(stationaryOpenAt,0f,1f) && bounded(band,0f,.1f),key) }
    valid(speed.isFinite() && speed in MinimumAnimationSpeed..MaximumAnimationSpeed,"speed")
    for(reason in popupMotionAdmission(popupMotion)) errors+="popupMotion.$reason"
    for((key,m) in material) with(m) { valid(listOf(blur,whiteTint,shadowStrength,shadowSpread).all { bounded(it,0f,1f) } && bounded(contrast,0f,.5f) && bounded(liftShadowStrength,.5f,1.5f) && bounded(shapeDamping,2f,40f) && bounded(depth,.6f,2f),"material.$key") }
    return if(errors.isNotEmpty()) SpecResult.Rejected(errors.toList()) else SpecResult.Accepted(UiSpec(space,radius,control,clearance,motion,spring,expansion,material,if(settings.reducedMotion || o.access==MotionAccess.Reduced) MotionAccess.Reduced else o.access?:MotionAccess.Standard,geometry,sheetExpansion,lyricsExpansion,popupMotion,speed))
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
internal fun popupMotionAdmission(m: PopupMotionModel, band: Float = StockPopupExpansionBand): List<String> {
    val errors=mutableListOf<String>()
    for((key,law) in listOf("open" to m.open,"close" to m.close,"crossOpen" to m.crossOpen,"crossClose" to m.crossClose,"gate" to m.gate)) {
        if(!(law.omega.isFinite() && law.zeta.isFinite() && law.omega in 1f..400f && law.zeta in .5f..4f)) { errors+=key; continue }
        if(key!="open" && law.zeta<1f) errors+="$key.overshoot"
        if(key=="open" && springOvershoot(law.zeta.toDouble())>band) errors+="open.overshoot"
        if(popupLawHorizon(law,MinimumAnimationSpeed.toDouble())>2.0) errors+="$key.horizon"
    }
    if(!(m.snapAspect.isFinite() && m.snapAspect>1f)) errors+="snapAspect"
    if(!(m.preparedRamp.isFinite() && m.preparedRamp in 0f..(.5f) && m.rampBlend.isFinite() && m.rampBlend>0f && m.rampBlend<=.25f)) errors+="ramp"
    if(PopupKind.entries.any { it !in m.variants } || m.variants[PopupKind.Legacy]!=PopupVariant.Legacy || m.variants.any { (k,v) -> k!=PopupKind.Legacy && v==PopupVariant.Legacy }) errors+="variants"
    return errors
}
/** The settle horizon of [law] at [speed] from its worst admitted state, |x0| <= 1.05 and |v0| <= 1.1 omega_eff (A2.2). */
internal fun popupLawHorizon(law: SpringLaw, speed: Double): Double {
    val w=law.omega*speed; val shape=SpringProfileSpec(law.zeta,(w*w).toFloat())
    return listOf(-1.05,0.0,1.05).flatMap { x -> listOf(-1.1*w,0.0,1.1*w).map { v -> if(x==0.0 && v==0.0) 0.0 else springHorizon(shape,x,v,1.0,1.0) } }.max()
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
