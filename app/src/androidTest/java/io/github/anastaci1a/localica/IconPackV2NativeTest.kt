package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class IconPackV2NativeTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val installed = mutableSetOf<String>()
    @After fun cleanup() {
        IconPacks.reset(context)
        installed.forEach { IconPacks.delete(context, it) }
    }
    private fun install(bytes: ByteArray): String {
        IconPacks.import(context, bytes.inputStream())
        return requireNotNull(IconPacks.activeId.value).also { installed += it }
    }

    /** This uses the original public API so it also proves the missing v2 behavior on the base revision. */
    @Test fun templateIsV2AndEveryRegistryIdRendersBothDefaultAndImportedImages() {
        val archive = ByteArrayOutputStream().also { IconPacks.exportTemplate(context, it) }.toByteArray()
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        val manifest = JSONObject(files.getValue("pack.json").toString(Charsets.UTF_8))
        assertEquals(2, manifest.optInt("schemaVersion", 1))
        val ids = manifest.getJSONObject("icons")
        assertEquals(AppIcon.ids, ids.keys().asSequence().toSet())
        val images = files.filterKeys { it.startsWith("icons/") }
        assertEquals(images.size, images.values.map { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).toList() }.toSet().size)
        ids.keys().forEach { assertTrue(files.containsKey("icons/${ids.getString(it)}")) }
        IconPacks.reset(context)
        val painters = linkedMapOf<String, Pair<Painter, Painter>>()
        val importedId = install(archive)
        IconPacks.load(context)
        assertEquals(AppIcon.ids, IconPacks.images.value.vectors.keys)
        compose.setContent {
            AppIcon.entries.forEach { icon ->
                val builtin = packIconPainter(icon, null)
                val imported = appIconPainter(icon)
                SideEffect { painters[icon.id] = builtin to imported }
            }
        }
        compose.runOnIdle {
            for ((id, pair) in painters) for ((label, painter) in listOf("default" to pair.first, "v2" to pair.second)) {
                val image = ImageBitmap(48, 48)
                CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(image), Size(48f, 48f)) {
                    with(painter) { draw(size, colorFilter = ColorFilter.tint(Color.Black)) }
                }
                val bitmap = image.asAndroidBitmap()
                val pixels = IntArray(48 * 48)
                bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48)
                assertTrue("$id must render in $label", pixels.any { it ushr 24 != 0 })
                bitmap.recycle()
            }
        }
        assertEquals(importedId, IconPacks.activeId.value)
    }

    @Test fun viewerShowsOneImageWithAllMappedIdsAndCapturesNativePixels() {
        val glyph = """<svg viewBox="0 0 24 24"><path d="M4 4H20V20H4Z"/></svg>"""
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            val manifest = """{"schemaVersion":2,"name":"Shared image fixture","version":"1","icons":{"nav.home":"shared.svg","nav.library":"shared.svg","action.refres":"shared.svg"}}"""
            for ((name, data) in listOf("pack.json" to manifest, "icons/shared.svg" to glyph)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        val id = install(bytes)
        IconPacks.load(context)
        compose.setContent { LocalicaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { IconPackViewer(id) } } }
        compose.onAllNodesWithTag("pack-image-shared.svg").assertCountEquals(1)
        compose.onNodeWithTag("pack-icon-nav.home").assertExists()
        compose.onNodeWithTag("pack-icon-nav.library").assertExists()
        compose.onNodeWithText("Closest current id: action.refresh").assertExists()
        val bitmap = compose.onRoot().captureNativeFrame().asAndroidBitmap()
        val file = File(nativeEvidenceDirectory("icon-packs-v2-751", context.getExternalFilesDir(null)), "api${Build.VERSION.SDK_INT}-deduplicated-viewer.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
