package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.AppIcon
import io.github.anastaci1a.localica.ui.appIconPainter
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The shared resolver draws pack masks tinted by the host, switches live, and falls back to built-ins. */
class IconPackResolverTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() = IconPacks.reset(context)


    @Test fun packIconIsTintedLiveAndResetRestoresBuiltIn() {
        compose.setContent { Icon(appIconPainter(AppIcon.MenuPin), null, Modifier.size(48.dp).testTag("icon"), tint = Color(0xFFFF0000)) }
        compose.waitForIdle()
        val builtin = compose.onNodeWithTag("icon").captureToImage().toPixelMap()
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.BLACK) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("pack.json")); z.write("""{"name":"One","version":"1"}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("icons/action.pin.png")); z.write(png); z.closeEntry() } }.toByteArray()
        IconPacks.import(context, ByteArrayInputStream(zip))
        compose.waitForIdle()
        val packed = compose.onNodeWithTag("icon").captureToImage().toPixelMap()
        assertEquals(Color(0xFFFF0000).toArgb(), packed[packed.width / 2, packed.height / 2].toArgb())
        assertEquals(Color(0xFFFF0000).toArgb(), packed[1, 1].toArgb())
        IconPacks.reset(context)
        compose.waitForIdle()
        val restored = compose.onNodeWithTag("icon").captureToImage().toPixelMap()
        assertEquals(builtin[1, 1].toArgb(), restored[1, 1].toArgb())
        assertNotEquals(Color(0xFFFF0000).toArgb(), restored[1, 1].toArgb())
    }
}
