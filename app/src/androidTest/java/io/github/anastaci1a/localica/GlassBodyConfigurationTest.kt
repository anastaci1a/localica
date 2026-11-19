package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Reuses the accepted CompositionData owner inspection; no production observer or state hook. */
class GlassBodyConfigurationTest {
    @get:Rule val compose = localicaComposeRule()
    private fun contacts(root: CompositionData, tables: Set<CompositionData>): List<GlassContact> {
        val result = mutableListOf<GlassContact>()
        fun visit(data: CompositionData) {
            for (group in data.compositionGroups) {
                for (value in group.data) if (value is GlassContact && result.none { it === value }) result += value
                visit(group)
            }
        }
        visit(root)
        tables.filter { it !== root }.forEach(::visit)
        return result
    }
    private fun raw(contact: GlassContact): List<Float> {
        val body = contact.state.momentum
        return listOf("motion", "pressure", "depth").flatMap { channel ->
            val spring = body.javaClass.getDeclaredField(channel).apply { isAccessible = true }.get(body)
            listOf("value", "velocity", "target").map { spring.javaClass.getDeclaredField(it).apply { isAccessible = true }.getFloat(spring) }
        }
    }
    private fun seed(contact: GlassContact) {
        val body = contact.state.momentum
        val spring = body.javaClass.getDeclaredField("depth").apply { isAccessible = true }.get(body)
        for ((field, value) in listOf("value" to -.12f, "velocity" to .3f, "target" to -.24f))
            spring.javaClass.getDeclaredField(field).apply { isAccessible = true }.setFloat(spring, value)
        contact.state.output.value = body.shape
    }
    private fun alphaTargets(contact: GlassContact): List<Float> {
        val body = contact.state.momentum
        val lights = glowChannels(body)
        return lights.take(2).map { it.javaClass.getDeclaredField("target").apply { isAccessible = true }.getFloat(it) }.let { if (it.size == 1) it + 0f else it }
    }

