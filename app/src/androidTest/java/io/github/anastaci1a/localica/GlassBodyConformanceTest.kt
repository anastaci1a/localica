package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.*
import java.math.BigDecimal
import java.math.MathContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** R4 numerical reference: an independent high precision exponential of the ODE matrix. */
class GlassBodyConformanceTest {
    private val precision = MathContext(70)
    private fun bd(x: Double) = BigDecimal(x, precision)
    private fun multiply(a: List<BigDecimal>, b: List<BigDecimal>) = List(4) { i ->
        a[i / 2 * 2].multiply(b[i % 2], precision)
            .add(a[i / 2 * 2 + 1].multiply(b[2 + i % 2], precision), precision).let { if (it.abs() < BigDecimal("1e-1000")) BigDecimal.ZERO else it }
    }
    private fun reference(x: Float, v: Float, target: Float, h: Double, k: Double, c: Double): Pair<Float, Float> {
        var steps = 0
        var t = h
        while (t * maxOf(1.0, k, c) > .125) { t /= 2; steps++ }
        val matrix = listOf(bd(0.0), bd(t), bd(-k * t), bd(-c * t))
        var term = listOf(bd(1.0), bd(0.0), bd(0.0), bd(1.0))
        var sum = term
        for (n in 1..48) {
            term = multiply(term, matrix).map { it.divide(BigDecimal(n), precision) }
            sum = sum.zip(term).map { (a, b) -> a.add(b, precision) }
        }
        repeat(steps) { sum = multiply(sum, sum) }
        val y = bd(x.toDouble()).subtract(bd(target.toDouble()), precision)
        fun apply(a: Int, b: Int) = sum[a].multiply(y, precision).add(sum[b].multiply(bd(v.toDouble()), precision), precision)
        return apply(0, 1).add(bd(target.toDouble()), precision).toFloat() to apply(2, 3).toFloat()
    }
    private fun spring(body: GlassMomentum, name: String): Any = body.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(body)
    private fun lights(body: GlassMomentum) = glowChannels(body)
    private fun get(spring: Any, name: String) = spring.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(spring)
    private fun set(spring: Any, name: String, value: Float) = spring.javaClass.getDeclaredField(name).apply { isAccessible = true }.setFloat(spring, value)
    private fun state(body: GlassMomentum) = (listOf(spring(body, "motion"), spring(body, "pressure"), spring(body, "depth")) + lights(body).map { it!! })
        .flatMap { s -> listOf("value", "velocity", "target").map { get(s, it) } }
    private fun coefficient(body: GlassMomentum, name: String): Number = body.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(body) as Number

    @Test fun analyticalMatrixMatchesIndependentFloatOracleAcrossTheSupportedDomain() {
        val channel = spring(GlassMomentum(), "depth")
        val advance = channel.javaClass.getDeclaredMethod("advance", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).apply { isAccessible = true }
        var maximumAbsolute = 0.0
        var maximumUlps = 0.0
        var cases = 0
        fun check(k: Double, c: Double, h: Double, x: Float = -.17f, v: Float = .73f, target: Float = -.24f) {
            set(channel, "value", x); set(channel, "velocity", v); set(channel, "target", target)
            val expected = reference(x, v, target, h, k, c)
            advance.invoke(channel, h, k, c)
            for ((field, ref) in listOf("value" to expected.first, "velocity" to expected.second)) {
                val actual = get(channel, field)
                assertTrue("finite $field k=$k c=$c h=$h", actual.isFinite())
                val error = abs(actual.toDouble() - ref)
                val ulp = Math.ulp(ref).toDouble()
                val allowance = ulp + 2.0.pow(-40) * maxOf(1.0, abs(x.toDouble()) + abs(target.toDouble()) + abs(v / sqrt(k))) * maxOf(1.0, sqrt(k))
                assertTrue("$field k=$k c=$c h=$h expected=$ref actual=$actual error=$error bound=$allowance", error <= allowance)
                maximumAbsolute = maxOf(maximumAbsolute, error)
                maximumUlps = maxOf(maximumUlps, error / ulp)
            }
            cases++
        }
        for (vector in listOf(
            listOf(1.0, 0.0, 1.08, .625, .262, 1.0 / 120),
            listOf(1.07, .1, 1.0, .375, .4, 1.0 / 60),
            listOf(.2, -.3, 1.0, 1.0, .25, .02),
            listOf(1.2, 2.0, 1.0, 1.4, .4, .01),
            listOf(.3, .4, 1.0, .6, .36, 0.0),
            listOf(1.0, 0.0, 1.0, .375, .4, .01))) {
            val omega = 2 * PI / vector[4].toFloat()
            check(omega * omega, (2 * omega * vector[3].toFloat()).toFloat().toDouble(), vector[5], vector[0].toFloat(), vector[1].toFloat(), vector[2].toFloat())
        }
        val sizes = listOf(44f, 119f, Math.nextDown(120f), 120f, Math.nextUp(120f), 160f, 240f)
        for (damping in 2..40) for (tenths in 5..15) for (held in listOf(false, true)) {
            val spec = FlexSpec.dynamic(sizes[(damping + tenths) % sizes.size], 44f)
            val w = 2 * PI / if (held) spec.trackingResponse else spec.response
            val k = w * w
            val c = (2 * sqrt(k) * (if (held) spec.trackingDamping else spec.damping) * damping / 8).toFloat().toDouble()
            check(k, c, (if (tenths % 2 == 0) 1.0 / 120 else 1.0 / 60) * (4f * tenths / 30f))
        }
        val w = 2 * PI / .262f
        for (c in listOf(Math.nextDown((2 * w).toFloat()), (2 * w).toFloat(), Math.nextUp((2 * w).toFloat())))
            for (h in listOf(1e-9, .02, 11.0)) check(w * w, c.toDouble(), h)
        for (ratio in listOf(1.0, Math.nextDown(1.0), Math.nextUp(1.0), 1 - 2.0.pow(-20), 1 + 2.0.pow(-20), 1 - 2.0.pow(-40), 1 + 2.0.pow(-40), 3.125))
            for (h in listOf(1e-9 * (2f / 3), 1.0 / 120, .1, 1.0, 11.0, 60.0, 3600.0, Long.MAX_VALUE.toDouble() / 1e9 * 2))
                check(w * w, 2 * w * ratio, h)
        for (response in listOf(.1f, .5f, .15f)) for (h in listOf(1e-9, 1.0 / 120, 11.0, 60.0)) {
            val omega = 2 * PI / response
            check(omega * omega, 2 * omega, h, .4f, -.7f, 0f)
        }
        check(w * w, 2 * w, 1e-9, Float.MIN_VALUE, -Float.MIN_VALUE, 0f)
        println("R4 subnormal valueBits=" + get(channel, "value").toRawBits() + " velocityBits=" + get(channel, "velocity").toRawBits())
        println("R4 Float oracle cases=$cases maximumAbsolute=$maximumAbsolute maximumUlps=$maximumUlps")
    }

