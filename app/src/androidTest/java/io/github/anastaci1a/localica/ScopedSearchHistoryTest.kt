package io.github.anastaci1a.localica

import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScopedSearchHistoryTest {
    @get:Rule val compose = createComposeRule()
    private val editor get() = compose.onNode(hasSetTextAction())

    private fun fixture(test: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = context.getSharedPreferences("interface", 0)
        val browse = context.getSharedPreferences("browse-style", 0)
        settings.edit().clear().commit(); browse.edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Albums), reducedMotion = true))
        val tracks = (0..79).map { n -> Track("history:$n", "Song $n", album = "Album " + n.toString().padStart(3, '0'),
            fileName = "$n.mp3", relativePath = "$n.mp3") }
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaybackState(connected = true),
                {}, {}, {}, {}, {}, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Album 000").fetchSemanticsNodes().isNotEmpty() }
            open()
            test()
        } finally { settings.edit().clear().commit(); browse.edit().clear().commit() }
    }

    private fun open() {
        compose.onNodeWithTag("list-search-pill").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
    }

    private fun endEditing(action: () -> Unit) {
        val view = (editor.fetchSemanticsNode().root as ViewRootForTest).view
        val decor = view.rootView as ViewGroup
        lateinit var observer: View
        val animations = mutableSetOf<WindowInsetsAnimationCompat>()
        var observed: WindowInsetsCompat? = null
        compose.runOnUiThread {
            // Independent first child observes legacy dispatch before content consumes it.
            // It never replaces Compose callbacks and returns insets unchanged.
            observer = View(view.context)
            ViewCompat.setOnApplyWindowInsetsListener(observer) { _, insets -> observed = insets; insets }
            ViewCompat.setWindowInsetsAnimationCallback(observer, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animations += animation
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    observed = insets
                    animations.clear()
                    animations.addAll(runningAnimations.filter { it.typeMask and WindowInsetsCompat.Type.ime() != 0 })
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) { animations -= animation }
            })
            decor.addView(observer, 0, ViewGroup.LayoutParams(0, 0))
            ViewCompat.requestApplyInsets(observer)
        }
        try {
            compose.waitUntil(5000) { compose.runOnUiThread { observed != null && view.hasWindowFocus() } }
            action()
            compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(5000) { compose.runOnUiThread {
                val native = ViewCompat.getRootWindowInsets(view)
                fun WindowInsetsCompat.hidden() = !isVisible(WindowInsetsCompat.Type.ime()) && getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
                view.hasWindowFocus() && observed?.hidden() == true && native?.hidden() == true && animations.isEmpty()
            } }
        } catch (failure: Throwable) {
            runCatching {
                compose.runOnUiThread { android.util.Log.e("ScopedSearchHistory", "focus=${view.hasWindowFocus()} ime=${observed?.isVisible(WindowInsetsCompat.Type.ime())} animations=${animations.size}") }
                android.util.Log.e("ScopedSearchHistory", compose.onAllNodes(isRoot()).printToString())
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            compose.runOnUiThread {
                ViewCompat.setWindowInsetsAnimationCallback(observer, null)
                ViewCompat.setOnApplyWindowInsetsListener(observer, null)
                decor.removeView(observer)
            }
        }
    }

    private fun submit(text: String) {
        editor.performClick().performTextReplacement(text)
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        endEditing { editor.performImeAction() }
    }

    private fun backTo(text: String) {
        compose.awaitExpectedWindowFocus(editor)
        endEditing { pressSystemBack() } // Exactly one native Back per historical transition.
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().size == 1 }
        editor.assertTextContains(text).assertIsNotFocused()
        compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
    }

    private fun awaitClosed() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("list-search-popup").fetchSemanticsNodes().isEmpty()
    }

    @Test fun completedQueriesRestoreTheirPositionsAndRootBackClosesWithoutKeyboard() = fixture {
        submit("Album")
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Album 060"))
        val firstPosition = compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot
        submit("Album 0")
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Album 040"))
        val secondPosition = compose.onNodeWithText("Album 040").fetchSemanticsNode().boundsInRoot
        submit("Album 01")
        backTo("Album 0")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Album 040").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(secondPosition, compose.onNodeWithText("Album 040").fetchSemanticsNode().boundsInRoot)
        backTo("Album")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Album 060").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(firstPosition, compose.onNodeWithText("Album 060").fetchSemanticsNode().boundsInRoot)
        pressSystemBack()
        awaitClosed()
    }

    @Test fun draftBackEndsEditingBeforeRestoringCompletedQueryAndEmptyBackCloses() = fixture {
        submit("Album 01")
        editor.performClick().performTextReplacement("Album 02")
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.awaitExpectedWindowFocus(editor)
        endEditing { pressSystemBack() }
        editor.assertTextContains("Album 02")
        compose.onNodeWithTag("list-search-popup").assertIsDisplayed()
        backTo("Album 01")
        editor.performClick().performTextReplacement("")
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.awaitExpectedWindowFocus(editor)
        pressSystemBack() // Empty scoped search closes even with completed history.
        awaitClosed()
        open()
        editor.assertTextEquals("")
        pressSystemBack()
        awaitClosed()
    }

    @Test fun outsideTapDismissesWholeHistoryInsteadOfRetracingIt() = fixture {
        submit("Album 01")
        submit("Album 02")
        compose.awaitExpectedWindowFocus(editor)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Search panels have horizontal safe margins; the window scrim owns this point.
        assertTrue("Outside coordinate must miss the search surface",
            compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot.left > 2f)
        assertTrue(device.click(2, device.displayHeight / 2))
        awaitClosed()
        open()
        editor.assertTextEquals("")
        pressSystemBack()
        awaitClosed()
    }
}
