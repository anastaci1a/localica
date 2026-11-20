package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

/** Verify a unit-power reference on foreground-free material bands before changing only power. */
internal class DefaultRefractionFixture {
    private data class Reference(val bounds: Rect, val material: Rect, val source: FrostedBackdrop, val power: Float, val draw: GlassSurfaceDrawObservation)
    private var reference by mutableStateOf<Reference?>(null)
    private lateinit var recording: FrostedBackdrop
    private var materialHeight = 0f
    private var materialPixels = emptyList<Pair<Int, Int>>()
    private val draws = mutableMapOf<Int, GlassSurfaceDrawObservation>()

    @Composable fun Scene(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
        recording = rememberFrostedBackdrop(Unit, enabled = false)
        val density = LocalDensity.current.density
        val height = with(LocalDensity.current) { listPillHeight().toPx() }
        SideEffect { materialHeight = height }
        Box(modifier.recordFrostedBackdrop(recording)) {
            CompositionLocalProvider(LocalGlassSurfaceDrawObserver provides { receipt ->
                    glassDrawProbe?.invoke(receipt)
                    if (receipt is GlassSurfaceDrawObservation) draws[receipt.surfaceIdentity] = receipt
                }) { content() }
            reference?.let { frame ->
                val origin = requireNotNull(recording.location.coordinates).positionInRoot()
                val bounds = frame.bounds.translate(-origin)
                Box(Modifier.absoluteOffset((bounds.left / density).dp, (bounds.top / density).dp)
                    .requiredSize((bounds.width / density).dp, (bounds.height / density).dp)
                    .testTag("default-refraction-reference"), contentAlignment = Alignment.Center) {
                    LiquidGlassSurface(Modifier.requiredSize((frame.material.width / density).dp, (frame.material.height / density).dp),
                        frame.source, refraction = frame.power, frostProgress = frame.draw.frost,
                        tint = androidx.compose.ui.graphics.Color(frame.draw.tintArgb), depthScale = frame.draw.depthScale,
                        pressFeedback = false, shadow = false)
                }
            }
        }
    }

    fun capture(compose: ComposeTestRule, node: SemanticsNodeInteraction, power: Float): PixelMap {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            if (reference == null) {
                val material = Rect(bounds.left, bounds.center.y - materialHeight / 2f,
                    bounds.right, bounds.center.y + materialHeight / 2f)
                val draw = requireNotNull(draws.values.singleOrNull { draw ->
                    val corners = draw.rootCorners ?: return@singleOrNull false
                    draw.visible && corners.size == 8 && abs(corners[0] - material.left) < .01f &&
                        abs(corners[1] - material.top) < .01f && abs(draw.width - material.width) < .01f &&
                        abs(draw.height - material.height) < .01f
                }) { "The real caller needs one matching material draw: target=$bounds material=$material draws=${draws.values.map { listOf(it.width, it.height, it.rootCorners) }}" }
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
                reference = Reference(bounds, material, source, power, draw)
            } else reference = reference!!.copy(power = power)
        }
        compose.mainClock.advanceTimeBy(1200)
        return compose.onNodeWithTag("default-refraction-reference").captureToImage().toPixelMap()
    }

    /** Returns the caller captured in the same scene state as its unit reference: mounting the reference can record the shared source. */
    fun establishUnitParity(compose: ComposeTestRule, node: SemanticsNodeInteraction): PixelMap {
        val before = node.captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(1200)
        val settled = node.captureToImage().toPixelMap()
        val unit = capture(compose, node, 1f)
        val actual = node.captureToImage().toPixelMap()
        val frame = requireNotNull(reference)
        assertEquals(actual.width, unit.width); assertEquals(actual.height, unit.height)
        val top = (frame.material.top - frame.bounds.top) * actual.height / frame.bounds.height
        val height = frame.material.height * actual.height / frame.bounds.height
        // Fixed geometric bands exclude the centred text/icon line and rounded corners.
        // The mask is chosen before looking at either image, never by pixel agreement.
        materialPixels = (0 until actual.height).flatMap { y ->
            val fraction = (y + .5f - top) / height
            if (fraction in .08f.. .30f || fraction in .70f.. .92f)
                (minOf(height.toInt(), actual.width / 4) until actual.width - minOf((height / 2f).toInt(), actual.width / 4)).map { x -> x to y }
            else emptyList()
        }
        assertTrue("Measured material bands must contain pixels", materialPixels.isNotEmpty())
        for ((x, y) in materialPixels) {
            val a = actual[x, y]; val b = unit[x, y]
            val delta = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
            assertTrue("The real caller and unit reference must match before power varies ($x,$y delta=$delta)" +
                if (delta <= 1f / 255f) "" else " actual=$a unit=$b target=${frame.bounds} material=${frame.material}" +
                    " original=${frame.draw} current=${draws[frame.draw.surfaceIdentity]}" +
                    " references=${draws.values.filter { it.surfaceIdentity != frame.draw.surfaceIdentity && it.width == frame.draw.width && it.height == frame.draw.height }}",
                delta <= 1f / 255f)
        }
        // Control: the same 1200 ms without the reference must reach the same caller, so the same-state capture is timing only.
        fun drift(a: PixelMap, b: PixelMap) = materialPixels.maxOf { (x, y) -> a[x, y].let { p -> b[x, y].let { q -> maxOf(abs(p.red - q.red), abs(p.green - q.green), abs(p.blue - q.blue)) } } }
        val settledDrift = drift(settled, actual)
        println("DefaultRefractionFixture control settledDrift=${settledDrift * 255} staleDrift=${drift(before, actual) * 255}")
        assertTrue("The caller must settle without the reference (settled drift=${settledDrift * 255}/255, " +
            "drift before the advance=${drift(before, actual) * 255}/255)", settledDrift <= 1f / 255f)
        return actual
    }

    fun assertDisplacement(actual: PixelMap, reference: PixelMap, threshold: Float, minimum: Int) {
        assertEquals(actual.width, reference.width)
        assertEquals(actual.height, reference.height)
        var displaced = 0
        assertTrue("Unit-power parity must precede the displacement count", materialPixels.isNotEmpty())
        for ((x, y) in materialPixels) {
            val a = actual[x, y].let { it.red - it.blue }
            val b = reference[x, y].let { it.red - it.blue }
            if (a * b < 0f && abs(a - b) > threshold) displaced++
        }
        assertTrue("Default material must displace colored landmarks, changed=$displaced", displaced > minimum)
    }
}
