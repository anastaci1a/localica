package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.*
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Native pixel proof; fixtures and optional local pack inputs never ship in the application. */
class IconOpticalCentringTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = nativeEvidenceDirectory("icon-optical-724", context.getExternalFilesDir(null))
    @After fun reset() { IconPacks.reset(context) }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun save(name: String, bitmap: Bitmap) {
        File(output, "api${Build.VERSION.SDK_INT}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun render(painter: Painter, px: Int): Bitmap {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(Density(px / 24f), LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(Color.Black)) }
        }
        return image.asAndroidBitmap()
    }
    private fun metrics(bitmap: Bitmap) = requireNotNull(alphaInkMetrics(pixels(bitmap).map { it ushr 24 }.toIntArray(), bitmap.width, bitmap.height))

    @Test fun rasterImportMovesWholePixelsWithoutChangingInkAndReloadDoesNotAccumulate() {
        val size = 96
        val originals = listOf("action.save", "action.close", "action.edit", "action.plus").mapIndexed { index, id ->
            val data = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                val inside = when (index) {
                    0 -> y in 16..79 && abs(x - 48) <= (y - 16) / 2
                    1 -> y in 24..71 && x in 24..71
                    2 -> y in 0..63 && abs(x - 48) <= y / 2
                    else -> false
                }
                if (inside) data[y * size + x] = 0xc04488cc.toInt()
            }
            id to Bitmap.createBitmap(data, size, size, Bitmap.Config.ARGB_8888)
        }
        val pack = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json")); zip.write("""{"name":"Optical raster fixture","version":"1"}""".toByteArray()); zip.closeEntry()
            for ((id, bitmap) in originals) {
                zip.putNextEntry(ZipEntry("icons/$id.png")); bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
            }
        } }.toByteArray()
        IconPacks.import(context, pack.inputStream())
        val first = IconPacks.images.value.masks
        for ((index, entry) in originals.withIndex()) {
            val (id, before) = entry
            val after = first.getValue(id)
            val move = if (index == 0) -5 else 0
            val original = pixels(before)
            val expected = IntArray(size * size)
            for (y in 0 until size) if (y + move in 0 until size)
                original.copyInto(expected, (y + move) * size, y * size, (y + 1) * size)
            assertArrayEquals("$id exact whole-pixel translation", expected, pixels(after))
            save("raster-$id-before", before); save("raster-$id-after", after)
        }
        val cached = first.getValue("action.save")
        assertSame("resolved masks reuse the processed image", cached, IconPacks.images.value.masks.getValue("action.save"))
        IconPacks.load(context)
        for ((id, _) in originals) assertArrayEquals("$id reload starts from original bytes", pixels(first.getValue(id)), pixels(IconPacks.images.value.masks.getValue(id)))
    }

    @Test fun nativeBuiltinStripsFollowTheRuleAndSymmetricPixelsStayIdentical() {
        val path = InstrumentationRegistry.getArguments().getString("icon_pack_path")
        val originals = if (path == null) emptyMap() else ZipFile(path).use { zip ->
            val raw = zip.entries().toList().filter { it.name.startsWith("icons/") && it.name.endsWith(".svg") }
                .associate { it.name.removePrefix("icons/").removeSuffix(".svg") to
                    SvgIconParser.parse(zip.getInputStream(it).readBytes()).toImageVector(shiftY = 0f) }
            AppIcon.entries.mapNotNull { icon -> AppIcon.packIds(icon).firstNotNullOfOrNull { raw[it] }?.let { icon.id to it } }.toMap()
        }
        if (path != null) {
            val report = IconPacks.import(context, File(path).inputStream())
            assertTrue(report.rejected.isEmpty())
            assertEquals("Every imported SVG has a raw/processed comparison", report.applied.toSet(), originals.keys)
        }
        val icons = AppIcon.entries.filter { path == null || it.id in originals }
        val prefix = if (path == null) "builtin" else "local-pack"
        val pairs = linkedMapOf<AppIcon, Pair<Painter, Painter>>()
        compose.setContent { LocalicaTheme {
            for (icon in icons) {
                val before = if (path == null) painterResource(icon.builtin) else packIconPainter(icon, null, originals.getValue(icon.id))
                val after = if (path == null) packIconPainter(icon, null) else appIconPainter(icon)
                SideEffect { pairs[icon] = before to after }
            }
        } }
        compose.waitForIdle()
        val records = JSONArray()
        val failures = mutableListOf<String>()
        compose.runOnIdle {
            val px = 384
            val sheet = Bitmap.createBitmap(px * 2, icons.size * (px + 28), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sheet)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { textSize = 20f; color = android.graphics.Color.BLACK }
            for ((index, icon) in icons.withIndex()) {
                val (a, b) = pairs.getValue(icon)
                val before = render(a, px); val after = render(b, px)
                val m = metrics(before); val n = metrics(after)
                val observed = (n.centroidY - m.centroidY) * 24f / px
                val expected = opticalShift(m, px.toFloat()) * 24f / px
                if (abs(expected - observed) > .08f) failures += "${icon.id}: expected $expected, observed $observed"
                val unchanged = if (path == null) BuiltinIconOffsets.vertical.getValue(icon.id) == 0f else originals.getValue(icon.id).opticalShiftY() == 0f
                if (unchanged) assertArrayEquals("${icon.id} zero-shift pixels stay exact", pixels(before), pixels(after))
                val y = index * (px + 28)
                canvas.drawText(icon.id + " before / after", 8f, y + 22f, paint)
                canvas.drawBitmap(before, 0f, (y + 28).toFloat(), null)
                canvas.drawBitmap(after, px.toFloat(), (y + 28).toFloat(), null)
                records.put(JSONObject().put("id", icon.id).put("beforeInkOffsetDp", (m.centroidY - px / 2f) * 24f / px)
                    .put("expectedMoveDp", expected).put("observedMoveDp", observed).put("afterInkOffsetDp", (n.centroidY - px / 2f) * 24f / px))
                before.recycle(); after.recycle()
            }
            save("$prefix-before-after", sheet)
            File(output, "api${Build.VERSION.SDK_INT}-$prefix-native.json").writeText(records.toString(2))
            sheet.recycle()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun nativeSmallOffsetWitnessDetectsAMoveJustAboveTheDeadband() {
        val source = listOf(.94f, .98f).map { alpha ->
            SvgIconParser.parse("""<svg viewBox="0 0 24 24"><path d="M6 4H18V12H6Z" fill-opacity="$alpha"/><path d="M6 12H18V20H6Z"/></svg>""".toByteArray())
        }
        val vectors = source.map { it.toImageVector(shiftY = 0f) to it.toImageVector() }
        val pairs = mutableListOf<Pair<Painter, Painter>>()
        compose.setContent {
            val measured = vectors.map { (before, after) ->
                packIconPainter(AppIcon.ActionSave, null, before) to packIconPainter(AppIcon.ActionSave, null, after)
            }
            SideEffect { pairs.clear(); pairs.addAll(measured) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            for ((index, pair) in pairs.withIndex()) {
                val before = render(pair.first, 1536); val after = render(pair.second, 1536)
                save("small-offset-$index-before", before); save("small-offset-$index-after", after)
                if (index == 0) {
                    val expected = vectors[index].first.opticalShiftY()
                    assertTrue("Fixture correction is just above the 0.05 dp deadband: $expected", expected in -.07f..-.05f)
                    val observed = (metrics(after).centroidY - metrics(before).centroidY) / 64f
                    assertTrue("Native raster must detect the small upward move: $observed", observed < -.04f)
                    assertEquals("High-resolution fixture sampling error", expected, observed, .01f)
                    assertFalse("A zero-move implementation must fail this witness", before.sameAs(after))
                } else {
                    assertSame("Below-deadband geometry stays", vectors[index].first, vectors[index].first.opticallyCentred())
                    assertArrayEquals("Below-deadband native pixels stay exact", pixels(before), pixels(after))
                }
                before.recycle(); after.recycle()
            }
        }
    }

    @Test fun firstPublishedFrameUsesTheCentredPackAndResetWithoutALateJump() {
        fun prepared(name: String, path: String): PreparedIconPack {
            val source = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("pack.json")); zip.write("""{"name":"$name","version":"1"}""".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("icons/action.save.svg"))
                zip.write("""<svg viewBox="0 0 24 24"><path d="$path"/></svg>""".toByteArray()); zip.closeEntry()
            } }.toByteArray()
            return IconPacks.prepare(source.inputStream())
        }
        val first = prepared("First frame lower ink", "M12 4L20 20H4Z")
        val second = prepared("First frame upper ink", "M4 4H20L12 20Z")
        var expected by mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null)
        IconPacks.reset(context)
        compose.setContent { LocalicaTheme {
            Row(Modifier.background(Color.White)) {
                for (actual in listOf(true, false)) Box(Modifier.size(96.dp).testTag(if (actual) "switch-actual" else "switch-expected"),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    val painter = if (actual) appIconPainter(AppIcon.ActionSave) else packIconPainter(AppIcon.ActionSave, null, expected)
                    androidx.compose.material3.Icon(painter, null, Modifier.size(48.dp), tint = Color.Black)
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for ((index, pack) in listOf(first, second, null).withIndex()) {
            compose.runOnIdle { expected = pack?.vectors?.getValue("action.save") }
            if (pack == null) IconPacks.reset(context) else IconPacks.install(context, pack)
            compose.mainClock.advanceTimeByFrame()
            val frameTime = compose.mainClock.currentTime
            val actual = compose.onNodeWithTag("switch-actual").captureNativeFrame().asAndroidBitmap()
            val reference = compose.onNodeWithTag("switch-expected").captureNativeFrame().asAndroidBitmap()
            assertEquals("Native capture cannot hide a later Compose frame", frameTime, compose.mainClock.currentTime)
            save("switch-$index-first", actual); save("switch-$index-reference", reference)
            assertArrayEquals("First published frame matches the prepared centred vector", pixels(reference), pixels(actual))
            compose.mainClock.advanceTimeByFrame()
            val next = compose.onNodeWithTag("switch-actual").captureNativeFrame().asAndroidBitmap()
            assertArrayEquals("No delayed centring jump on the next frame", pixels(actual), pixels(next))
        }
        compose.mainClock.autoAdvance = true
    }

    /** The #701 shared menu and round controls; an optional local pack stays outside Git and CI. */
    @Test fun sharedMenuAndRoundSaveRenderWithBuiltinAndOptionalLocalPack() {
        val path = InstrumentationRegistry.getArguments().getString("icon_pack_path")
        if (path != null) IconPacks.import(context, File(path).inputStream()) else IconPacks.reset(context)
        compose.setContent { LocalicaTheme { GlassPanelTheme(true) {
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("optical-menu"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionMenuItem("Play now", icon = AppIcon.TransportPlay) {}
                ActionMenuItem("Add to queue", icon = AppIcon.MenuListAddLast) {}
                ActionMenuItem("New queue", icon = AppIcon.TabQueues) {}
                ActionMenuItem("Edit tags", icon = AppIcon.ActionEdit) {}
                ActionMenuItem("Remove from queue", icon = AppIcon.ActionClose) {}
                ActionMenuItem("Select all", icon = AppIcon.TabQueues) {}
                ActionMenuItem("Select range", icon = AppIcon.TabQueues) {}
                Row { ActionIcon(AppIcon.ActionClose, "Close artwork") {}; ActionIcon(AppIcon.ActionSave, "Save artwork") {} }
            }
        } } }
        compose.waitForIdle()
        save(if (path == null) "builtin-menu" else "local-pack-menu", compose.onNodeWithTag("optical-menu").captureToImage().asAndroidBitmap())
        compose.onNodeWithContentDescription("Save artwork").assertExists().performClick()
    }
}
