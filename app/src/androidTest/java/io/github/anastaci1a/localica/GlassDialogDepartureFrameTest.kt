package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #812: a conditional Page caller's window must depart in the frame its dismissal commit
 * removes the caller, as library and settings hosts do, not one frame later at disposal.
 * The real host and dialog run on the test clock; only the native window is replaced by a recorder. */
class GlassDialogDepartureFrameTest {
    @get:Rule val compose = createComposeRule()

    /** Every composition of a window, as (clock of its frame, the visibility it presented). */
    private val presented = mutableListOf<Pair<Long, Boolean>>()
    private lateinit var host: GlassDialogs

    private fun present(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GlassDialogHost(content = {
                host = checkNotNull(LocalGlassDialogs.current)
                content()
            }) { entry -> presented += compose.mainClock.currentTime to entry.present }
        }
        repeat(3) { frame() }
        compose.runOnIdle { assertEquals("The window is presented before dismissal", listOf(true), presented.map { it.second }.distinct()) }
    }

    /** Advance one frame and return its clock. */
    private fun frame(): Long {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return compose.mainClock.currentTime
    }

    private fun firstHidden() = compose.runOnIdle { presented.firstOrNull { !it.second }?.first }

    @Test fun honouredPageDismissalDepartsInTheCommitFrame() {
        var page by mutableStateOf(true)
        present { if (page) GlassDialog({ page = false }, GlassPlacement.Page) {} }
        val entry = compose.runOnIdle { host.entries.single().also { assertTrue(it.precedesWindow); it.requestDismiss() } }
        val commit = frame()
        assertFalse(page)
        assertEquals("The window reads the departure in the frame that removes its caller", commit, firstHidden())
        repeat(2) { frame() }
        compose.runOnIdle {
            assertFalse(entry.mounted)
            assertTrue(presented.filter { it.first >= commit }.none { it.second })
        }
    }

    /** Control: a removal that bypasses the commit is seen only after disposal, the lag the commit removes. */
    @Test fun removalWithoutTheCommitStillDepartsAfterDisposal() {
        var page by mutableStateOf(true)
        present { if (page) GlassDialog({ page = false }, GlassPlacement.Page) {} }
        compose.runOnIdle { page = false }
        val removal = frame()
        val disposal = frame()
        frame()
        assertTrue(disposal > removal)
        assertEquals(disposal, firstHidden())
    }

    @Test fun rejectedDismissalNeverPresentsADeparture() {
        var attempts = 0
        present { GlassDialog({ attempts++ }, GlassPlacement.Page) {} }
        val entry = compose.runOnIdle { host.entries.single() }
        repeat(2) {
            compose.runOnIdle { entry.requestDismiss() }
            repeat(3) { frame() }
        }
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertNull("A caller that keeps its dialog never sees it leave", presented.firstOrNull { !it.second })
            assertFalse(entry.dismissing)
            assertTrue(entry.present)
        }
    }

    @Test fun reopeningAfterAnHonouredDismissalPresentsANewWindow() {
        var page by mutableStateOf(true)
        present { if (page) GlassDialog({ page = false }, GlassPlacement.Page) {} }
        val entry = compose.runOnIdle { host.entries.single().also { it.requestDismiss() } }
        frame()
        compose.runOnIdle { page = true }
        repeat(3) { frame() }
        compose.runOnIdle {
            // A new Page caller owns a new anchor, so it opens its own entry.
            val reopened = host.entries.last()
            assertNotSame(entry, reopened)
            assertFalse(reopened.dismissing)
            assertTrue(reopened.present)
        }
    }
}
