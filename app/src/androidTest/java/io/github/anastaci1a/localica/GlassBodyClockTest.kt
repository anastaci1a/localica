package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real rememberGlassBody scheduling under the existing BroadcastFrameClock fixture pattern. */
class GlassBodyClockTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
    private class Inputs {
        var position by mutableFloatStateOf(0f)
        var contact by mutableFloatStateOf(0f)
        var lightContact by mutableStateOf<Float?>(null)
        var point by mutableStateOf<Offset?>(null)
        var speed by mutableFloatStateOf(1f)
        var damping by mutableFloatStateOf(8f)
        var active by mutableStateOf(true)
        var reduced by mutableStateOf(false)
        var strength by mutableFloatStateOf(1f)
        val body = GlassBodyState()
    }
    private suspend fun CoroutineScope.withBody(block: suspend Inputs.(suspend (Long) -> Unit) -> Unit) {
        val input = Inputs()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        composition.setContent {
            CompositionLocalProvider(LocalMotionSpeed provides input.speed, LocalReducedMotion provides input.reduced,
                LocalGlassAppearance provides GlassAppearance(shapeDamping = input.damping, touchLight = input.strength)) {
                rememberGlassBody({ input.position }, contact = { input.contact }, state = input.body,
                    active = { input.active }, point = { input.point ?: Offset.Zero }, lightContact = { input.lightContact ?: input.contact })
            }
        }
        suspend fun flush() { Snapshot.sendApplyNotifications(); repeat(4) { yield() } }
        try {
            flush()
            input.block { time -> flush(); clock.sendFrame(time); flush() }
        } finally { composition.dispose(); recomposer.cancel(); runner.cancelAndJoin() }
    }
    private fun value(body: GlassBodyState, channel: String, field: String): Float {
        val spring = body.momentum.javaClass.getDeclaredField(channel).apply { isAccessible = true }.get(body.momentum)
        return spring.javaClass.getDeclaredField(field).apply { isAccessible = true }.getFloat(spring)
    }

    private fun set(body: GlassBodyState, channel: String, field: String, value: Float) {
        val spring = body.momentum.javaClass.getDeclaredField(channel).apply { isAccessible = true }.get(body.momentum)
        spring.javaClass.getDeclaredField(field).apply { isAccessible = true }.setFloat(spring, value)
    }
    private fun travel(body: GlassBodyState, velocity: Float) =
        body.momentum.javaClass.getDeclaredField("travelVelocity").apply { isAccessible = true }.setFloat(body.momentum, velocity)

    @Test fun admissionRepeatedTimeTravelBrakingAndIdleRetirementUseRealObservations() = runBlocking(Dispatchers.Main) {
        withBody { frame ->
            position = .1f
            frame(0L)
            assertEquals(GlassShape(), body.output.value)
            position = .2f
            frame(0L)
            assertEquals("duplicate time consumes no position", .1f, body.previous, 0f)
            frame(8_333_333L)
            assertEquals(.7f, value(body, "motion", "velocity"), 0f)
            assertEquals(-.35f, value(body, "depth", "velocity"), 0f)
            position = .3f
            frame(16_666_666L)
            assertTrue("position-only wake reaches positive deformation", body.output.value.strain > 0f)
            frame(16_666_666L)
            for (channel in listOf("motion", "pressure", "depth")) {
                set(body, channel, "value", 0f); set(body, channel, "velocity", 0f)
            }
            set(body, "motion", "value", .00001f)
            travel(body, ((.4f - .3f) / (8_333_333.0 / 1e9)).toFloat())
            position = .4f
            frame(24_999_999L)
            assertTrue("settled constant travel remains observable", body.momentum.settled)
            assertNotEquals("moving samples cannot finish a settled residual", 0f, value(body, "motion", "value"))
            frame(33_333_332L)
            assertTrue("quiet sample applies braking before retirement", value(body, "motion", "velocity") < -.6f)
            for (channel in listOf("motion", "pressure", "depth")) {
                set(body, channel, "value", 0f); set(body, channel, "velocity", 0f)
            }
            travel(body, 0f)
            position = Math.nextUp(position)
            frame(41_666_665L)
            assertTrue(body.momentum.settled)
            assertTrue("tiny nonzero displacement cannot finish its tiny impulse", value(body, "motion", "velocity") > 0f)
            frame(49_999_998L)
            assertEquals("positive quiet sample may finish the sub-threshold response", 0f, value(body, "motion", "velocity"), 0f)
            repeat(1000) { frame(58_333_331L + it * 8_333_333L) }
            assertEquals(GlassShape(), body.output.value)
            assertTrue(body.momentum.settled)
            contact = 1f
            frame(9_000_000_000L)
            repeat(500) { frame(9_008_333_333L + it * 8_333_333L) }
            assertTrue(body.momentum.settled)
            position = .4f
            frame(14_000_000_000L)
            assertEquals("admission clears travel without impulse", 0f, value(body, "motion", "velocity"), 0f)
            position = .5f
            frame(14_008_333_333L)
            position = .6f
            frame(14_016_666_666L)
            assertTrue("held interior travel also deforms", body.output.value.strain > 0f)
            repeat(1000) { frame(14_024_999_999L + it * 8_333_333L) }
            assertTrue("held quiescence retires without release", body.momentum.settled)
        }
    }

    @Test fun speedOwnershipGlowAndReducedMotionShareOneClockWithoutCrossGapAdvancement() = runBlocking(Dispatchers.Main) {
        withBody { frame ->
            contact = 1f; point = Offset(.25f, .75f)
            frame(0L)
            assertEquals(0f, body.glow.value.big, 0f)
            assertEquals(point, body.glow.value.point)
            frame(8_333_333L)
            assertTrue(body.glow.value.big > 0f)
            speed = 2f
            frame(16_666_666L) // Apply the CompositionLocal change after the last old-owner observation.
            val raw = value(body, "depth", "value")
            val velocity = value(body, "depth", "velocity")
            val glow = body.glow.value
            frame(11_000_000_000L)
            assertEquals("speed readmission is h=0", raw, value(body, "depth", "value"), 0f)
            assertEquals(velocity, value(body, "depth", "velocity"), 0f)
            assertEquals(glow, body.glow.value)
            frame(11_008_333_333L)
            assertNotEquals(glow.big, body.glow.value.big)
            body.momentum.spec = FlexSpec.dynamic(240f, 160f)
            for (channel in listOf("motion", "pressure")) {
                set(body, channel, "value", 0f); set(body, channel, "velocity", 0f)
            }
            set(body, "depth", "value", -.23999f); set(body, "depth", "velocity", 0f)
            val light = glowChannels(body.momentum)
            for ((index, item) in light.take(2).withIndex()) {
                for ((field, value) in listOf("value" to if (index == 0) 0f else .3f,
                    "velocity" to 0f, "target" to if (index == 0) 0f else 1f))
                    item!!.javaClass.getDeclaredField(field).apply { isAccessible = true }.setFloat(item, value)
            }
            body.momentum.javaClass.getDeclaredField("liftAge").apply { isAccessible = true }.setDouble(body.momentum, io.github.anastaci1a.localica.ui.spec.FlexPress.Hang)
            seedGlowEpoch(body.momentum)
            frame(11_016_666_666L)
            assertEquals("eligible body completion does not wait for light", -.24f, value(body, "depth", "value"), 0f)
            assertEquals(0f, value(body, "depth", "velocity"), 0f)
            assertEquals("the already settled alpha finishes independently", 0f, body.glow.value.big, 0f)
            val w = 2 * kotlin.math.PI / .1f
            val h = 8_333_333.0 * 2 / 1e9
            val expected = (1 + (.3f.toDouble() - 1) * (1 + w * h) * kotlin.math.exp(-w * h)).toFloat()
            assertEquals("local alpha uses its retained Show segment", expected, body.glow.value.little, Math.ulp(expected))
            val unfinishedLight = body.glow.value.little
            frame(11_024_999_999L)
            assertNotEquals("another channel's completion cannot retire the clock", unfinishedLight, body.glow.value.little)
            active = false
            frame(11_033_333_332L)
            val cancelled = body.output.value
            val cancelledGlow = body.glow.value
            frame(22_000_000_000L)
            assertEquals(cancelled, body.output.value)
            assertEquals(cancelledGlow, body.glow.value)
            position = .7f; active = true
            frame(33_000_000_000L)
            assertEquals("ownership resume does not consume inactive gap", cancelled, body.output.value)
            assertEquals(cancelledGlow, body.glow.value)
            contact = 0f
            frame(33_008_333_333L)
            repeat(1000) { frame(33_016_666_666L + it * 8_333_333L) }
            assertTrue(body.momentum.settled)
            assertEquals(GlassShape(), body.output.value)
            assertEquals(0f, body.glow.value.big, 0f)
            body.momentum.spec = FlexSpec.Small
            contact = 1f; reduced = true; point = Offset(1.25f, -.25f)
            frame(45_000_000_000L)
            assertEquals(GlassShape(), body.output.value)
            assertEquals(GlassGlow(1f, 1f, Offset(1f, 0f)), body.glow.value)
            strength = 0f
            frame(45_008_333_333L)
            assertEquals(GlassGlow(), body.glow.value)
            reduced = false; strength = 1f; contact = 1f; lightContact = 1f; point = Offset(.5f, .5f)
            frame(46_000_000_000L)
            frame(46_016_666_666L)
            assertTrue("physical light begin is visible", body.glow.value.big > 0f)
            lightContact = 0f
            frame(46_024_999_999L)
            assertEquals("body engagement remains independent", -.24f, value(body, "depth", "target"), 0f)
            val channels = glowChannels(body.momentum)
            fun target(channel: Any) = channel.javaClass.getDeclaredField("target").apply { isAccessible = true }.getFloat(channel)
            assertEquals("broad terminal follows physical contact", 0f, target(channels[0]), 0f)
            assertEquals("local terminal follows physical contact", 0f, target(channels[1]), 0f)
            assertEquals("physical terminal selects q4 while body stays held", 4f, target(channels.last()), 0f)
        }
    }
}
