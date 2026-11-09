package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reference bodies preserve the exact affected layout expressions at 4ec747a8. */
class SettingsStockGeometryTest {
    @get:Rule val compose = localicaComposeRule()
    private var adopted by mutableStateOf(false)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var font by mutableFloatStateOf(1f)
    private var sample by mutableIntStateOf(0)
    private var enabled by mutableStateOf(true)
    private val clicks = mutableListOf<String>()
    private val title = "A long setting label that wraps at large font"
    private val summary = "Metadata stays readable and keeps its original natural height."
    private val primary = ColorPainter(Color.Red)
    private val comparison = ColorPainter(Color.Blue)

    @Composable private fun Buttons() {
        ActionTextButton({ clicks += "up" }, enabled = enabled) { Text("Move up") }
        ActionTextButton({ clicks += "down" }, enabled = enabled) { Text("Move down") }
        ActionTextButton({ clicks += "remove" }, enabled = enabled) { Text("Remove") }
    }

    @Composable private fun LegacyOrder(wrap: Boolean) {
        Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (wrap) FlowRow { Buttons() } else Row { Buttons() }
            }
        }
    }

    @Composable private fun LegacyReview(candidates: Boolean) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary)
            if (candidates) listOf("Use Album A · song-a.mp3", "Use Unknown album · song-b.mp3").forEach { label ->
                ActionTextButton({ clicks += label }) { Text(label) }
            }
        }
    }

    @Composable private fun LegacyIcons() {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("comparison"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(primary, "Pack: menu.pin", Modifier.size(24.dp))
            Icon(comparison, "Built-in: menu.pin", Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun show() = compose.setContent {
        LocalicaTheme {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, font),
                LocalLayoutDirection provides direction, LocalReducedMotion provides true) {
                Box(Modifier.width(320.dp).background(Color.White).testTag("witness")) {
                    if (!adopted) when (sample) {
                        0 -> LegacyOrder(false)
                        1 -> LegacyOrder(true)
                        2 -> LegacyReview(true)
                        3 -> LegacyReview(false)
                        else -> LegacyIcons()
                    } else when (sample) {
                        0, 1 -> SettingsOrderCard(title,
                            if (sample == 0) SettingsActionLayout.Inline else SettingsActionLayout.Wrapping,
                            moveUp = { ActionTextButton({ clicks += "up" }, enabled = enabled) { Text("Move up") } },
                            moveDown = { ActionTextButton({ clicks += "down" }, enabled = enabled) { Text("Move down") } },
                            remove = { ActionTextButton({ clicks += "remove" }, enabled = enabled) { Text("Remove") } },
                        )
                        2, 3 -> SettingsReviewItem(title, summary) {
                            if (sample == 2) listOf("Use Album A · song-a.mp3", "Use Unknown album · song-b.mp3").forEach { label ->
                                ActionTextButton({ clicks += label }) { Text(label) }
                            }
                        }
                        else -> SettingsIconComparisonRow(SettingsComparisonIcon(primary, "Pack: menu.pin"),
                            SettingsComparisonIcon(comparison, "Built-in: menu.pin"), title, summary, Modifier.testTag("comparison"))
                    }
                }
            }
        }
    }

    private data class Node(val labels: List<String>, val description: List<String>, val bounds: Rect,
        val disabled: Boolean, val role: String?, val click: Boolean, val touchBounds: Rect)

    private fun nodes() = compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
        Node(node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text },
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() },
            Rect(node.positionInRoot, node.size.toSize()), node.config.contains(SemanticsProperties.Disabled),
            node.config.getOrElseNullable(SemanticsProperties.Role) { null }?.toString(),
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick), node.touchBoundsInRoot)
    }

    private fun output(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null))
        return File(root, "settings-stock-geometry").apply { mkdirs() }
    }

    private fun pixels(name: String): Bitmap {
        val bitmap = compose.onNodeWithTag("witness").captureToImage().asAndroidBitmap()
        val file = File(output(), "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }

    private fun compare(kind: Int) {
        show()
        for (rtl in listOf(false, true)) for (scale in listOf(1f, 2f)) for (available in if (kind <= 1) listOf(true, false) else listOf(true)) {
            val cell = "$kind-${if (rtl) "rtl" else "ltr"}-$scale-$available"
            compose.runOnIdle { sample = kind; direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                font = scale; enabled = available; adopted = false }
            compose.waitForIdle()
            val beforeNodes = nodes()
            val before = pixels("$cell-base-4ec747a8")
            compose.runOnIdle { adopted = true }
            compose.waitForIdle()
            assertEquals("Semantic bounds and actions $cell", beforeNodes, nodes())
            val afterNodes = nodes()
            File(output(), "$cell-bounds.txt").writeText("source=" + BuildConfig.SOURCE_SHA + "\nbase=4ec747a8dc7d1802d5964f93c410362d81f41908\nbefore=" + beforeNodes + "\nafter=" + afterNodes + "\n")
            val after = pixels("$cell-adopted")
            assertEquals("Image width $cell", before.width, after.width)
            assertEquals("Image height $cell", before.height, after.height)
            assertTrue("Pixel-identical stock geometry $cell", before.sameAs(after))
            if (kind <= 1) {
                clicks.clear()
                listOf("Move up", "Move down", "Remove").forEach { label ->
                    val button = compose.onNodeWithText(label)
                    if (available) button.assertIsEnabled().performClick() else button.assertIsNotEnabled()
                }
                assertEquals("Action order $cell", if (available) listOf("up", "down", "remove") else emptyList<String>(), clicks)
            }
        }
    }

    @Test fun tabCardMatchesPinnedInlineGeometry() = compare(0)
    @Test fun filterCardMatchesPinnedWrappingGeometry() = compare(1)
    @Test fun reviewCandidatesMatchPinnedMetadataGeometry() = compare(2)
    @Test fun unmatchedReviewMatchesPinnedMetadataGeometry() = compare(3)
    @Test fun iconComparisonMatchesPinnedMetadataGeometry() = compare(4)
}
