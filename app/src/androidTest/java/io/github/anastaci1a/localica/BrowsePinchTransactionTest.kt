package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowsePinchTransactionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rejectedStepOwnsGestureButNeverReceivesProgressOrRelease() {
        var attempts = 0
        var progress = 0
        var releases = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("pinch").pinchToChangeView(true,
                onProgress = { progress++ }, onRelease = { releases++ }) { _, _ -> attempts++; false })
        }
        compose.onNodeWithTag("pinch").performTouchInput { begin(true); spread(110f); spread(50f); spread(120f); up(0); up(1) }
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(0, progress); assertEquals(0, releases) }
    }

    @Test fun canceledEndpointsCannotLeakTargetsIntoBoundaryPinchesOrLaterSettings() {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val compact = BrowseStyle(listSize = ListSize.Compact)
        val gallery = BrowseStyle(presentation = Presentation.Large, itemsPerRow = 1)
        prefs.write(route, compact, false)
        try {
            val tracks = (0..29).map { Track("transaction:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = Dispatchers.Unconfined)
            } }
            for ((source, outward, label) in listOf(Triple(compact, true, "List, Compact"), Triple(gallery, false, "Gallery, 1 column"))) {
                compose.runOnIdle { prefs.write(route, source, false) }
                waitForView(label)
                val list = compose.onNodeWithTag("browser-list")
                list.performTouchInput {
                    begin(outward)
                    spread(if (outward) 110f else 50f)
                    spread(if (outward) 50f else 110f)
                    up(0); up(1)
                }
                waitForView(label)
                assertEquals(source, prefs.read(route))
                list.performTouchInput { begin(!outward); spread(if (outward) 50f else 110f); up(0); up(1) }
                compose.waitForIdle()
                waitForView(label)
                assertEquals(source, prefs.read(route))
            }
            compose.runOnIdle { prefs.write(route, BrowseStyle(listSize = ListSize.Normal), false) }
            waitForView("List, Normal")
            compose.onNodeWithTag("browser-list").performTouchInput { begin(true); spread(110f); up(0); up(1) }
            waitForView("List, Large")
            assertEquals(ListSize.Large, prefs.read(route).listSize)
        } finally { prefs.write(route, original, overridden) }
    }

    @Test fun pointerCancellationRestoresPreviewWithoutSavingAndClearsTransaction() {
        var style by mutableStateOf(BrowseStyle(listSize = ListSize.Compact))
        val source = style
        val motion = BrowsePinchMotion()
        var commits = 0
        compose.setContent {
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().testTag("pinch").browsePinch(true, motion, style,
                BrowseRoute(MainTab.Songs), rememberLazyGridState(), 800, density, rememberCoroutineScope(), false,
                onPreview = { style = it }, onCommit = { commits++; style = it }, onAnchor = {}))
        }
        val node = compose.onNodeWithTag("pinch")
        node.performTouchInput { begin(true); spread(110f) }
        compose.waitUntil(5000) { motion.source != null && style != source }
        // A second transaction cannot start while cancellation is handing the
        // source layout back, even before recomposition updates enabled state.
        node.performTouchInput { cancel(); begin(true); spread(110f); up(0); up(1) }
        compose.waitUntil(5000) { motion.source == null }
        compose.runOnIdle {
            assertEquals(source, style); assertEquals(0, commits)
            assertNull(motion.target); assertNull(motion.focusKey)
            assertTrue(motion.frozen.isEmpty()); assertTrue(motion.bounds.isEmpty())
            assertFalse(motion.held); assertFalse(motion.capturing)
        }
        node.performTouchInput { begin(true); spread(110f); up(0); up(1) }
        compose.waitUntil(5000) { motion.source == null && commits == 1 }
        compose.runOnIdle { assertEquals(ListSize.Normal, style.listSize) }
    }

    @Test fun routeChangeCancelsOldOwnerAndDoesNotReleaseIntoNewCallbacks() {
        var route by mutableStateOf(BrowseRoute(MainTab.Songs))
        var style by mutableStateOf(BrowseStyle(listSize = ListSize.Compact))
        val owners = mutableMapOf<String, BrowsePinchMotion>()
        val commits = mutableListOf<String>()
        compose.setContent {
            val currentRoute = route
            val motion = remember(currentRoute.key) { BrowsePinchMotion().also { owners[currentRoute.key] = it } }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().testTag("pinch").browsePinch(true, motion, style,
                currentRoute, rememberLazyGridState(), 800, density, rememberCoroutineScope(), false,
                onPreview = { style = it }, onCommit = { commits += currentRoute.key; style = it }, onAnchor = {}))
        }
        val node = compose.onNodeWithTag("pinch")
        val oldKey = route.key
        node.performTouchInput { begin(true); spread(110f) }
        compose.waitUntil(5000) { owners[oldKey]?.held == true }
        compose.runOnIdle { route = BrowseRoute(MainTab.Albums); style = BrowseStyle(listSize = ListSize.Compact) }
        node.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(commits.isEmpty()); assertTrue(owners.getValue(oldKey).disposed)
            assertNull(owners.getValue(oldKey).source); assertNull(owners.getValue(oldKey).target)
            assertEquals(ListSize.Compact, style.listSize)
        }
        node.performTouchInput { begin(true); spread(110f); up(0); up(1) }
        compose.waitUntil(5000) { commits.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(route.key), commits); assertEquals(ListSize.Normal, style.listSize) }
    }

    private fun waitForView(label: String) {
        compose.waitUntil(5000) { compose.onNodeWithTag("browser-list").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == label }
        compose.waitForIdle()
    }
    private fun TouchInjectionScope.begin(outward: Boolean) {
        val span = if (outward) 50f else 110f
        down(0, Offset(centerX - span, centerY)); down(1, Offset(centerX + span, centerY))
    }
    private fun TouchInjectionScope.spread(span: Float) {
        moveTo(0, Offset(centerX - span, centerY), delayMillis = 32)
        moveTo(1, Offset(centerX + span, centerY), delayMillis = 32)
    }
}
