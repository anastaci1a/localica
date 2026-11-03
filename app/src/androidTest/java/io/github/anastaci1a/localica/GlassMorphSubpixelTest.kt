package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassMorphSubpixelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuKeepsItsAnchoredEdgesFixedThroughoutGrowthAndReturn() = sweep(GlassPlacement.Menu)
    @Test fun centeredPopupMovesEachEdgeMonotonicallyAndLandsExactly() = sweep(GlassPlacement.Center)

    private fun sweep(placement: GlassPlacement) {
        var open by mutableStateOf(false)
        lateinit var anchor: GlassAnchor
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                scope = rememberCoroutineScope()
                anchor = rememberGlassAnchor()
                val density = LocalDensity.current
                // Odd physical deltas expose round(position) + round(size) at p = .5.
                val targetWidth = with(density) { (64.dp.roundToPx() + 401).toDp() }
                val targetHeight = with(density) { (64.dp.roundToPx() + 301).toDp() }
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                    Box(Modifier.align(Alignment.BottomEnd).size(64.dp).glassAnchor(anchor))
                    GlassMorphPopup(open, anchor, { open = false }, placement, fitContent = true) {
                        Box(Modifier.width(targetWidth).height(targetHeight))
                    }
                }
            }
        } }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("glass-morph-surface")
        fun bounds(): Rect {
            val semantics = node.fetchSemanticsNode()
            val view = (semantics.root as ViewRootForTest).view
            val location = IntArray(2)
            compose.runOnUiThread { view.getLocationOnScreen(location) }
            return semantics.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
        }
        val expanded = bounds()
        val home = anchor.bounds
        compose.mainClock.autoAdvance = false
        fun snap(p: Float): Rect {
            compose.runOnIdle {
                val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { anchor.contact.expansion.snapTo(p) }
                assertTrue(update.isCompleted)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            return bounds()
        }
        try {
            val frames = (0..100).map { snap(it / 100f) }
            fun monotone(label: String, values: List<Float>) {
                val rising = values.last() >= values.first()
                values.zipWithNext().forEach { (a, b) -> assertTrue("$placement $label reverses: $a -> $b",
                    if (rising) b >= a else b <= a) }
            }
            monotone("left", frames.map { it.left }); monotone("right", frames.map { it.right })
            monotone("top", frames.map { it.top }); monotone("bottom", frames.map { it.bottom })
            assertEquals("Exact trigger endpoint", home, frames.first())
            assertEquals("Resting popup pixels unchanged", expanded, frames.last())
            if (placement == GlassPlacement.Menu) frames.forEach {
                assertEquals(home.right, it.right, 0f); assertEquals(home.bottom, it.bottom, 0f)
            }
            for (sample in 100 downTo 0) assertEquals("Return uses the identical rectangle at $sample",
                frames[sample], snap(sample / 100f))
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { open = false }
            compose.waitForIdle()
        }
    }
}
