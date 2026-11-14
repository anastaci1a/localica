package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class GlassTouchLightTest {
    private val lightIn = motionProfileId(MotionProfile.TouchLight)
    private val lightOut = motionProfileId(MotionProfile.ControlOff)

    @Test fun measuredRiseAndReleaseFinishExactlyAndIdleStopsReadingFrames() {
        EngineHarness().use { h ->
            val light = MotionFloat(h.clock, 0f, 1f)
            assertEquals(100, (StockUiSpec.motion.getValue(MotionProfile.TouchLight) as MotionProfileSpec).durationMs)
            assertEquals(180, (StockUiSpec.motion.getValue(MotionProfile.Control) as MotionProfileSpec).durationMs)
            light.retarget(1f, lightIn)
            val start = h.now
            var previous = 0f
            for (ms in 0..100) {
                val value = light.valueAt(FrameStamp(start + ms * 1_000_000L, ms.toLong()))
                assertTrue(value >= previous && value <= 1f)
                previous = value
            }
            h.advance(.1)
            assertEquals(1f, light.value(), 0f)
            assertTrue(light.settled)
            light.retarget(0f, lightOut)
            h.advance(.269)
            assertFalse(light.settled)
            h.advance(.001)
            assertEquals(0f, light.value(), 0f)
            assertTrue(light.settled)
            assertEquals(0, h.clock.activeCount)
            var reads = 0
            androidx.compose.runtime.snapshots.Snapshot.observe({ reads++ }, null) { light.value() }
            assertEquals(1, reads)
            light.dispose()
        }
    }

    @Test fun interruptedRisePreservesVelocityAndStaysWithinTheLightRange() {
        for (ms in 1..99) EngineHarness().use { h ->
            val light = MotionFloat(h.clock, 0f, 1f)
            light.retarget(1f, lightIn)
            h.advance(ms / 1000.0)
            val stamp = h.clock.stampNow
            val before = light.sampleAt(stamp)
            light.retarget(0f, lightOut)
            assertEquals(before, light.sampleAt(stamp))
            for (step in 0..270) {
                val sample = light.valueAt(FrameStamp(stamp.nanos + step * 1_000_000L, step.toLong()))
                assertTrue("release at $ms ms: $sample", sample in 0f..1f)
            }
            h.advance(.27)
            assertEquals(0f, light.value(), 0f)
            light.dispose()
        }
    }

    @Test fun reducedMotionSnapsNewAndInFlightContactIntents() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            val light = MotionFloat(h.clock, 0f, 1f)
            light.retarget(1f, lightIn)
            assertEquals(0, light.kind)
            assertEquals(1f, light.value(), 0f)
            light.retarget(0f, lightOut)
            assertEquals(0, light.kind)
            assertEquals(0f, light.value(), 0f)
            light.dispose()
        }
        EngineHarness().use { h ->
            val light = MotionFloat(h.clock, 0f, 1f)
            light.retarget(1f, lightIn)
            h.advance(.025)
            h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            assertEquals(1f, light.value(), 0f)
            assertEquals(0, h.clock.activeCount)
            light.dispose()
        }
    }

    @Test fun mappingRoundTripsAcrossAspectAxisStrainAndOutsideContacts() {
        for (size in listOf(Size(96f, 96f), Size(240f, 48f), Size(48f, 240f)))
            for (vertical in listOf(false, true)) for (shape in listOf(GlassShape(), GlassShape(.2f, -.3f), GlassShape(-.3f, .2f)))
                for (x in listOf(-.25f, 0f, .2f, .5f, 1f, 1.25f)) for (y in listOf(-.25f, 0f, .5f, 1f, 1.25f)) {
                    val point = Offset(x, y)
                    val mapped = glassContactPoint(point, size, shape, vertical)
                    assertEquals(x * size.width, size.width / 2 + (mapped.x - size.width / 2) * shape.scaleX(vertical), .0001f)
                    assertEquals(y * size.height, size.height / 2 + (mapped.y - size.height / 2) * shape.scaleY(vertical), .0001f)
                }
    }

    @Test fun frozenOpticsAndZeroLightDisplayListContracts() {
        val root = File("src/main/java/io/github/anastaci1a/localica/ui")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("1be7b81ccf3d1f869658d46341661c44004b4ff216c95f12a164eb41c4811d7c", sha(File(root, "LensRenderer.kt").readBytes()))
        val source = File(root, "LiquidGlass.kt").readText()
        val effect = source.substring(source.indexOf("if (Build.VERSION.SDK_INT >= 33) lens.value?.effect("), source.indexOf("\n                else if", source.indexOf("if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(")))
        assertEquals("20e598e80a1ac997cfb4d02c22a9c8ceffb5f152de28344c6e71ff53ef954892", sha(effect.toByteArray()))
        assertTrue(File(root, "GlassRimCache.kt").readText().contains("if (alpha == 0f) return"))
        assertTrue(source.contains("MotionProfile.TouchLight, io.github.anastaci1a.localica.ui.spec.MotionProfile.ControlOff"))
        assertEquals(1f, GlassAppearance().touchLight, 0f)
        for (strength in listOf(Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(1f, GlassAppearance(touchLight = strength).normalized().touchLight, 0f)
        assertEquals(0f, GlassAppearance(touchLight = -1f).normalized().touchLight, 0f)
        assertEquals(1f, GlassAppearance(touchLight = 2f).normalized().touchLight, 0f)
    }
}
