package io.github.anastaci1a.localica

import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded reproduction campaign, not a workaround or a retry of a failed tap. */
@RunWith(Parameterized::class)
class ChoiceSingleTapStressTest(private val trial: Int, private val traced: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun freshAlbumsDialogAcceptsItsFirstYearTap() {
        // Parameterized tests get a fresh Compose rule/activity. Once any trial fails,
        // subsequent cases are skipped rather than continuing until a pass is found.
        assumeFalse("Stopped after the first failed single-tap trial", failed.get())
        val label = "trial=$trial traced=$traced"
        val trace: ((String) -> Unit)? = if (traced) ({ Log.i("ChoiceTapTrace", "$label $it"); Unit }) else null
        Log.i("ChoiceSingleTapStress", "start $label")
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalChoiceTapTrace provides trace) {
                    // Preserve the original fixture's route construction and draft lifecycle.
                    BrowseSortDialog(BrowseRoute(MainTab.Albums), BrowseStyle(), false, {}, { _, _ -> }, {})
                }
            } }
            compose.onNodeWithText("Sort by").performClick()
            val year = compose.onNodeWithText("Year").performScrollTo()
            // This is the original pre-tap diagnostic, with no extra native/UI-thread probe.
            Log.i("ChoiceControlsTest", "Year tap bounds=${year.fetchSemanticsNode().boundsInRoot}")
            year.performClick().assertIsSelected()
            Log.i("ChoiceSingleTapStress", "passed $label")
        } catch (failure: Throwable) {
            failed.set(true)
            Log.e("ChoiceSingleTapStress", "failed $label", failure)
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().forEachIndexed { index, node ->
                    val view = (node.root as ViewRootForTest).view
                    val native = compose.runOnUiThread {
                        val screen = IntArray(2).also(view::getLocationOnScreen)
                        "view=${System.identityHashCode(view)} attached=${view.isAttachedToWindow} focused=${view.hasWindowFocus()} screen=${screen.contentToString()} size=${view.width}x${view.height}"
                    }
                    Log.e("ChoiceSingleTapStress", "$label root=$index bounds=${node.boundsInRoot} $native")
                    roots[index].printToLog("ChoiceSingleTapStress")
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        private val failed = AtomicBoolean(false)

        @JvmStatic
        @Parameterized.Parameters(name = "trial={0}, traced={1}")
        fun trials(): List<Array<Any>> = (1..20).flatMap { trial ->
            // Alternate which arm goes first, avoiding a fixed first-session advantage.
            val modes = if (trial % 2 == 1) listOf(false, true) else listOf(true, false)
            modes.map { traced -> arrayOf<Any>(trial, traced) }
        }
    }
}
