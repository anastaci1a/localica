package io.github.anastaci1a.localica
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
class PopupSelectionStackingReferenceTest {
    @get:Rule val compose = localicaComposeRule()
    private val frames by lazy { PopupWindowEvidence(compose) }
    @org.junit.After fun removeMarkers() = frames.dispose()
    @Test fun normalSelectionPillPixelsCoverOverlappingTabs() = selectionOverlap(GlassRendererMode.Normal)
    @Test fun singleToneSelectionPillPixelsCoverOverlappingTabs() = selectionOverlap(GlassRendererMode.SingleToneBlur)
    private fun selectionOverlap(mode: GlassRendererMode) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        val output = File(nativeEvidenceDirectory("popup-chrome-depth", context.getExternalFilesDir(null)),
            "${mode.name}/selection-stacking").apply { mkdirs() }
        lateinit var host: View
        var tabsVisible by mutableStateOf(false)
        var selectionVisible by mutableStateOf(true)
        val track = Track("identity:overlap", "Overlap song", fileName = "overlap.mp3", relativePath = "overlap.mp3")
        try {
            preferences.write(InterfaceSettings(reducedMotion = true, glass = GlassAppearance(renderer = mode)))
            compose.setContent { LocalicaTheme {
                host = LocalView.current
                val home = rememberGlassPopupHome(false)
                val page = rememberFrostedBackdrop(home, dark = false)
                val panel = rememberFloatingSelectionPanel(selectionVisible, true)
                CompositionLocalProvider(LocalFrostedBackdrop provides page,
                    LocalGlassAppearance provides GlassAppearance(renderer = mode,
                        tabStyle = io.github.anastaci1a.localica.navigation.TabLensStyle.Classic,
                        classicOpacity = 1f, blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // Deliberate collision, as in SelectorStackingTest: use the
                        // shell's real shared controls and ordering with no motion override.
                        Box(Modifier.size(308.dp, 180.dp)) {
                        R1ReferenceHome(home, chrome = {
                            if (tabsVisible) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp).testTag("overlap-tabs")) {
                                SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = page) {}
                            }
                            FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                SelectionActions(listOf(io.github.anastaci1a.localica.library.SongRow(track.id, track)), setOf(track.id), {}, null, null, PlaylistState(loading = false),
                                    minimized = true, onExpand = {})
                            }
                        }) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().recordFrostedBackdrop(page).background(androidx.compose.ui.graphics.Color.Blue))
                                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White))
                            }
                        }
                        }
                    }
                }
            } }
            compose.onNodeWithTag("selection-minimized").assertIsDisplayed()
            val pill = compose.onNodeWithTag("selection-minimized").fetchSemanticsNode().boundsInRoot
            val activity = compose.runOnUiThread {
                var owner = host.context
                while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
                (owner as Activity).window
            }
            val alone = original(activity, output, "selection-only")
            try {
                compose.runOnIdle { tabsVisible = true; selectionVisible = false }
                compose.waitForIdle()
                val tabs = original(activity, output, "tabs-only")
                try {
                    compose.runOnIdle { selectionVisible = true }
                    compose.onNodeWithTag("selection-minimized").assertIsDisplayed()
                    val combined = original(activity, output, "selection-over-tabs")
                    try {
                        val tab = compose.onNodeWithTag("overlap-tabs").fetchSemanticsNode().boundsInRoot
                        val location = IntArray(2).also { compose.runOnUiThread { host.getLocationInWindow(it) } }
                        val overlap = pill.intersect(tab)
                        var discriminating = 0
                        var wrong = 0
                        for (y in overlap.top.toInt() until overlap.bottom.toInt()) for (x in overlap.left.toInt() until overlap.right.toInt()) {
                            val px = x + location[0]; val py = y + location[1]
                            val expected = alone.getPixel(px, py)
                            val behind = tabs.getPixel(px, py)
                            if (Color.blue(expected) > Color.red(expected) + 40 && abs(Color.blue(expected) - Color.blue(behind)) > 40) {
                                discriminating++
                                if (combined.getPixel(px, py) != expected) wrong++
                            }
                        }
                        File(output, "stacking.json").writeText(JSONObject().put("source", BuildConfig.SOURCE_SHA)
                            .put("overlap", overlap.toString()).put("discriminatingPixels", discriminating)
                            .put("wrongForegroundPixels", wrong).toString(2))
                        assertTrue("Selection stacking fixture reaches real overlapping control pixels", discriminating >= 100)
                        assertEquals("S3: selection pill pixels cover tabs at the shell sibling depth", 0, wrong)
                    } finally { combined.recycle() }
                } finally { tabs.recycle() }
            } finally { alone.recycle() }
        } finally { preferences.write(previous) }
    }

    private fun original(window: Window, output: File, name: String): Bitmap = frames.capture(window, output, name)
}