    @Test fun measuredFreshActionAndSharedAnchorKeepTheirDistinctConfigurationAcrossResizeAndReducedMotion() {
        var width by mutableFloatStateOf(64f)
        var sharedWidth by mutableFloatStateOf(240f)
        var reduced by mutableStateOf(false)
        lateinit var root: CompositionData
        lateinit var tables: Set<CompositionData>
        val anchor = GlassAnchor()
        val decoy = GlassContact()
        fun gain(longSide: Float) = -((if (longSide < 120f) 16f else (16.0 - 12.0 * 20 / 116).toFloat()) / longSide) / .24f
        fun published(contact: GlassContact, longSide: Float) {
            compose.mainClock.advanceTimeUntil {
                contact.state.momentum.spec == FlexSpec.dynamic(longSide, 64f) &&
                    contact.state.momentum.bodyOverride?.scaleGain == gain(longSide) &&
                    anchor.contact.state.momentum.spec == FlexSpec.dynamic(sharedWidth, 160f)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val inspected = remember { mutableSetOf<CompositionData>() }
            root = currentComposer.compositionData
            tables = inspected
            CompositionLocalProvider(LocalInspectionTables provides inspected) {
                EngineTheme {
                    CompositionLocalProvider(LocalReducedMotion provides reduced, LocalMotionSpeed provides 1f,
                        LocalGlassAppearance provides GlassAppearance()) {
                        Column {
                            GlassActionButton(true, AppIcon.ActionEdit, "Fresh", Modifier.size(width.dp, 64.dp), onClick = {})
                            GlassActionButton(true, AppIcon.ActionEdit, "Anchored", Modifier.glassAnchor(anchor).size(128.dp, 64.dp), onClick = {})
                            LiquidGlassSurface(Modifier.glassAnchor(anchor).size(sharedWidth.dp, 160.dp), source = null,
                                sharedContact = decoy, bodyOverride = FlexSpec.Small, shadow = false)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val fresh = compose.runOnIdle {
            val selected = contacts(root, tables).filter { it.state.momentum.bodyOverride != null }
            assertEquals("only the fresh shared action selects Small", 1, selected.size)
            assertNull(anchor.contact.state.momentum.bodyOverride)
            assertNull(decoy.state.momentum.bodyOverride)
            assertEquals(FlexSpec.dynamic(240f, 160f), anchor.contact.state.momentum.spec)
            assertEquals("anchor wins over the explicitly supplied decoy", FlexSpec.Small, decoy.state.momentum.spec)
            selected.single().also { it.pressed = true; anchor.contact.active = false }
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.mainClock.advanceTimeUntil { fresh.state.momentum.settled }
        val held = compose.runOnIdle {
            assertTrue(fresh.state.momentum.settled)
            assertEquals(1f, fresh.state.glow.value.big, 0f)
            raw(fresh).also { width = 128f; sharedWidth = 160f }
        }
        val heldTargets = compose.runOnIdle { alphaTargets(fresh) }
        published(fresh, 128f)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("idle size assignment preserves settled raw state", held, raw(fresh))
            assertEquals(1f + gain(128f) * -.24f, fresh.body.value.scaleX(), 0f)
            assertEquals("idle resize does not wake or retarget glow", 1f, fresh.state.glow.value.big, 0f)
            assertEquals("idle layout does not consume the newly assigned opacity targets", heldTargets, alphaTargets(fresh))
            fresh.active = false
        }
        compose.mainClock.advanceTimeByFrame()
        val initial = compose.runOnIdle {
            seed(fresh)
            raw(fresh).also { width = 64f }
        }
        published(fresh, 64f)
        compose.runOnIdle {
            assertEquals(initial, raw(fresh))
            assertEquals(gain(64f), fresh.state.momentum.bodyOverride!!.scaleGain)
            assertEquals(1.125f, fresh.body.value.scaleX(), 0f)
            width = 128f
        }
        published(fresh, 128f)
        compose.runOnIdle {
            assertEquals(initial, raw(fresh))
            assertEquals(1f + gain(128f) * -.12f, fresh.body.value.scaleX(), 0f)
            assertEquals(gain(128f), fresh.state.momentum.bodyOverride!!.scaleGain)
            assertEquals(FlexSpec.Small.response, fresh.state.momentum.bodyOverride!!.response, 0f)
            assertEquals(FlexSpec.dynamic(128f, 64f), fresh.state.momentum.spec)
            assertEquals(FlexSpec.dynamic(160f, 160f), anchor.contact.state.momentum.spec)
            assertEquals("layout assignment alone does not retarget light", 1f, fresh.state.glow.value.big, 0f)
            reduced = true; width = 64f
        }
        published(fresh, 64f)
        compose.runOnIdle {
            assertEquals("idle reduced-motion remeasurement remains identity", GlassShape(), fresh.body.value)
            assertEquals(initial, raw(fresh))
            reduced = false; fresh.active = true; fresh.pressed = true; fresh.point = Offset(.25f, .75f)
        }
        compose.mainClock.advanceTimeBy(64)
        val moving = compose.runOnIdle {
            assertFalse(fresh.state.momentum.settled)
            val spring = fresh.state.momentum.javaClass.getDeclaredField("depth").apply { isAccessible = true }.get(fresh.state.momentum)
            val values = listOf("value", "velocity").map { spring.javaClass.getDeclaredField(it).apply { isAccessible = true }.getFloat(spring) }
            width = 128f
            values
        }
        published(fresh, 128f)
        compose.runOnIdle {
            assertEquals(gain(128f), fresh.state.momentum.bodyOverride!!.scaleGain)
            assertEquals(FlexSpec.Small.trackingResponse, fresh.state.momentum.bodyOverride!!.trackingResponse, 0f)
            assertNotEquals("running resize preserves live motion instead of resetting", listOf(0f, 0f), moving)
            assertNotEquals("live velocity is retained through publication", 0f, raw(fresh)[7])
        }
    }
}
