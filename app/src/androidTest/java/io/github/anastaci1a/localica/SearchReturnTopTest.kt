package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.localicaAndroidComposeRule as createAndroidComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual scoped popup, native IME and measured return geometry. */
class SearchReturnTopTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val songs = (0..79).map { Track("return-search:$it", "Song ${it.toString().padStart(3, '0')}",
        album = "Return album", artist = "Return artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
    private val route = BrowseRoute(MainTab.Songs, key = "return-search-690")
    private var sample: SearchMorphSample? = null
    private val list get() = compose.onNodeWithTag("browser-list")
    private val arrow get() = compose.onNodeWithContentDescription("Return to top")

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun fixture(popup: Boolean) {
        val prefs = BrowsePreferences(instrumentation.targetContext)
        prefs.write(route, BrowseStyle(quickSearch = popup), false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSearchMorphObserver provides { sample = it }) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(
                    folders = listOf(FolderSelection("return-search-root", "Music")), tracks = songs), loading = false),
                    PlaylistState(loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-return-search:0").fetchSemanticsNodes().isNotEmpty() }
        if (popup) {
            compose.onNodeWithTag("list-search-pill").performClick()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("list-search-popup"))
            compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction()).performTextReplacement("Song")
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("media-return-search:1").fetchSemanticsNodes().size == 1 &&
                    compose.runOnUiThread { sample?.let { it.ime > 0 && it.nativeIme > 0 && it.ime == it.imeTarget } == true }
            }
        }
        compose.waitForIdle()
    }

    private fun hiddenIme() {
        compose.awaitSearchImeHandoff { compose.onNode(hasSetTextAction()).performImeAction() }
        compose.waitUntil(5000) { compose.runOnUiThread { sample?.let { it.ime == 0 && it.nativeIme == 0 } == true } }
        compose.waitForIdle()
    }

    private fun withKeyboard(block: () -> Unit) {
        val original = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            prepareNativeDockedKeyboard(compose, mutableListOf(), ::shell)
            block()
        } finally {
            if (original == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $original")
            compose.awaitSystemImeHardKeyboardPolicy(::shell, (original.toIntOrNull() ?: 0) != 0)
        }
    }

    @Test fun popupAtVisualTopNeverOffersReturn() = withKeyboard {
        fixture(true)
        arrow.assertDoesNotExist()
        hiddenIme()
        arrow.assertDoesNotExist()
    }

    @Test fun popupTapReachesTopAndRetiresReturnDrawing() = withKeyboard {
        fixture(true); hiddenIme()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
        arrow.assertDoesNotExist()
        compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
    }

    @Test fun popupManualReturnRetiresTheArrow() = withKeyboard {
        fixture(true); hiddenIme()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed()
        list.performScrollToIndex(0)
        arrow.assertDoesNotExist()
        compose.onNodeWithTag("floating-return-control", true).assertDoesNotExist()
    }

    @Test fun popupReturnHasBottomGapWithNativeIme() = withKeyboard {
        fixture(true)
        assertGap(true)
    }

    @Test fun popupReturnHasBottomGapWithoutNativeIme() = withKeyboard {
        fixture(true); hiddenIme()
        assertGap(false)
    }

    private fun assertGap(ime: Boolean) {
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed()
        val popup = compose.onNodeWithTag("list-search-popup").fetchSemanticsNode().boundsInRoot
        val button = arrow.fetchSemanticsNode().boundsInRoot
        val expected = with(compose.density) { FloatingSurfaceGap.toPx() }
        assertEquals("Inset is the shared floating surface gap; IME=$ime", expected, popup.bottom - button.bottom, 1f)
        val current = compose.runOnUiThread { requireNotNull(sample) }
        assertEquals("Native popup owns the expected keyboard state", ime, current.nativeIme > 0)
        if (ime) {
            val keyboard = instrumentation.uiAutomation.windows.single { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val bounds = android.graphics.Rect().also(keyboard::getBoundsInScreen)
            assertTrue("Popup ends above the actual keyboard: $current / $bounds", current.bounds.bottom <= bounds.top + 1f)
        }
    }

    @Test fun browseWithEmptyNoticeHidesAtTopAndAfterTap() {
        fixture(false)
        arrow.assertDoesNotExist()
        list.performScrollToIndex(50)
        arrow.assertIsDisplayed().performClick()
        compose.onNodeWithTag("media-return-search:0").assertIsDisplayed()
        arrow.assertDoesNotExist()
        list.performScrollToIndex(50)
        list.performScrollToIndex(0)
        arrow.assertDoesNotExist()
    }
}
