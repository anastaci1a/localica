package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Measures the actual shared controls, including their retained minimum touch box. */
class GlassActionCentreTest {
    @get:Rule val compose = localicaComposeRule()

    private fun descendants(info: LayoutInfo): List<LayoutInfo> {
        val getter = info.javaClass.methods.firstOrNull { it.name.startsWith("getChildren") && it.parameterCount == 0 }
            ?: return listOf(info)
        val value = getter.invoke(info) ?: return listOf(info)
        val children = if (value is List<*>) value.filterIsInstance<LayoutInfo>() else {
            val content = value.javaClass.methods.first { it.name.startsWith("getContent") && it.parameterCount == 0 }
            val size = value.javaClass.methods.first { it.name == "getSize" && it.parameterCount == 0 }
            (content.invoke(value) as Array<*>).take(size.invoke(value) as Int).filterIsInstance<LayoutInfo>()
        }
        return listOf(info) + children.flatMap(::descendants)
    }

    @Test fun menuAndStripFacesKeepTheirTouchBoxAndCentreTheirIcons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "glass-action-centre-701").apply { mkdirs() }
        val rows = listOf("Play now" to AppIcon.TransportPlay, "Add to queue" to AppIcon.MenuListAddLast,
            "New queue" to AppIcon.TabQueues, "Edit tags" to AppIcon.ActionEdit,
            "Remove from queue" to AppIcon.ActionClose, "Select all" to AppIcon.TabQueues,
            "Select range" to AppIcon.TabQueues)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var density = 0f
        var clicks = 0
        compose.setContent { LocalicaTheme { GlassPanelTheme(true) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                val currentDensity = LocalDensity.current
                SideEffect { density = currentDensity.density }
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("centre-menu"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { (label, icon) -> ActionMenuItem(label, icon = icon) { clicks++ } }
                    GlassActionRow { GlassActionButton(AppIcon.ActionEdit, "Strip edit", onClick = { clicks++ }) }
                    Row { ActionIcon(AppIcon.ActionClose, "Close artwork") {}; ActionIcon(AppIcon.ActionSave, "Save artwork") {} }
                }
            }
        } } }
        val records = JSONArray()
        val failures = mutableListOf<String>()
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection }
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("centre-menu").captureToImage().asAndroidBitmap()
            File(output, "api${Build.VERSION.SDK_INT}-${layoutDirection.name}-menu.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            for (label in rows.map { it.first } + "Strip edit") {
                val node = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
                val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                compose.runOnIdle {
                    val button = node.layoutInfo.coordinates.boundsInRoot()
                    val face = requireNotNull(text.layoutInfo.parentInfo).coordinates.boundsInRoot()
                    val icon = descendants(node.layoutInfo).first { kotlin.math.abs(it.width - 24 * density) < 1 && kotlin.math.abs(it.height - 24 * density) < 1 }
                        .coordinates.boundsInRoot()
                    fun rect(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
                    records.put(JSONObject().put("label", label).put("direction", layoutDirection.name).put("density", density)
                        .put("button", rect(button)).put("face", rect(face)).put("icon", rect(icon)))
                    if (kotlin.math.abs(face.center.y - button.center.y) > .5f) failures += "$label face centre ${face.center.y} != ${button.center.y}"
                    if (kotlin.math.abs(icon.center.y - button.center.y) > .5f) failures += "$label icon centre ${icon.center.y} != ${button.center.y}"
                    assertEquals("Minimum touch height stays unchanged", with(androidx.compose.ui.unit.Density(density)) { CompactControlSize.roundToPx() }.toFloat(), button.height, .5f)
                    // Main's icon starts at exactly 18dp from the logical start. Keep that column fixed.
                    val startInset = if (layoutDirection == LayoutDirection.Ltr) icon.left - button.left else button.right - icon.right
                    assertEquals("Main icon x-position must stay unchanged", with(androidx.compose.ui.unit.Density(density)) { 18.dp.roundToPx() }.toFloat(), startInset, .5f)
                }
            }
            // The retained bottom part of the minimum box must still activate.
            val button = compose.onNode(hasText("Edit tags") and hasClickAction())
            button.performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, height - 1f)) }
        }
        File(output, "api${Build.VERSION.SDK_INT}-geometry.json").writeText(records.toString(2))
        assertEquals("Bottom-edge touch target remains active in both directions", 2, clicks)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
