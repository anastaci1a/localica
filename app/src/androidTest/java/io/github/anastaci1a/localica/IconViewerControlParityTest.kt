package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
            Column(Modifier.background(Color.White).verticalScroll(rememberScrollState())) {
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
            val differing = a.indices.count { a[it] != b[it] }
            assertEquals("${icon.id} (pack=$packId): viewer and control pixels differ", 0, differing)
        }
    }

    @Test fun builtinViewerMatchesEveryControl() = compare(null)

    @Test fun exportedDefaultPackViewerMatchesEveryControl() {
        val template = ByteArrayOutputStream().also { IconPacks.exportTemplate(context, it) }.toByteArray()
        IconPacks.import(context, template.inputStream())
        compare(requireNotNull(IconPacks.activeId.value))
    }
}