    @Test fun observationUsesOldBodyAndGlowCoefficientsBeforeInstallingNewInputs() {
        val body = GlassMomentum()
        body.advance(0f, 0.0, pressure = -.1f, contact = 1f, damping = 2f)
        body.targetGlow(Offset(.25f, .75f), 1f, 1f)
        body.advance(0f, .03, pressure = -.1f, contact = 1f, damping = 2f)
        val before = state(body)
        val k = coefficient(body, "stiffness").toDouble()
        val c = coefficient(body, "resistance").toDouble()
        val w = 2 * PI / .1f
        val expectedBody = reference(before[6], before[7], before[8], .02, k, c)
        val expectedAlpha = reference(before[9], before[10], before[11], .02, w * w, 2 * w)
        body.spec = FlexSpec.dynamic(240f, 160f)
        body.advance(0f, .02, contact = 0f, damping = 40f)
        body.targetGlow(Offset(1.25f, -.25f), 0f, .5f)
        val after = state(body)
        assertEquals(expectedBody.first, after[6], Math.ulp(expectedBody.first))
        assertEquals(expectedBody.second, after[7], Math.ulp(expectedBody.second))
        assertEquals(expectedAlpha.first, after[9], Math.ulp(expectedAlpha.first))
        assertEquals(expectedAlpha.second, after[10], Math.ulp(expectedAlpha.second))
        assertEquals(0f, after[11], 0f)
        val free = 2 * PI / body.spec.response
        assertEquals(free * free, coefficient(body, "stiffness").toDouble(), 0.0)
        val retained = state(body)
        body.advance(20f, 0.0, contact = 1f, damping = 2f)
        for (i in retained.indices.filter { it % 3 != 2 }) assertEquals("h=0 preserves raw x/v", retained[i], state(body)[i], 0f)
        val snapshot = state(body)
        body.advance(30f, Double.NaN, contact = 0f)
        assertEquals(snapshot, state(body))
        body.advance(30f, -1.0, contact = 0f)
        assertEquals(snapshot, state(body))
    }

