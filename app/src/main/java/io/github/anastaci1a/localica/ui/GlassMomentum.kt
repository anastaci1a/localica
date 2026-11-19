package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.FlexSpec
import io.github.anastaci1a.localica.ui.spec.FlexGlow
import io.github.anastaci1a.localica.ui.spec.FlexPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.*

/** Optical cap depth with an optional independently derived body scale. */
internal data class GlassShape(val strain: Float = 0f, val depth: Float = 0f, val scale: Float? = null, val lift: Float = 0f, val drift: Offset = Offset.Zero) {
    fun scaleX(vertical: Boolean = false) = scale?.let { it * exp(if (vertical) -strain else strain) } ?: exp((if (vertical) -strain else strain) - depth / 2)
    fun scaleY(vertical: Boolean = false) = scale?.let { it * exp(if (vertical) strain else -strain) } ?: exp((if (vertical) strain else -strain) - depth / 2)
    val thickness get() = exp(depth)
}

internal data class GlassGlow(val big: Float = 0f, val little: Float = 0f, val point: Offset = Offset.Zero,
    val scale: Float = 1f, val retiring: List<GlassGlow> = emptyList())

/** Signed per-axis strain from travel since touch-down; travel inside the body counts a quarter. */
internal fun glassContactFlex(point: Offset, origin: Offset, reach: Offset): Offset {
    fun axis(p: Float, o: Float, k: Float): Float { val t = p - o; val u = abs(p.coerceIn(0f, 1f) - o); return sign(t) * k * (u / 4 + abs(t) - u) }
    return Offset(axis(point.x, origin.x, reach.x), axis(point.y, origin.y, reach.y))
}

internal data class GlassBodyTransform(val scale: Offset = Offset(1f, 1f), val drift: Offset = Offset.Zero) {
    fun localPoint(point: Offset, size: Size) = Offset(
        size.width * (.5f + (point.x - .5f) / scale.x) - drift.x / scale.x,
        size.height * (.5f + (point.y - .5f) / scale.y) - drift.y / scale.y)
}

/** Selector lenses and switch thumbs: depth-scaled axes about the centre, no lift or drift. */
internal fun GlassShape.lensTransform(vertical: Boolean) = GlassBodyTransform(Offset(scaleX(vertical), scaleY(vertical)))

/** The one drawn press geometry of every glass body: [GlassShape.lift] adds that many dp to the long side being drawn. */
internal fun GlassShape.bodyTransform(size: Size, density: Float, vertical: Boolean = false): GlassBodyTransform {
    val s = if (vertical) -strain else strain
    val m = scale ?: size.maxDimension.let { if (it > 0f) 1f + lift * density / it else 1f }
    return GlassBodyTransform(Offset(m * exp(s), m * exp(-s)), Offset(drift.x * size.width, drift.y * size.height))
}

/** Compensation for center-based scaling: the contacting edge stays on its wall. */
internal fun glassWallTranslation(extent: Float, scale: Float, wall: Float): Float =
    wall.coerceIn(-1f, 1f) * extent * (1f - scale) / 2

/** Approach wall anchoring continuously; switching anchors at the endpoint jumps
 * whenever contact has already expanded the body. Works in either travel axis. */
internal fun glassWallAffinity(position: Float, last: Float): Float {
    if (last <= 0f) return 0f
    val reach = minOf(.25f, last / 2f)
    fun ease(t: Float): Float { val p = t.coerceIn(0f, 1f); return p * p * (3f - 2f * p) }
    return ease((position - last + reach) / reach) - ease((reach - position) / reach)
}

/** A sign change contains braking that magnitude-only velocity misses. The
 * correction vanishes with crossing speed, leaving a slow passage through zero
 * continuous. Callers supply finite bounded speeds and clamp each channel impulse. */
internal fun glassMotionSpeedChange(previous: Float, current: Float): Float {
    val change = abs(current) - abs(previous)
    return if (previous * current < 0f) change - 2f * minOf(abs(previous), abs(current)) else change
}

