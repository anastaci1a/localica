package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.anastaci1a.localica.browse.LibraryFilters
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #655 vertical centering and #741 historical horizontal positions at default and large font scales. */
class SettingsRowAlignmentTest {
    @get:Rule val compose = createComposeRule()

    private val pages = listOf(null, "Interface", "Appearance", "Icon packs", "Glass", "Navigation", "Player", "Motion",
        "Playback and queues", "Song tap behavior", "Library and tags", "Library filters", "Folders to scan", "Scanner",
        "Tag jobs and recovery", "About and licenses")
    /** Pages whose content is built from shared rows; each must expose at least one. */
    private val rowPages = setOf(null, "Interface", "Glass", "Player", "Motion", "Playback and queues", "Song tap behavior",
        "Library and tags", "Folders to scan", "Scanner", "Icon packs")
    private val navigationSummary = "Main tabs, Back history, and exit confirmation"

    private var scale by mutableFloatStateOf(1f)
    private var category by mutableStateOf<String?>(null)
    private lateinit var density: Density

    private fun show() {
        val state = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("content://synthetic-origin", "Synthetic origin"))),
            loading = false)
        compose.setContent { LocalicaTheme {
            val device = LocalDensity.current
            density = device
            CompositionLocalProvider(LocalDensity provides Density(device.density, scale),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).fillMaxHeight()) { key(category, scale) {
                    FolderSettings(state, {}, {}, {}, initialCategory = category, interfaceContent = { section ->
                        if (section == "Player") SettingsLink("Customize player actions", "Choose controls and their order") {}
                        var settings by remember { mutableStateOf(InterfaceSettings()) }
                        InterfaceSettingsControls(settings, section) { settings = it }
                    }, playbackContent = { section ->
                        var settings by remember { mutableStateOf(QueueSettings()) }
                        QueueSettingsControls(settings, section) { settings = it(settings) }
                    }, aboutContent = { Text("Localica") }, libraryFiltersContent = {
                        var filters by remember { mutableStateOf(LibraryFilters()) }
                        LibraryFilterSettings(filters) { filters = it }
                    })
                } }
            }
        } }
    }

    private fun parts(): List<Pair<SettingsRowPart, Rect>> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SettingsRowPartKey), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SettingsRowPartKey] to Rect(it.positionInRoot, it.size.toSize()) }

    private fun assertPage(label: String, requireRows: Boolean) {
        compose.waitForIdle()
        val parts = parts()
        val tolerance = with(density) { 1.dp.toPx() }
        val rows = parts.filter { it.first == SettingsRowPart.Row }.map { it.second }
        parts.filter { it.first == SettingsRowPart.Icon || it.first == SettingsRowPart.Trailing }.forEach { (part, bounds) ->
            val row = rows.filter { it.contains(bounds.center) }.minByOrNull { it.height }
            assertNotNull("$label: $part lies outside every settings row", row)
            assertEquals("$label: $part centre-y must equal the row centre-y (row $row, part $bounds)",
                row!!.center.y, bounds.center.y, tolerance)
        }
        val starts = parts.filter { it.first == SettingsRowPart.Text || it.first == SettingsRowPart.Heading }.map { it.second.left }
        if (requireRows) assertTrue("$label exposes no shared settings rows", starts.isNotEmpty())
        val pageEdge = rows.minOfOrNull { it.left } ?: starts.minOrNull() ?: return
        parts.filter { it.first == SettingsRowPart.Text || it.first == SettingsRowPart.Heading }.forEach { (part, bounds) ->
            val row = rows.filter { it.contains(bounds.center) }.minByOrNull { it.height }
            val icon = row?.let { r -> parts.any { it.first == SettingsRowPart.Icon && r.contains(it.second.center) } } == true
            val card = row?.let { r -> parts.any {
                it.first == SettingsRowPart.Trailing && r.contains(it.second.center) &&
                    it.second.width <= with(density) { 20.dp.toPx() }
            } } == true
            val expected = if (part == SettingsRowPart.Heading || !card) pageEdge else row!!.left +
                with(density) { (if (icon) 56.dp else 16.dp).toPx() }
            assertEquals("$label: historical x for $part (icon=$icon, card=$card)", expected, bounds.left, 0f)
        }
    }

    private fun summaryLines(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(navigationSummary, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().lineCount
    }

    @Test fun everySettingsPageKeepsHistoricalTextOffsetsAtDefaultAndLargeFontScale() {
        show()
        for (fontScale in listOf(1f, 2f)) for (page in pages) {
            compose.runOnIdle { scale = fontScale; category = page }
            assertPage("${page ?: "Settings"} @ ${fontScale}x", page in rowPages)
        }
        compose.runOnIdle { scale = 1f; category = "Folders to scan" }
        compose.onNodeWithText("Synthetic origin").performScrollTo().performClick()
        compose.onNodeWithText("Excluded subfolders").assertExists()
        assertPage("Folder options", requireRows = true)
    }

    @Test fun wrappedSubtitleKeepsIconAndChevronCentredOnTheRow() {
        show()
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale = fontScale; category = "Interface" }
            compose.waitForIdle()
            assertTrue("The Navigation subtitle must wrap for this check @ ${fontScale}x", summaryLines() >= 2)
            assertPage("Interface (wrapped Navigation subtitle) @ ${fontScale}x", requireRows = true)
        }
    }
}