    @Test fun dynamicOpacityAndSelectedGeometryKeepTheirSeparateExactBoundaries() {
        for (long in listOf(0f, 44f, 64f, 119f, Math.nextDown(120f), 120f, Math.nextUp(120f), 128f, 160f, 240f))
            for (short in listOf(0f, 44f, 64f, 160f)) for (strength in listOf(0f, .5f, 1f)) {
                val t = ((long - 44f) / 116f).coerceIn(0f, 1f)
                val u = ((short.toDouble() - 44) / 116).coerceIn(0.0, 1.0)
                val expected = if (long < 120f) FlexSpec(.375f, .4f, .625f, .262f) else FlexSpec(.375f + .225f * t, .4f - .04f * t, .625f, .262f + .052f * t, fusedTestValue(1 - u, 1.0, u * 0.0).toFloat(), fusedTestValue(1 - u, .3, u * .2).toFloat(), lift = FlexPress.Large, distance = (6000 + 18000 * u).toFloat())
                val body = GlassMomentum(FlexSpec.Small).apply { spec = FlexSpec.dynamic(long, short) }
                assertEquals(expected, body.spec)
                body.advance(0f, 0.0, contact = 1f, damping = 40f)
                assertEquals((2 * PI / FlexSpec.Small.trackingResponse).pow(2), coefficient(body, "stiffness").toDouble(), 0.0)
                body.targetGlow(Offset(.4f, .6f), 1f, strength)
                assertEquals((expected.bigOpacity * strength).toBits(), get(lights(body)[0]!!, "target").toBits())
                assertEquals((expected.littleOpacity * strength).toBits(), (lights(body).getOrNull(1)?.let { get(it, "target") } ?: 0f).toBits())
                if (strength == 0f) assertEquals(GlassGlow(), body.glow)
            }
        val body = GlassMomentum(FlexSpec.Small.copy(scaleGain = -(16f / 64f) / .24f))
        val depth = spring(body, "depth")
        set(depth, "value", -.12f); set(depth, "velocity", .3f); set(depth, "target", -.24f)
        val raw = state(body)
        val optical = body.shape.depth
        assertEquals(1.125f, body.shape.scaleX(), 0f)
        body.bodyOverride = body.bodyOverride!!.copy(scaleGain = -(16f / 128f) / .24f)
        assertEquals(1.0625f, body.shape.scaleX(), 0f)
        assertEquals(optical.toBits(), body.shape.depth.toBits())
        assertEquals(raw, state(body))
        assertEquals(body.shape, body.blendedShape(body, .5f))
        body.bodyOverride = FlexSpec.Small.copy(scaleGain = -(16f / 64f) / .24f)
        body.advance(0f, 0.0, contact = 1f)
        set(depth, "value", -.2395f); set(depth, "velocity", .005f)
        assertTrue("selected completion is measured in scale units", body.settled)
        assertFalse("the raw rectangular metric is a different contract",
            abs(get(depth, "value") - get(depth, "target")) < .0003f && abs(get(depth, "velocity")) < .003f)
        assertTrue(body.finishSettlement())
        assertEquals(-.24f, get(depth, "value"), 0f)
        assertEquals(0f, get(depth, "velocity"), 0f)
    }

    @Test fun completionFinishesBodyAndEachLightIndependentlyAndTransfersEveryRawScalar() {
        val a = GlassMomentum(); val b = GlassMomentum(); val joined = GlassMomentum()
        a.targetGlow(Offset(.2f, .4f), 1f, 1f); b.targetGlow(Offset(.8f, .6f), 1f, 1f)
        val allA = listOf(spring(a, "motion"), spring(a, "pressure"), spring(a, "depth")) + lights(a).map { it!! }
        val allB = listOf(spring(b, "motion"), spring(b, "pressure"), spring(b, "depth")) + lights(b).map { it!! }
        for ((i, item) in allA.withIndex()) for ((j, field) in listOf("value", "velocity", "target").withIndex()) {
            set(item, field, -(i + j + 1) * .13f)
            set(allB[i], field, (i + j + 1) * .17f)
        }
        val left = state(a); val right = state(b)
        joined.blendFrom(a, b, .25f, -1f, 1f)
        for ((i, actual) in state(joined).take(12).withIndex()) assertEquals(left[i] * (if (i < 6) -1f else 1f) * .75f + right[i] * .25f, actual, 0f)
        val records = glowRecords(joined)
        assertEquals(2, records.size)
        for ((index, source) in listOf(a, b).withIndex()) {
            val weight = if (index == 0) .75f else .25f
            val expected = glowChannels(glowRecords(source).single()!!)
            val actual = glowChannels(records[index]!!)
            for (channel in actual.indices) for (field in listOf("value", "velocity", "target")) {
                if (index == 0 && field == "target" && channel in listOf(0, 4)) continue // Retired: alpha 0 and scale 4.
                assertEquals(get(expected[channel]!!, field) * if (channel == 0) weight else 1f, get(actual[channel]!!, field), 0f)
            }
            assertEquals(if (index == 0) 0f else get(expected[0]!!, "target") * weight, get(actual[0]!!, "target"), 0f)
        }
        val k = coefficient(joined, "stiffness")
        joined.blendFrom(joined, aAxis = -1f, bAxis = -1f)
        assertEquals(k, coefficient(joined, "stiffness"))
        a.reset()
        a.targetGlow(Offset(.5f, .5f), 1f, 1f)
        lights(a).forEach { set(it, "target", get(it, "value")) }
        set(spring(a, "depth"), "target", -.24f); set(spring(a, "depth"), "value", -.23999f)
        set(lights(a)[0]!!, "value", .3f); set(lights(a)[1]!!, "value", .00001f)
        seedGlowEpoch(a)
        assertFalse(a.finishSettlement())
        assertEquals(-.24f, get(spring(a, "depth"), "value"), 0f)
        assertEquals(.3f, a.glow.big, 0f)
        assertEquals(0f, a.glow.little, 0f)
        a.targetGlow(null, 1f, 1f)
        assertTrue(a.settled)
    }
}
