package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMorphSubpixelTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun menuKeepsItsAnchoredEdgesFixedThroughoutGrowthAndReturn() = sweep(GlassPlacement.Menu)
    @Test fun centeredPopupMovesEachEdgeMonotonicallyAndLandsExactly() = sweep(GlassPlacement.Center)

    private fun sweep(placement: GlassPlacement) {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        var plane: ShadowPlane? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                anchor = rememberGlassAnchor()
                val density = LocalDensity.current
                val width = with(density) { (64.dp.roundToPx() + 401).toDp() }
                val height = with(density) { (64.dp.roundToPx() + 301).toDp() }
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                    Box(Modifier.align(Alignment.BottomEnd).size(64.dp).glassAnchor(anchor))
                    GlassMorphPopup(open, anchor, { open = false }, placement, fitContent = true) {
                        val native = LocalShadowPlane.current
                        SideEffect { plane = native }
                        Box(Modifier.width(width).height(height))
                    }
                }
            }
        } }
        fun bounds(): Rect {
            val semantics = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode()
            val view = (semantics.root as ViewRootForTest).view
            val location = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(location) }
            return semantics.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
        }
        val home = compose.runOnIdle { anchor.bounds }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = true }
        var rectangle: MotionRectView? = null
        var opening = 0; var returning = 0
        var expanded: Rect? = null
        try {
            repeat(120) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val owner = compose.runOnUiThread { plane?.casters?.singleOrNull() }
                if (anchor.hidden && owner != null) {
                    val rect = owner.transform
                    if (rectangle == null) rectangle = rect else assertSame(rectangle, rect)
                    val pixels = compose.runOnUiThread { glassMorphPixelBounds(rect.value()) }
                    assertEquals("Native layout uses its one edge-rounded owner", Rect(pixels.left.toFloat(), pixels.top.toFloat(),
                        pixels.right.toFloat(), pixels.bottom.toFloat()), bounds())
                    if (anchor.contact.expansion.value in .001f.. .999f) opening++
                    if (!anchor.contact.expansion.isRunning) expanded = bounds()
                }
            }
            assertTrue("Actual expansion has intermediate native frames", opening >= 3)
            val end = requireNotNull(expanded)
            // Keep the independent 101-position quantization corpus; it no longer
            // writes a second phase into a live Engine-owned popup.
            val corpus = (0..100).map { glassMorphPixelBounds(lerp(home, end, it / 100f)) }
            assertEquals(glassMorphPixelBounds(home), corpus.first())
            assertEquals(glassMorphPixelBounds(end), corpus.last())
            for (edge in listOf<(MotionPixelRect)->Int>({ it.left }, { it.top }, { it.right }, { it.bottom })) {
                val values = corpus.map(edge); val rising = values.last() >= values.first()
                values.zipWithNext().forEach { (a,b) -> assertTrue(if (rising) b >= a else b <= a) }
            }
            if (placement == GlassPlacement.Menu) corpus.forEach {
                assertEquals(home.right.toInt(), it.right); assertEquals(home.bottom.toInt(), it.bottom)
            }
            compose.runOnIdle { open = false }
            repeat(120) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                if (anchor.contact.expansion.value in .001f.. .999f) {
                    val pixels = compose.runOnUiThread { glassMorphPixelBounds(requireNotNull(rectangle).value()) }
                    assertEquals("Return uses those same physical edges", Rect(pixels.left.toFloat(), pixels.top.toFloat(),
                        pixels.right.toFloat(), pixels.bottom.toFloat()), bounds())
                    returning++
                }
            }
            assertTrue("Actual return has intermediate native frames", returning >= 3)
            assertFalse(anchor.modalPresent)
        } finally { compose.mainClock.autoAdvance = true; compose.runOnIdle { open = false }; compose.waitForIdle() }
    }
}
