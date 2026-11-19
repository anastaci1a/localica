package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import kotlin.math.abs

/** The real caller and a zero-power material share its recorded source and physical placement. */
internal class DefaultRefractionFixture {
    private data class Reference(val bounds: Rect, val source: FrostedBackdrop, val power: Float)
    private var reference by mutableStateOf<Reference?>(null)
    private lateinit var recording: FrostedBackdrop
    private val draws = mutableMapOf<Int, GlassSurfaceDrawObservation>()

    @Composable fun Scene(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
        recording = rememberFrostedBackdrop(Unit, enabled = false)
        val density = LocalDensity.current.density
        Box(modifier.recordFrostedBackdrop(recording)) {
            CompositionLocalProvider(LocalGlassVisible provides (reference == null),
                LocalGlassSurfaceDrawObserver provides { receipt ->
                    glassDrawProbe?.invoke(receipt)
                    if (receipt is GlassSurfaceDrawObservation) draws[receipt.surfaceIdentity] = receipt
                }) { content() }
            reference?.let { frame ->
                val origin = requireNotNull(recording.location.coordinates).positionInRoot()
                val bounds = frame.bounds.translate(-origin)
                LiquidGlassSurface(Modifier.absoluteOffset((bounds.left / density).dp, (bounds.top / density).dp)
                    .requiredSize((bounds.width / density).dp, (bounds.height / density).dp)
                    .testTag("default-refraction-reference"), frame.source,
                    refraction = frame.power, pressFeedback = false, shadow = false)
            }
        }
    }

    fun capture(compose: ComposeTestRule, node: SemanticsNodeInteraction, power: Float): PixelMap {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            if (reference == null) {
                val draw = requireNotNull(draws.values.singleOrNull { draw ->
                    val corners = draw.rootCorners ?: return@singleOrNull false
                    draw.visible && corners.size == 8 && abs(corners[0] - bounds.left) < .01f &&
                        abs(corners[1] - bounds.top) < .01f && abs(draw.width - bounds.width) < .01f &&
                        abs(draw.height - bounds.height) < .01f
                }) { "The real caller needs one matching material draw" }
                assertEquals("The caller uses default refraction", 1f, draw.refraction, 0f)
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FrostedBackdrop, Boolean>())
                fun find(source: FrostedBackdrop): FrostedBackdrop? {
                    if (!seen.add(source)) return null
                    if (System.identityHashCode(source) == draw.source?.identity) return source
                    return (listOfNotNull(source.parent) + source.recordedChildren + source.recordedLayoutChildren)
                        .firstNotNullOfOrNull(::find)
                }
                val source = requireNotNull(find(recording)) { "The caller's exact sampled source must remain recorded" }
                assertTrue(source.recorded)
                assertEquals("The reference keeps the caller's polarity", source.lightMix, draw.toneLightMix, 0f)
                reference = Reference(bounds, source, power)
            } else reference = reference!!.copy(power = power)
        }
        compose.mainClock.advanceTimeBy(1200)
        return compose.onNodeWithTag("default-refraction-reference").captureToImage().toPixelMap()
    }

    fun assertDisplacement(actual: PixelMap, reference: PixelMap, threshold: Float, minimum: Int) {
        assertEquals(actual.width, reference.width)
        assertEquals(actual.height, reference.height)
        var displaced = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            val a = actual[x, y].let { it.red - it.blue }
            val b = reference[x, y].let { it.red - it.blue }
            if (a * b < 0f && abs(a - b) > threshold) displaced++
        }
        assertTrue("Default material must displace colored landmarks, changed=$displaced", displaced > minimum)
    }
}
