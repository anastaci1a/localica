package io.github.anastaci1a.localica

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #741: historical flat page copy and actual-icon card offsets, independently of vertical row alignment. */
class SettingsTextInsetsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val output get() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.getExternalFilesDir(null)),
        "settings-insets-741/api${Build.VERSION.SDK_INT}").apply { mkdirs() }

    private fun textGeometry(label: String, node: SemanticsNodeInteraction = compose.onNodeWithText(label, useUnmergedTree = true)): JSONObject {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val semantics = node.fetchSemanticsNode()
        val layout = layouts.single()
        return JSONObject().put("text", label).put("x", semantics.positionInRoot.x)
            .put("y", semantics.positionInRoot.y).put("width", layout.size.width).put("right", semantics.positionInRoot.x + layout.size.width)
            .put("lineLeft", JSONArray((0 until layout.lineCount).map { layout.getLineLeft(it) }))
            .put("lineRight", JSONArray((0 until layout.lineCount).map { layout.getLineRight(it) }))
    }

    @Test fun flatTextRolesKeepHistoricalPageEdgesExactly() {
        var edge = 0f
        var scale by mutableFloatStateOf(1f)
        compose.setContent { LocalicaTheme {
            val device = LocalDensity.current
            edge = with(device) { 24.dp.roundToPx().toFloat() }
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(device.density, scale),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                SettingsPage("Text roles", {}) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("role-body")) {
                        SettingsHeading("Header role")
                        Text("Description role", style = MaterialTheme.typography.bodySmall)
                        SliderPreference("Slider title role", .5f, {}, low = "Low role", high = "High role", description = "Helper role")
                    }
                }
            }
        } }
        val receipt = JSONArray()
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale = fontScale }
            compose.waitForIdle()
            val body = compose.onNodeWithTag("role-body", useUnmergedTree = true).fetchSemanticsNode()
            val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
            val expectedLeft = root.positionInRoot.x + edge
            val expectedRight = root.positionInRoot.x + root.size.width - edge
            val role = JSONObject().put("fontScale", fontScale).put("expectedLeft", expectedLeft)
                .put("expectedRight", expectedRight)
            val positions = JSONArray()
            for (label in listOf("Header role", "Description role", "Slider title role", "Low role", "Helper role", "High role")) {
                positions.put(textGeometry(label))
            }
            role.put("positions", positions); receipt.put(role)
            File(output, "roles.json").writeText(receipt.toString(2))
            // Save the full native window before assertions so a red base retains its geometry witness.
            val bitmap = compose.onRoot(useUnmergedTree = true).captureNativeFrame().asAndroidBitmap()
            File(output, "roles-$fontScale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("Page body is the historical 24dp edge", expectedLeft, body.positionInRoot.x, 0f)
            for (index in 0 until positions.length()) {
                val item = positions.getJSONObject(index)
                if (item.getString("text") == "High role")
                    assertEquals("High endpoint stays at page end", expectedRight, item.getDouble("right").toFloat(), 0f)
                else assertEquals("${item.getString("text")} keeps historical x", expectedLeft, item.getDouble("x").toFloat(), 0f)
            }
        }
    }

    @Test fun captureNativeSettingsPagesAndTextPositions() {
        var page by mutableStateOf("Glass advanced")
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                key(page) {
                    FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = if (page.startsWith("Glass")) "Glass" else page,
                        interfaceContent = { section ->
                            if (section.startsWith("Glass")) GlassSettingsControls(GlassAppearance()) {}
                            else InterfaceSettingsControls(InterfaceSettings(), section) {}
                        })
                }
            }
        } }
        val pages = linkedMapOf(
            "Glass advanced" to listOf("Adaptive contrast", "Higher weighting focuses", "Shape and motion", "Sliders and switches", "Control expansion ·"),
            "Glass appearance" to listOf("Blur ·", "White tint ·", "Shadow strength ·"),
            "Library and tags" to listOf("Library filters", "Tag jobs and recovery"),
            "Interface" to listOf("Appearance", "Navigation", "Motion"),
            "Scanner" to listOf("Include subfolders", "Both are off by default."),
            "Motion" to listOf("Reduce motion", "How Return to top"),
            "Appearance" to listOf("Adjust secondary text", "Interface gray ·"))
        val records = JSONArray()
        for ((name, anchors) in pages) {
            compose.runOnIdle { page = name }
            compose.waitForIdle()
            for ((index, anchor) in anchors.withIndex()) {
                val node = compose.onAllNodes(hasText(anchor, substring = true), useUnmergedTree = true).onFirst()
                node.performScrollTo()
                compose.waitForIdle()
                // Scrollable Columns lay out every child. Export their native text origins even when clipped,
                // and keep ownership for repeated endpoint captions on the older combined Glass page.
                val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes().filter { it.size.width > 0 && it.size.height > 0 }
                val titles = texts.filter { it.config.contains(SettingsRowPartKey) && it.config[SettingsRowPartKey] == SettingsRowPart.Text }
                val geometry = JSONArray()
                for (textNode in texts) {
                    val text = textNode.config[SemanticsProperties.Text].joinToString("") { it.text }
                    val owner = titles.filter { it.positionInRoot.y < textNode.positionInRoot.y }
                        .maxByOrNull { it.positionInRoot.y }?.config?.get(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
                    val target = compose.onNode(SemanticsMatcher("native text id "+textNode.id) { it.id == textNode.id }, useUnmergedTree = true)
                    geometry.put(textGeometry(text, target).put("owner", owner))
                }
                records.put(JSONObject().put("page", name).put("anchor", anchor).put("positions", geometry))
                val slug = name.lowercase().replace(' ', '-') + "-$index"
                val bitmap = compose.onRoot(useUnmergedTree = true).captureNativeFrame().asAndroidBitmap()
                File(output, "$slug.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "pages.json").writeText(records.toString(2))
            }
        }
    }
}
