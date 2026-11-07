package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The pack viewer and the controls draw every icon id through one path, so their pixels match exactly. */
class IconViewerControlParityTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() { IconPacks.reset(context) }

    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }

    private fun compare(packId: String?) {
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalContentColor provides Color.Black) {
            // Edge-to-edge (API 35+) draws system-bar scrims over the window; keep both captures clear of them.
            Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().verticalScroll(rememberScrollState())) {
                IconPackViewer(packId)
                // Controls (GlassActionButtonFace, player action slots) host the icon exactly like this.
                AppIcon.entries.forEach { Icon(appIconPainter(it), null, Modifier.size(24.dp).testTag("control-${it.id}")) }
            }
        } } }
        for (icon in AppIcon.entries) {
            val viewer = compose.onNodeWithContentDescription("Pack: ${icon.id}").performScrollTo().captureToImage()
            val control = compose.onNodeWithTag("control-${icon.id}").performScrollTo().captureToImage()
            assertEquals("${icon.id} size", viewer.width to viewer.height, control.width to control.height)
            val a = pixels(viewer); val b = pixels(control)
            assertTrue("${icon.id} has visible ink", a.any { it != android.graphics.Color.WHITE })
            val differing = a.indices.filter { a[it] != b[it] }
            val corners = listOf(0, viewer.width - 1, a.size - viewer.width, a.size - 1)
            fun hex(values: IntArray) = corners.joinToString { Integer.toHexString(values[it]) }
            assertEquals("${icon.id} (pack=$packId): viewer and control pixels differ " +
                "(ink ${differing.count { a[it] != android.graphics.Color.WHITE || b[it] != android.graphics.Color.WHITE }}; " +
                "viewer corners ${hex(a)}; control corners ${hex(b)}; bounds " +
                "${compose.onNodeWithContentDescription("Pack: ${icon.id}").fetchSemanticsNode().boundsInRoot} vs " +
                "${compose.onNodeWithTag("control-${icon.id}").fetchSemanticsNode().boundsInRoot})", 0, differing.size)
        }
    }

    @Test fun builtinViewerMatchesEveryControl() = compare(null)

    @Test fun exportedDefaultPackViewerMatchesEveryControl() {
        val template = ByteArrayOutputStream().also { IconPacks.exportTemplate(context, it) }.toByteArray()
        IconPacks.import(context, template.inputStream())
        compare(requireNotNull(IconPacks.activeId.value))
    }
}