/** Position is owned by the gesture; acceleration, contact and pressure drive its shape. */
internal class GlassMomentum(bodyOverride: FlexSpec? = null) {
    var bodyOverride = bodyOverride?.copy(lift = FlexPress.None)
        set(value) { field = value?.copy(lift = FlexPress.None) }
    var spec = FlexSpec.Small
    /** Body size in dp and the touch-down point that contact translation is measured from. */
    var extent = Size.Zero
    var origin = Offset(.5f, .5f)
    private class Spring {
        var value = 0f
        var velocity = 0f
        var target = 0f
        val settled get() = abs(value - target) < GlassSpringParameters.SETTLED_DISPLACEMENT && abs(velocity) < GlassSpringParameters.SETTLED_VELOCITY
        fun advance(h: Double, k: Double, c: Double) {
            val a = c / 2
            val omega = sqrt(k)
            val q = sqrt(abs(a - omega) * (a + omega))
            val b: Double
            val aa: Double
            val dd: Double
            if (a > omega) {
                val slow = -k / (a + q)
                val es = exp(slow * h)
                b = es * -expm1(-2 * q * h) / (2 * q)
                aa = es - slow * b
                dd = exp(-(a + q) * h) + slow * b
            } else {
                val e = exp(-a * h)
                val z = q * h
                b = e * h * if (z == 0.0) 1.0 else sin(z) / z
                val hh = e * cos(z)
                aa = hh + a * b
                dd = hh - a * b
            }
            val y = value.toDouble() - target
            value = (target + aa * y + b * velocity).toFloat()
            velocity = (-k * b * y + dd * velocity).toFloat()
        }
        fun finish() { value = if (target == 0f) 0f else target; velocity = 0f }
        fun reset() { value = 0f; velocity = 0f; target = 0f }
        fun blendFrom(a: Spring, b: Spring, weight: Float, aAxis: Float = 1f, bAxis: Float = 1f) {
            value = a.value * aAxis * (1f - weight) + b.value * bAxis * weight
            velocity = a.velocity * aAxis * (1f - weight) + b.velocity * bAxis * weight
            target = a.target * aAxis * (1f - weight) + b.target * bAxis * weight
        }
    }
    private val motion = Spring()
    private val pressure = Spring()
    private val depth = Spring()
    private val lift = Spring()
    private val drift = List(2) { Spring() }
    private var liftAge = FlexPress.Hang
    private var liftSpec = (this.bodyOverride ?: spec).lift
    private val liftResponse get() = if (lift.target != 0f) liftSpec.response else liftSpec.releaseResponse
    private val liftDamping get() = if (lift.target != 0f) liftSpec.damping else liftSpec.releaseDamping
    private fun liftTarget(target: Float) {
        val w = 2 * PI / if (target != 0f) liftSpec.response else liftSpec.releaseResponse
        val impulse = if (target != 0f) liftSpec.impulse else liftSpec.releaseImpulse
        lift.velocity += (w * w * impulse * (target - lift.target)).toFloat()
        lift.target = target
    }
    private class Local(point: Offset, alpha: Float) {
        val light = List(5) { Spring() }
        var elapsed = 0.0; var origin = 0f; var originVelocity = 0f; var response = FlexGlow.Show
        val hidden get() = light[4].target == 4f
        val output get() = GlassGlow(little = light[0].value, point = Offset(light[1].value, light[2].value), scale = light[4].value)
        init {
            light[0].target = alpha
            light[1].target = point.x; light[2].target = point.y
            light[1].finish(); light[2].finish()
            light[3].value = FlexGlow.InitialResponse; light[3].target = FlexGlow.TrackingResponse
            light[4].target = 1f; light[4].finish()
        }
        fun target(alpha: Float, response: Float, scale: Float) {
            if (light[0].target != alpha || this.response != response) {
                origin = light[0].value; originVelocity = light[0].velocity; elapsed = 0.0
                light[0].target = alpha; this.response = response
            }
            light[4].target = scale
        }
        fun hide() = target(0f, FlexGlow.Hide, 4f)
        fun advance(h: Double) {
            elapsed += h
            val w = 2 * PI / response
            light[0].value = origin; light[0].velocity = originVelocity
            light[0].advance(elapsed, w * w, 2 * w)
            val smooth = 2 * PI / FlexGlow.SmoothingResponse
            light[3].advance(h, smooth * smooth, 2 * smooth)
            val follow = 2 * PI / light[3].value
            light[1].advance(h, follow * follow, 2 * follow); light[2].advance(h, follow * follow, 2 * follow)
            val scale = 2 * PI / FlexGlow.Hide
            light[4].advance(h, scale * scale, 2 * scale)
        }
        val dead get(): Boolean {
            if (!hidden || light[0].value != 0f) return false
            val w = 2 * PI / response; val a = abs(origin.toDouble()); val b = abs(originVelocity + w * origin)
            val t = if (b == 0.0) elapsed else maxOf(elapsed, 1 / w - a / b)
            return ((a + b * t) * exp(-w * t)).toFloat() == 0f
        }
        val settled get() = !hidden && light.all { it.settled }
        fun finish() {
            if (hidden) return
            if (light[0].settled) { light[0].finish(); origin = light[0].value; originVelocity = 0f; elapsed = 0.0 }
            for (i in 1..4) if (light[i].settled) light[i].finish()
        }
        fun copy(weight: Float) = Local(Offset.Zero, 0f).also { copy ->
            light.indices.forEach { copy.light[it].blendFrom(light[it], light[it], 0f) }
            for (field in listOf(copy.light[0])) { field.value *= weight; field.velocity *= weight; field.target *= weight }
            copy.origin = origin * weight; copy.originVelocity = originVelocity * weight; copy.elapsed = elapsed; copy.response = response
        }
    }
    private val broad = Spring()
    private var broadResponse = FlexGlow.Hide
    private val locals = mutableListOf<Local>()
    private var glowGeneration = 0
    private var travelVelocity = 0f
    private var stiffness = (2 * PI / (bodyOverride ?: spec).response).pow(2)
    private var resistance = (2 * sqrt(stiffness) * (bodyOverride ?: spec).damping).toFloat()
    val strain get() = .12f * tanh(motion.value / .12f) + pressure.value
    private fun shape(strain: Float, depth: Float, lift: Float, drift: Offset) = GlassShape(strain, .24f * tanh(depth / .24f), bodyOverride?.scaleGain?.let { 1f + it * depth }, lift * liftSpec.points, drift)
    val shape get() = shape(strain, depth.value, lift.value, Offset(drift[0].value, drift[1].value))
    private val depthSettled get() = bodyOverride?.scaleGain?.let {
        (it.toDouble() * (depth.target.toDouble() - depth.value)).pow(2) + (it.toDouble() * depth.velocity * resistance / stiffness).pow(2) <= 1e-6
    } ?: depth.settled
    val scaleX get() = shape.scaleX()
    val scaleY get() = shape.scaleY()
    val glow get() = (locals.lastOrNull()?.output ?: GlassGlow()).copy(big = broad.value, retiring = locals.dropLast(1).map { it.output })
    val settled get() = motion.settled && pressure.settled && drift.all { it.settled } && depthSettled && broad.settled && locals.all { it.settled } && lift.settled && liftAge >= liftSpec.hang

