package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** #728: four home tabs at the operator's Galaxy A35 width and density (1080 px at
 * 397 dpi, font scale 0.9), sliding one contact across every boundary both ways. */
class SlidingTabsFourTabSlideTest {
    @get:Rule val compose = createComposeRule()
    private val four = listOf(MainTab.Home, MainTab.Library, MainTab.Playlists, MainTab.AlbumArtists)
    private val six = listOf(MainTab.Home, MainTab.Library, MainTab.Songs, MainTab.Albums, MainTab.Playlists, MainTab.AlbumArtists)
    private val a35 = 397f / 160f
    // LocalicaScreen: 12 dp padding each side, 8 dp spacing and the 104 dp utility pill.
    private val pill = (1080f / a35 - 136f).dp
    private var current by mutableStateOf(four)
    private var active by mutableStateOf(MainTab.Home)
    private var slop = 0f
    private var widestLabel = 0f

    private fun fixture(fontScale: Float, tabs: List<MainTab>, selected: MainTab) {
        current = tabs; active = selected
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(a35, fontScale)) {
                slop = LocalViewConfiguration.current.touchSlop
                // Measured independently of SlidingTabs, with the label style it uses.
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                widestLabel = four.maxOf { measurer.measure(AnnotatedString(it.label), style, maxLines = 1, softWrap = false).size.width }.toFloat()
                // Room either side so a slide can continue past the pill, as on the phone.
                Box(Modifier.padding(horizontal = 40.dp)) {
                    Box(Modifier.size(pill, 64.dp).testTag("four-tabs")) {
                        SlidingTabs(current, active, { current.indexOf(active).coerceAtLeast(0).toFloat() }) { active = it }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    /** The operator moved between 4 and 5-6 tabs in one session: leave a scrolled, overflowing bar. */
    private fun fromScrolledSixTabs(fontScale: Float) {
        fixture(fontScale, six, MainTab.AlbumArtists)
        val range = compose.onNodeWithTag("tab-scroll").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Six tabs overflow and are scrolled toward the end", range.value() > 0f)
        compose.runOnIdle { current = four; active = MainTab.Playlists }
        compose.waitForIdle()
    }

    private val host get() = compose.onNodeWithTag("four-tabs")
    private val hostLeft get() = host.fetchSemanticsNode().boundsInRoot.left
    private val width get() = host.fetchSemanticsNode().size.width.toFloat()
    private fun cell(index: Int) = compose.onNodeWithTag("tab-option-$index").fetchSemanticsNode().boundsInRoot
    private val minimumCell get() = maxOf(68f * a35, widestLabel + 32f * a35)
    // Unclipped: an overflowing cell may extend past the visible strip.
    private fun cellWidth(index: Int) = compose.onNodeWithTag("tab-option-$index").fetchSemanticsNode().size.width.toFloat()

    private data class Crossing(val x: Float, val from: Int, val to: Int)

    /** One contact through [points] in 3 px steps; records where the selected tab changes. */
    private fun slide(vararg points: Float): List<Crossing> {
        val y = host.fetchSemanticsNode().size.height / 2f
        host.performTouchInput { down(Offset(points.first(), y)) }
        compose.waitForIdle()
        var last = four.indexOf(active)
        val crossings = mutableListOf<Crossing>()
        points.toList().zipWithNext().forEach { (start, end) ->
            val n = (abs(end - start) / 3f).toInt().coerceAtLeast(1)
            for (i in 1..n) {
                val x = start + (end - start) * i / n
                host.performTouchInput { moveTo(Offset(x, y)) }
                compose.waitForIdle()
                val now = four.indexOf(active)
                assertTrue("Every selection is one of the four tabs, not a stale list ($active)", now >= 0)
                if (now != last) { crossings += Crossing(x, last, now); last = now }
            }
        }
        host.performTouchInput { up() }
        compose.waitForIdle()
        return crossings
    }

    /** Boundary k is the right edge of cell k. Pickup withholds slop in the first direction,
     * so every switch sits one slop from its boundary in that direction, whichever way it crosses. */
    private fun assertMidpoints(label: String, crossings: List<Crossing>, firstDirection: Float, boundaries: IntRange) {
        val tolerance = 4f * a35 + 3f
        val expected = boundaries.associateWith { cell(it).right - hostLeft + slop * firstDirection }
        android.util.Log.i("Tab728", "$label slop=$slop expected=$expected crossings=$crossings")
        for (k in boundaries) {
            val right = crossings.filter { it.from == k && it.to == k + 1 }
            val left = crossings.filter { it.from == k + 1 && it.to == k }
            assertEquals("$label: boundary $k crossed rightward exactly once", 1, right.size)
            assertEquals("$label: boundary $k crossed leftward exactly once", 1, left.size)
            assertEquals("$label: rightward switch at boundary $k", expected.getValue(k), right.single().x, tolerance)
            assertEquals("$label: leftward switch at boundary $k", expected.getValue(k), left.single().x, tolerance)
            assertEquals("$label: no direction-dependent offset at boundary $k", right.single().x, left.single().x, tolerance)
        }
        assertTrue("$label: only adjacent switches", crossings.all { abs(it.to - it.from) == 1 })
    }

    private fun assertFitsAtMeasuredWidths(fontScale: Float) {
        // The product rule: labels at their measured width fit, or four tabs at a normal
        // font scale still get at least 48 dp each.
        val fits = width >= minimumCell * four.size || (fontScale <= 1.15f && width / four.size >= 48f * a35)
        assertTrue("Four tabs fit the A35 pill at font scale $fontScale", fits)
        assertFalse("A fitting strip has no horizontal scroll", SemanticsProperties.HorizontalScrollAxisRange in
            compose.onNodeWithTag("tab-scroll").fetchSemanticsNode().config)
        (0..3).forEach { assertEquals("Cell $it is a quarter of the pill", width / 4f, cellWidth(it), 1.5f) }
    }

    private fun slideAcrossEveryBoundary(label: String) {
        assertFitsAtMeasuredWidths(.9f)
        val c = width / 4f
        val outward = slide(c / 2f, width + 38f * a35, -38f * a35)
        assertEquals("$label: sliding reaches the last tab", 3, outward.maxOfOrNull { it.to })
        assertEquals("$label: sliding back reaches the first tab", MainTab.Home, active)
        assertMidpoints("$label (rightward first)", outward, 1f, 0..2)
        val inward = slide(c * 3.5f, -38f * a35, width + 38f * a35)
        assertEquals("$label: sliding back out reaches the last tab", MainTab.AlbumArtists, active)
        assertMidpoints("$label (leftward first)", inward, -1f, 0..2)
    }

    @Test fun fittingFourTabsFollowTheFingerAcrossEveryBoundary() {
        fixture(.9f, four, MainTab.Home)
        slideAcrossEveryBoundary("fresh four tabs")
    }

    @Test fun fittingFourTabsFollowTheFingerAfterAScrolledSixTabBar() {
        fromScrolledSixTabs(.9f)
        slideAcrossEveryBoundary("six then four tabs")
    }

    @Test fun pressingTheLastTabThenMovingKeepsItAfterAScrolledSixTabBar() {
        fromScrolledSixTabs(.9f)
        val c = width / 4f
        // The recording: press Album Artists, move right past slop, and the lens fell back to Playlists.
        slide(c * 3.5f, c * 3.5f + 3f * slop, width + 38f * a35)
        compose.runOnIdle { assertEquals(MainTab.AlbumArtists, active) }
    }

    @Test fun twiceFontFourTabsOverflowAtMeasuredWidthsAndKeepEveryTabReachable() {
        fixture(2f, four, MainTab.Home)
        assertTrue("2x labels need more than the pill", minimumCell * four.size > width)
        val range = compose.onNodeWithTag("tab-scroll").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals("The strip scrolls by the measured overflow", minimumCell * four.size - width, range.maxValue(), 2f)
        (0..3).forEach { assertEquals("Cell $it keeps the measured label width", minimumCell, cellWidth(it), 1.5f) }
        // #366: the lens drags only within the visible cells, so pulling it past the
        // window and back leaves the first tab selected rather than an offset lens.
        slide(minimumCell / 2f, width + 38f * a35, -38f * a35)
        compose.runOnIdle { assertEquals(MainTab.Home, active) }
        // Every tab stays reachable: scroll the strip, then select.
        for (index in 1..3) {
            compose.onNodeWithTag("tab-option-$index").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(four[index], active) }
        }
    }

    @Test fun twiceFontAfterAScrolledSixTabBarStillOverflowsConsistently() {
        fromScrolledSixTabs(2f)
        val range = compose.onNodeWithTag("tab-scroll").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals("The four-tab strip scrolls by its own measured overflow", minimumCell * four.size - width, range.maxValue(), 2f)
        compose.onNodeWithTag("tab-option-3").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MainTab.AlbumArtists, active) }
        compose.onNodeWithTag("tab-option-0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MainTab.Home, active) }
    }
}
