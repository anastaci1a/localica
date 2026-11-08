package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Actual FolderSettings pages, native window and measured first content, not a replica layout. */
@OptIn(ExperimentalTestApi::class)
class SettingsRootSearchTest {
    @get:Rule val compose = localicaComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var density: Density
    private val receipts = JSONArray()
    private var stage = 0
    private fun header(page: String) = hasContentDescription("Back from settings") and hasAnySibling(hasText(page))
    private fun back(page: String) = compose.onNode(header(page))
    private fun view(page: String): View = (back(page).fetchSemanticsNode().root as ViewRootForTest).view
    private fun output(name: String) = File(nativeEvidenceDirectory("settings-root-search-696", instrumentation.targetContext.getExternalFilesDir(null)), "api${Build.VERSION.SDK_INT}-$name")
    private fun show() {
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current
            var value by remember { mutableStateOf(InterfaceSettings()) }
            FolderSettings(LibraryUiState(loading = false), {}, {}, {},
                interfaceContent = { section -> InterfaceSettingsControls(value, section) { value = it } })
        } }
    }
    /** A native overlay token admits the requested frame without polling its search/content pixels. */
    private fun capture(page: String, name: String) {
        val root = view(page).rootView
        val marker = ColorDrawable(Color.rgb(++stage * 8, 255, 0))
        val origin = IntArray(2)
        var x = 0; var y = 0
        compose.runOnUiThread {
            root.getLocationOnScreen(origin)
            val visible = android.graphics.Rect().also(root::getWindowVisibleDisplayFrame)
            x = maxOf(origin[0], visible.left) + 24
            y = maxOf(origin[1], visible.top) + 24
            marker.setBounds(x - origin[0] - 4, y - origin[1] - 4, x - origin[0] + 4, y - origin[1] + 4)
            root.overlay.add(marker); root.invalidate()
        }
        var image: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                image?.recycle()
                image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val pixel = image!!.getPixel(x, y)
                Color.green(pixel) >= 250 && Color.blue(pixel) <= 4 && abs(Color.red(pixel) - stage * 8) <= 4
            }
            output("$name.png").outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image?.recycle()
            compose.runOnUiThread { root.overlay.remove(marker) }
        }
    }
    private fun assertNoGap(page: String, first: SemanticsNodeInteraction) {
        compose.onNodeWithContentDescription("Search settings input").assertDoesNotExist()
        first.assertIsDisplayed()
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val headerBounds = back(page).fetchSemanticsNode().boundsInRoot
        // Shared header is64dp, centred around its48dp Back action. Content has24dp padding.
        val expected = headerBounds.center.y + with(density) { (32.dp + 24.dp).toPx() }
        val tolerance = with(density) { 1.dp.toPx() }
        receipts.put(JSONObject().put("page", page).put("actualFirstTop", firstBounds.top)
            .put("expectedFirstTop", expected).put("backCenterY", headerBounds.center.y))
        assertEquals("$page first real content starts at the header plus its existing24dp inset; no search gap",
            expected, firstBounds.top, tolerance)
    }
    private fun saveReceipt() { output("bounds.json").writeText(receipts.toString(2)) }

    @Test fun rootAndTwoSubpagesKeepTheNativeWindowAndHaveNoVacantSearchSlot() {
        show()
        compose.onNodeWithContentDescription("Search settings input").assertIsDisplayed()
        val token = compose.runOnUiThread { view("Settings").windowToken }
        capture("Settings", "root")
        compose.onNodeWithText("Interface", substring = false).performClick()
        val appearance = compose.onNode(hasText("Appearance") and hasClickAction())
        assertNoGap("Interface", appearance)
        assertEquals(token, compose.runOnUiThread { view("Interface").windowToken })
        capture("Interface", "interface")
        appearance.performClick()
        assertNoGap("Appearance", compose.onNodeWithText("Adjust secondary text and inactive icons throughout the app."))
        assertEquals(token, compose.runOnUiThread { view("Appearance").windowToken })
        capture("Appearance", "appearance")
        back("Appearance").performClick()
        assertNoGap("Interface", compose.onNode(hasText("Appearance") and hasClickAction()))
        back("Interface").performClick()
        compose.onNodeWithContentDescription("Search settings input").assertIsDisplayed()
        assertEquals(token, compose.runOnUiThread { view("Settings").windowToken })
        saveReceipt()
    }

    @Test fun forwardAndInterruptedBackReverseTheExistingPageMotionAndRetireFocusedInput() {
        show()
        val home = back("Settings").fetchSemanticsNode().boundsInRoot.center.x
        val token = compose.runOnUiThread { view("Settings").windowToken }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Interface", substring = false).performClick()
        compose.mainClock.advanceTimeBy(80)
        assertTrue("Forward keeps the existing right-side entrance", back("Interface").fetchSemanticsNode().boundsInRoot.center.x > home)
        capture("Interface", "forward-80ms")
        back("Interface").performClick()
        compose.mainClock.advanceTimeBy(64)
        assertTrue("Interrupted Back brings root from its existing left side", back("Settings").fetchSemanticsNode().boundsInRoot.center.x < home)
        capture("Settings", "reverse-64ms")
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Search settings input").assertIsDisplayed().performClick()
        compose.onNodeWithText("Interface", substring = false).performClick()
        assertNoGap("Interface", compose.onNode(hasText("Appearance") and hasClickAction()))
        compose.waitUntil(5000) { compose.runOnUiThread {
            ViewCompat.getRootWindowInsets(view("Interface"))?.isVisible(WindowInsetsCompat.Type.ime()) != true
        } }
        assertEquals(token, compose.runOnUiThread { view("Interface").windowToken })
        capture("Interface", "focused-field-retired")
        saveReceipt()
    }
}
