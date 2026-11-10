package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #748: every multi-select bar button that opens a glass popup opens it from its own bounds
 * and retracts into them, exactly like the more-actions menu.
 *
 * The popup body is laid out at its settled size and morphed in draw, so layout bounds say
 * nothing about where it is drawn. The proof therefore reads native pixels: a frozen clock
 * steps every frame, a screenshot is taken at each one, and the drawn region is the
 * bounding box of the pixels that differ from a baseline screenshot taken before the press.
 * The first visible frame is the first one with such pixels, the last retracting frame the
 * last one. Each is saved as a PNG named by its trace frame index; every frame, including
 * those where nothing is drawn, is a row of trace.csv. A missing screenshot or a failed
 * write fails the test.
 *
 * The oracle is the morph's own contract: the surface starts and ends at the trigger's
 * bounds, and what it draws around them is the shared shadow field, whose extent is
 * (4 + shadowSpread * 28 + 2) dp. A drawn region may therefore exceed the button by that
 * extent plus one pixel of rounding, and must contain the button's centre.
 */
class PopupAnchorMorphTest {
    @get:Rule val compose = createComposeRule()

    private fun SemanticsNode.screenBounds(): Rect = layoutInfo.coordinates.let { Rect(it.positionOnScreen(), it.size.toSize()) }

    private fun surfaceBounds(): Rect? =
        compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().singleOrNull()?.screenBounds()

    private fun differing(frame: android.graphics.Bitmap, base: android.graphics.Bitmap): Rect? {
        val width = frame.width; val height = frame.height
        require(width == base.width && height == base.height)
        val a = IntArray(width); val b = IntArray(width)
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) {
            frame.getPixels(a, 0, width, 0, y, width, 1); base.getPixels(b, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val p = a[x]; val q = b[x]
                if (p != q && (kotlin.math.abs(((p shr 16) and 255) - ((q shr 16) and 255)) > 6 ||
                        kotlin.math.abs(((p shr 8) and 255) - ((q shr 8) and 255)) > 6 || kotlin.math.abs((p and 255) - (q and 255)) > 6)) {
                    if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private fun runBar(button: String, popupText: String, evidence: String) {
        val rows = listOf(SongRow("a", Track("a", "Alpha", fileName = "a.mp3", relativePath = "a.mp3")))
        var shadowExtent = 0f
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            val density = LocalDensity.current
            val spread = LocalGlassAppearance.current.shadowSpread
            SideEffect { shadowExtent = with(density) { (4f + spread * 28f + 2f).dp.toPx() } + 1f }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SelectionActions(rows, setOf("a"), {}, null, null, PlaylistState(loading = false))
                }
            }
        } } }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = nativeEvidenceDirectory("popup-anchor-748-$evidence", instrumentation.targetContext.getExternalFilesDir(null))
        val trace = StringBuilder("phase,frame,surface_layout,drawn_left,drawn_top,drawn_right,drawn_bottom\n")
        fun grab(): android.graphics.Bitmap {
            // Frozen clock: wait for the presented frame, then capture it natively.
            Thread.sleep(150)
            return checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "$button: native screenshot unavailable" }
        }
        fun save(image: android.graphics.Bitmap, name: String): File {
            val file = File(directory, "$name.png")
            check(file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }) { "$name: PNG write failed" }
            assertTrue("$name must be a nonempty image", file.length() > 0)
            return file
        }
        val home = compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds()
        val baseline = grab()
        save(baseline, "baseline")
        fun contained(drawn: Rect, what: String) {
            val allowed = Rect(home.left - shadowExtent, home.top - shadowExtent, home.right + shadowExtent, home.bottom + shadowExtent)
            assertTrue("$what $drawn must contain the centre of $button $home", drawn.contains(home.center))
            assertTrue("$what $drawn must stay within $button $home plus the shared shadow extent ($shadowExtent px)",
                drawn.left >= allowed.left && drawn.top >= allowed.top && drawn.right <= allowed.right && drawn.bottom <= allowed.bottom)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(button).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            var firstDrawn: Rect? = null
            repeat(60) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val layout = surfaceBounds()
                val image = grab()
                try {
                    val drawn = differing(image, baseline)
                    trace.append("open,$frame,${layout ?: "absent"},${drawn?.left ?: "absent"},${drawn?.top ?: ""},${drawn?.right ?: ""},${drawn?.bottom ?: ""}\n")
                    if (firstDrawn == null && drawn != null) {
                        firstDrawn = drawn
                        save(image, "first-visible-frame-$frame")
                        contained(drawn, "first visible frame $frame")
                    }
                } finally { image.recycle() }
            }
            requireNotNull(firstDrawn) { "$button must draw a popup" }
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
            compose.onNodeWithText(popupText).assertExists()
            pressSystemBack()
            var lastIndex = -1
            var lastDrawn: Rect? = null
            var lastImage: android.graphics.Bitmap? = null
            repeat(60) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val layout = surfaceBounds()
                val image = grab()
                val drawn = differing(image, baseline)
                trace.append("close,$frame,${layout ?: "absent"},${drawn?.left ?: "absent"},${drawn?.top ?: ""},${drawn?.right ?: ""},${drawn?.bottom ?: ""}\n")
                if (drawn != null) { lastImage?.recycle(); lastImage = image; lastIndex = frame; lastDrawn = drawn } else image.recycle()
            }
            val retained = checkNotNull(lastImage) { "dismissal of $button must draw a retracting popup" }
            try { save(retained, "last-retracting-frame-$lastIndex") } finally { retained.recycle() }
            contained(requireNotNull(lastDrawn), "last retracting frame $lastIndex")
            compose.mainClock.advanceTimeBy(2000); compose.waitForIdle()
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
            compose.onNodeWithContentDescription(button).assertExists()
            assertEquals(home, compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds())
        } finally {
            compose.mainClock.autoAdvance = true
            baseline.recycle()
            File(directory, "trace.csv").writeText(trace.toString())
        }
    }

    @Test fun moreActionsMenuExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("More selection actions", "Select all", "more")
    @Test fun addToPlaylistsPopupExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("Add to playlist", "Add to playlists", "add")
}
