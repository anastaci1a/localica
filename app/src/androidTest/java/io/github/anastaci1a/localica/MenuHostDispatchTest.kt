package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.LocalReducedMotion
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.layout.menu.local
import io.github.anastaci1a.localica.ui.layout.menu.page
import io.github.anastaci1a.localica.ui.menu.MenuPageHost
import io.github.anastaci1a.localica.ui.pages.PageId
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #757 M0 (DESIGN 3.2) through the real host: a rebuild with equal content and a new callback capture does not
 * recompose the row, and the row then invokes the new callback, not the one it was composed with.
 */
@OptIn(InternalComposeTracingApi::class)
class MenuHostDispatchTest {
    @get:Rule val compose = createComposeRule()

    private var generation by mutableIntStateOf(0)
    private var title by mutableStateOf("Link row")
    private val log = mutableListOf<String>()
    @Volatile private var rows = 0
    @Volatile private var hosts = 0

    /** Counts executed (not skipped) bodies of the binder's row and host composables. */
    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.contains("ui.menu.MenuRow ")) rows++
            if (info.contains("ui.menu.MenuPageHost ")) hosts++
        }
        override fun traceEventEnd() {}
    }

    @After fun clear() { Composer.setTracer(null) }

    private fun show() {
        Composer.setTracer(tracer)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.width(360.dp).fillMaxHeight()) {
                val captured = generation
                MenuPageHost(page(PageId.Specimen) { section("s") {
                    link(local("link", title)) { log += "link@$captured" }
                    toggle(local("toggle", "Toggle row"), false, { log += "toggle@$captured=$it" })
                } }, strict = false)
            }
        } } }
        compose.waitForIdle()
    }

    @Test fun anEqualRebuildWithANewCallbackSkipsTheRowAndDispatchesTheNewCallback() {
        show()
        assertTrue("the tracer sees row bodies (positive control)", rows >= 2)
        compose.onNodeWithText("Link row").performClick()
        compose.waitForIdle()
        assertEquals(listOf("link@0"), log)

        rows = 0; hosts = 0; log.clear()
        generation = 1
        compose.waitForIdle()
        assertTrue("the host re-declared the page", hosts >= 1)
        assertEquals("no row body ran for equal content", 0, rows)
        compose.onNodeWithText("Link row").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("link@1", "toggle@1=true"), log)

        // Positive control for the skip assertion: changed content does run the changed row, and only that row.
        rows = 0
        title = "Renamed link row"
        compose.waitForIdle()
        assertEquals(1, rows)
        compose.onNodeWithText("Renamed link row").assertExists()
    }
}
