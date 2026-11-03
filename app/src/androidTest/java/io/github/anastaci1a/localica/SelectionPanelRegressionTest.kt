package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs unchanged on the pre-popup baseline; saves the real panel before the assertion. */
class SelectionPanelRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frequentActionsFitInOneCompactPanelAndSecondaryActionsUseOverflow() {
        val session = SessionSelection()
        compose.setContent { LocalicaTheme {
            val source = rememberFrostedBackdrop(Unit)
            Box(Modifier.size(380.dp, 640.dp).testTag("selection-panel-scene")) {
                Box(Modifier.matchParentSize().recordFrostedBackdrop(source).background(Color(0xFFE9E3F5)))
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    SelectionPanelVisibility(session.panelVisible, Modifier.align(Alignment.BottomCenter).testTag("panel-footprint")) {
                        SessionSelectionPanel(session, null, null, PlaylistState(loading = false))
                    }
                }
            }
        } }
        compose.runOnIdle {
            session.select(null, (1..3).map { SongRow("$it", Track("$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3")) })
        }
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("selection-panel-scene").captureToImage()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        File(root, "selection-pill").apply { mkdirs() }.let { directory ->
            File(directory, "panel-regression-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                scene.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val height = compose.onNodeWithTag("panel-footprint").fetchSemanticsNode().boundsInRoot.height
        val density = scene.width / 380f
        assertTrue("Frequent actions fit within a compact panel: ${height / density} dp", height <= 112f * density)
        compose.onNodeWithTag("selection-pill", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("More selection actions").assertIsDisplayed()
        compose.onNodeWithText("New queue", substring = false).assertDoesNotExist()
    }
}