    /** Each output finishes independently while the shared clock waits for all channels. */
    fun finishSettlement(): Boolean {
        if (motion.settled && pressure.settled && drift.all { it.settled } && depthSettled) { motion.finish(); pressure.finish(); depth.finish(); drift.forEach { it.finish() } }
        if (lift.settled && liftAge >= liftSpec.hang) lift.finish()
        if (broad.settled) broad.finish()
        locals.forEach { it.finish() }
        return settled
    }

    fun reset() { motion.reset(); pressure.reset(); depth.reset(); drift.forEach { it.reset() }; travelVelocity = 0f; lift.reset(); liftAge = FlexPress.Hang; liftSpec = (bodyOverride ?: spec).lift; broad.reset(); locals.clear(); broadResponse = FlexGlow.Hide }

    fun targetGlow(point: Offset?, contact: Float, strength: Float, snap: Boolean = false, travel: Float = 0f, generation: Int = 0) {
        if (point == null || strength == 0f) { broad.reset(); locals.clear(); return }
        broadResponse = if (contact > 0f) FlexGlow.Show else FlexGlow.Hide
        broad.target = if (contact > 0f) spec.bigOpacity * strength else 0f
        if (contact > 0f) {
            val target = Offset(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
            if (generation != glowGeneration || locals.lastOrNull()?.hidden != false) {
                locals.lastOrNull()?.hide(); locals.add(Local(target, spec.littleOpacity * strength))
            }
            val local = locals.last()
            local.light[1].target = target.x; local.light[2].target = target.y
            if (local.light[4].target == 1f) local.target(spec.littleOpacity * strength, FlexGlow.Show, 1f)
            if (travel > FlexGlow.Travel) local.target(spec.littleOpacity * strength / 2, FlexGlow.Hide, 2f)
        } else locals.lastOrNull()?.hide()
        glowGeneration = generation
        if (snap) {
            broad.finish(); locals.forEach { it.light.forEach { channel -> channel.finish() } }
            locals.removeAll { it.hidden }; locals.forEach { it.finish() }
        }
    }

    /** A separating/rejoining body transfers stored energy as well as its shape.
     * Copying only the visible scale would discard the next frame's rebound. */
    fun blendFrom(a: GlassMomentum, b: GlassMomentum = a, weight: Float = 0f,
        aAxis: Float = 1f, bAxis: Float = 1f) {
        val fraction = weight.coerceIn(0f, 1f)
        motion.blendFrom(a.motion, b.motion, fraction, aAxis, bAxis)
        pressure.blendFrom(a.pressure, b.pressure, fraction, aAxis, bAxis)
        drift.forEachIndexed { i, it -> it.blendFrom(a.drift[i], b.drift[i], fraction) }
        depth.blendFrom(a.depth, b.depth, fraction)
        lift.blendFrom(a.lift, b.lift, fraction)
        val governing = if (fraction < .5f) a else b
        liftAge = governing.liftAge; liftSpec = governing.liftSpec; origin = governing.origin
        broad.blendFrom(a.broad, b.broad, fraction)
        if (this !== a || a !== b) {
            val adopted = if (a === b) a.locals.map { it.copy(1f) } else a.locals.map { it.copy(1f - fraction) } + b.locals.map { it.copy(fraction) }
            locals.clear(); locals.addAll(adopted)
            locals.dropLast(1).forEach { if (!it.hidden) it.hide() }
        }
        broadResponse = b.broadResponse
        glowGeneration = b.glowGeneration
        travelVelocity = a.travelVelocity + (b.travelVelocity - a.travelVelocity) * fraction
    }

    fun blendedShape(other: GlassMomentum, weight: Float, otherAxis: Float = 1f): GlassShape {
        val fraction = weight.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * fraction
        return shape(.12f * tanh(mix(motion.value, other.motion.value * otherAxis) / .12f) +
            mix(pressure.value, other.pressure.value * otherAxis), mix(depth.value, other.depth.value), mix(lift.value, other.lift.value),
            Offset(mix(drift[0].value, other.drift[0].value), mix(drift[1].value, other.drift[1].value)))
    }

    fun advance(velocity: Float, seconds: Double, pressure: Float = 0f, contact: Float = 0f, impulseScale: Float = 1f, damping: Float = GlassSpringParameters.DEFAULT_DAMPING, point: Offset? = null, vertical: Boolean = false) {
        if (!seconds.isFinite() || seconds < 0.0) return
        if (seconds > 0.0) {
            motion.advance(seconds, stiffness, resistance.toDouble())
            this.pressure.advance(seconds, stiffness, resistance.toDouble())
            depth.advance(seconds, stiffness, resistance.toDouble())
            drift.forEach { it.advance(seconds, stiffness, resistance.toDouble()) }
            val wait = if (liftAge < 0.0) minOf(seconds, -liftAge) else 0.0
            fun advanceLift(dt: Double) {
                val w = 2 * PI / liftResponse
                lift.advance(dt, w * w, 2 * w * liftDamping)
            }
            if (wait > 0.0) advanceLift(wait)
            val release = liftAge < 0.0 && seconds >= -liftAge
            liftAge = if (release) liftSpec.hang else minOf(liftSpec.hang, liftAge + seconds)
            if (release) liftTarget(0f)
            advanceLift(seconds - wait)
            val broadOmega = 2 * PI / broadResponse
            broad.advance(seconds, broadOmega * broadOmega, 2 * broadOmega)
            locals.forEach { it.advance(seconds) }; locals.removeAll { it.dead }
        }
        if (seconds > 0.0) {
            val speed = if (velocity.isFinite()) velocity.coerceIn(-30f, 30f) else 0f
            val speedChange = glassMotionSpeedChange(travelVelocity, speed)
            // Strain is axial: acceleration stretches and braking compresses along
            // travel, independent of whether that travel is leftward or rightward.
            motion.velocity += (speedChange * .11f * impulseScale.coerceIn(1f, 1.8f)).coerceIn(-.7f, .7f)
            // Acceleration flattens the cap; braking lets it rise. Contact sustains flattening.
            depth.velocity -= (speedChange * .035f * impulseScale.coerceIn(1f, 1.8f)).coerceIn(-.35f, .35f)
            travelVelocity = speed
        } else travelVelocity = 0f
        val edge = if (pressure.isFinite()) pressure.coerceIn(-.22f, .12f) else 0f
        val touch = point?.takeIf { contact > 0f && it.x.isFinite() && it.y.isFinite() }
        val flex = if (touch != null) glassContactFlex(touch, origin, Offset(extent.width, extent.height) / spec.distance) else Offset.Zero
        drift[0].target = flex.x; drift[1].target = flex.y
        this.pressure.target = edge + (abs(flex.x) - abs(flex.y)) * if (vertical) -1 else 1
        val held = contact.isFinite() && contact > 0f || abs(this.pressure.target) > .0001f
        depth.target = -.24f * (if (contact.isFinite()) contact.coerceIn(0f, 1f) else 0f) - abs(edge) * .3f
        liftSpec = (bodyOverride ?: spec).lift
        val target = if (contact.isFinite() && liftSpec.points != 0f) contact.coerceIn(0f, 1f) else 0f
        if (target > 0f) {
            if (lift.target == 0f || liftAge < 0.0) liftAge = 0.0
            liftTarget(target)
        } else if (lift.target != 0f && liftAge >= 0.0) {
            val wait = maxOf(0.0, liftSpec.hang - liftAge)
            if (wait > 0.0) liftAge = -wait else liftTarget(0f)
        }
        val drag = if (damping.isFinite()) damping.coerceIn(GlassSpringParameters.MIN_DAMPING, GlassSpringParameters.MAX_DAMPING) else GlassSpringParameters.DEFAULT_DAMPING
        val selected = bodyOverride ?: spec
        stiffness = (2 * PI / if (held) selected.trackingResponse else selected.response).pow(2)
        resistance = (2 * sqrt(stiffness) * (if (held) selected.trackingDamping else selected.damping) * drag / GlassSpringParameters.DEFAULT_DAMPING).toFloat()
    }
}
