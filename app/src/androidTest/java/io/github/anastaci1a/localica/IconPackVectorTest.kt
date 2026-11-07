package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.*
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackVectorTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pack(entries: List<Pair<String, ByteArray>>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            (listOf("pack.json" to """{"name":"Vector fixture","version":"1"}""".toByteArray()) + entries).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
    private val svg = """<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M4 12 H20"/></svg>""".toByteArray()
    private fun png() = ByteArrayOutputStream().also { out ->
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
    }.toByteArray()
    @After fun reset() { IconPacks.reset(context) }

    @Test fun vectorsWinInBothZipOrdersPersistAndShowInTheLibraryViewer() {
        val entries = listOf("icons/menu.pin.svg" to svg, "icons/menu.pin.png" to png())
        for (ordered in listOf(entries, entries.reversed())) {
            val report = IconPacks.import(context, pack(ordered).inputStream())
            assertEquals(listOf("menu.pin"), report.applied)
            assertTrue(report.rejected.isEmpty())
            assertNotNull(IconPacks.images.value.vectors["menu.pin"])
            assertNull(IconPacks.masks.value["menu.pin"])
            IconPacks.load(context)
            assertNotNull(IconPacks.images.value.vectors["menu.pin"])
            val id = requireNotNull(IconPacks.activeId.value)
            assertTrue(File(context.filesDir, "icon-packs/$id/icons/menu.pin.svg").isFile)
            assertFalse(File(context.filesDir, "icon-packs/$id/icons/menu.pin.png").exists())
        }
        val id = requireNotNull(IconPacks.activeId.value)
        compose.setContent { LocalicaTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { IconPackViewer(id) }
        } }
        compose.onNodeWithText("Reassigned · Vector").assertExists()
        compose.onNodeWithTag("pack-icon-menu.pin").assertExists()
    }

    @Test fun badVectorsRejectIndividuallyAndAllowRasterFallback() {
        val bad = "<svg><script/></svg>".toByteArray()
        val report = IconPacks.import(context, pack(listOf("icons/menu.pin.svg" to bad,
            "icons/menu.pin.png" to png(), "icons/menu.trash.svg" to svg)).inputStream())
        assertEquals(setOf("menu.pin", "menu.trash"), report.applied.toSet())
        assertEquals("icons/menu.pin.svg", report.rejected.single().first)
        assertTrue(report.rejected.single().second.startsWith("Invalid SVG:"))
        assertNotNull(IconPacks.masks.value["menu.pin"])
        assertNotNull(IconPacks.images.value.vectors["menu.trash"])
    }

    private fun render(painter: Painter, pixels: Int, tint: Color): Bitmap {
        val image = ImageBitmap(pixels, pixels)
        CanvasDrawScope().draw(Density(pixels / 24f), LayoutDirection.Ltr, Canvas(image), Size(pixels.toFloat(), pixels.toFloat())) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(tint)) }
        }
        return image.asAndroidBitmap()
    }

    /** Optional local arguments exercise private packs without adding their files to source or CI. */
    @Test fun nativeVectorTemplateMatchesEveryBuiltinAndCanRenderAnExternalPackSheet() {
        val arguments = InstrumentationRegistry.getArguments()
        val source = arguments.getString("icon_pack_path")?.let { File(it).readBytes() }
            ?: ByteArrayOutputStream().also { IconPacks.exportTemplate(context, it) }.toByteArray()
        val report = IconPacks.import(context, source.inputStream())
        assertTrue(report.unknown.isEmpty()); assertTrue(report.rejected.isEmpty())
        val current = IconPacks.images.value
        val external = arguments.getString("icon_pack_path") != null
        if (!external) assertEquals(AppIcon.ids, current.vectors.keys)
        val previousPath = arguments.getString("previous_icon_pack_path")
        val previous = previousPath?.let {
            IconPacks.import(context, File(it).inputStream()); IconPacks.images.value
        } ?: current
        val painters = linkedMapOf<AppIcon, List<Painter>>()
        compose.setContent { LocalicaTheme {
            AppIcon.entries.forEach { icon ->
                val builtin = packIconPainter(icon, null)
                val old = packIconPainter(icon, previous.masks[icon.id], previous.vectors[icon.id])
                val new = packIconPainter(icon, current.masks[icon.id], current.vectors[icon.id])
                SideEffect { painters[icon] = listOf(builtin, old, new) }
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            val sheet = Bitmap.createBitmap(700, 60 + AppIcon.entries.size * 68, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sheet); canvas.drawColor(0xfff4f4f4.toInt())
            val text = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 13f; isAntiAlias = true }
            canvas.drawText("Icon id                 Built-in              Previous                 Current", 12f, 24f, text)
            AppIcon.entries.forEachIndexed { index, icon ->
                val y = 48 + index * 68
                canvas.drawText(icon.id, 12f, y + 24f, text)
                val values = painters.getValue(icon)
                for ((column, painter) in values.withIndex()) for (px in listOf(24, 48)) {
                    val bitmap = render(painter, px, Color(0xff222222))
                    assertTrue("${icon.id} has visible ink", (0 until px).any { row -> (0 until px).any { x -> android.graphics.Color.alpha(bitmap.getPixel(x, row)) > 0 } })
                    canvas.drawBitmap(bitmap, (220 + column * 150 + if (px == 48) 34 else 0).toFloat(), y.toFloat(), null)
                    if (!external && column == 2) {
                        val expected = render(values[0], px, Color(0xff222222))
                        val a = IntArray(px * px); val b = IntArray(a.size)
                        bitmap.getPixels(a, 0, px, 0, 0, px, px); expected.getPixels(b, 0, px, 0, 0, px, px)
                        val delta = a.indices.sumOf { kotlin.math.abs((a[it] ushr 24) - (b[it] ushr 24)) }
                        assertTrue("${icon.id} $px px alpha error: $delta", delta <= px * px)
                        val tinted = render(painter, px, Color(0x804488cc))
                        val expectedTint = render(values[0], px, Color(0x804488cc))
                        val ta = IntArray(a.size); val tb = IntArray(a.size)
                        tinted.getPixels(ta, 0, px, 0, 0, px, px); expectedTint.getPixels(tb, 0, px, 0, 0, px, px)
                        val tintDelta = ta.indices.sumOf { i -> listOf(0, 8, 16, 24).sumOf { shift ->
                            kotlin.math.abs(((ta[i] ushr shift) and 255) - ((tb[i] ushr shift) and 255))
                        } }
                        assertTrue("${icon.id} tint/disabled alpha parity", tintDelta <= px * px * 4)
                    }
                }
            }
            val destination = File(context.getExternalFilesDir(null), "icon-pack-compare.png")
            destination.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(context.getExternalFilesDir(null), "icon-pack-import.txt").writeText(
                "${report.applied.size} applied, ${report.unknown.size} unknown, ${report.rejected.size} rejected, version ${report.info.version}\n")
        }
    }
}
