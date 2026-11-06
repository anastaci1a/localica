package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #552: the multi-select pill is the standard Liquid Glass pill, floats over its list, and follows the menu's ink. */
class SelectionPillMaterialTest {
    @get:Rule val compose = createComposeRule()

    private val session = SessionSelection()
    private var darkMenu by mutableStateOf(false)
    private var listHeight = 0
    private lateinit var backdrop: FrostedBackdrop

    private fun lum(c: Color) = .3f * c.red + .59f * c.green + .11f * c.blue

    private fun show() {
        val tracks = (0..2).map { Track("t$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        compose.setContent { LocalicaTheme {
            backdrop = rememberFrostedBackdrop(Unit, dark = darkMenu)
            GlassPanelTheme(darkMenu) {
            // The Queue structure: the list fills the viewport and the pill floats over its end.
            Box(Modifier.size(380.dp, 520.dp).testTag("pill-scene")) {
                Box(Modifier.matchParentSize().recordFrostedBackdrop(backdrop).background(if (darkMenu) Color(0xFF202124) else Color(0xFFE9E3F5))) {
                    Column(Modifier.fillMaxSize().onGloballyPositioned { listHeight = it.size.height }.padding(16.dp)) {
                        repeat(10) { Box(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp).background(if (darkMenu) Color(0xFF5A5C66) else Color(0xFFB9A8E0))) }
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides backdrop) {
                    val panel = rememberFloatingSelectionPanel(session.panelVisible, true)
                    FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                        SessionSelectionPanel(session, null, null, PlaylistState(loading = false))
                    }
                }
            }
            }
        } }
    }

    @Test fun pillFloatsOverTheListWithoutChangingItsViewportAndUsesGlassInBothThemes() {
        show()
        compose.waitForIdle()
        val before = listHeight
        for (dark in listOf(false, true)) {
            compose.runOnIdle { session.clear(); darkMenu = dark }
            compose.waitForIdle()
            val tracks = (0..2).map { Track("t$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.runOnIdle { session.select(null, tracks.map { SongRow(it.id, it) }) }
            if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backdrop.snapshot != null }
            compose.waitForIdle()
            assertEquals("The list keeps its height (no new bottom clip edge), dark=$dark", before, listHeight)
            val pill = compose.onNodeWithTag("selection-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val pixels: PixelMap = compose.onNodeWithTag("pill-scene").captureToImage().toPixelMap()
            // Foreground follows the menu: light icons and text on dark menus, dark on light menus.
            var lightest = 0f; var darkest = 1f
            for (x in pill.left.toInt() + 12 until pill.right.toInt() - 12 step 2) for (y in pill.top.toInt() + 8 until pill.bottom.toInt() - 8 step 2) {
                val l = lum(pixels[x, y]); lightest = maxOf(lightest, l); darkest = minOf(darkest, l)
            }
            if (dark) assertTrue("Light ink on a dark menu: $lightest", lightest > .85f)
            else assertTrue("Dark ink on a light menu: $darkest", darkest < .3f)
            // Glass, not a bare outline: the pill face differs from the raw backdrop stripes at its edge zone.
            val x = (pill.left + 6).toInt(); val y = pill.center.y.toInt()
            val outside = lum(pixels[(pill.left - 12).toInt().coerceAtLeast(0), y])
            assertNotEquals("Pill face is a distinct material", outside, lum(pixels[x, y]), .002f)
        }
    }
}
