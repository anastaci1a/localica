package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.icons.SvgIconParser
import io.github.anastaci1a.localica.icons.toImageVector
import io.github.anastaci1a.localica.ui.AppIcon
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Upgrade compatibility is checked with distinct artwork, not merely accepted filenames. */
class IconPackAliasTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val aliases = linkedMapOf(
        "action.back" to "action.chevron_left", "action.forward" to "action.arrow_right",
        "action.down" to "action.chevron_down", "action.save_artwork" to "action.save",
        "action.more" to "action.ellipsis", "menu.playlist_add" to "menu.list_create",
        "menu.play_next" to "menu.list_add_next", "menu.queue_add" to "menu.list_add_last",
        "action.playlist_add" to "action.list_add_last",
    )
    private val horizontal = """<svg viewBox="0 0 24 24"><path fill="none" stroke="black" stroke-width="2" d="M3 6 H21"/></svg>""".toByteArray()
    private val vertical = """<svg viewBox="0 0 24 24"><path fill="none" stroke="black" stroke-width="2" d="M18 3 V21"/></svg>""".toByteArray()
    private val installed = mutableSetOf<String>()
    private val manifest = """{"name":"Alias upgrade fixture","version":"697"}""".toByteArray()
    private fun archive(entries: List<Pair<String, ByteArray>>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            (listOf("pack.json" to manifest) + entries).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun png(left: Boolean) = ByteArrayOutputStream().also { out ->
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until 96) for (x in 0 until 96)
            bitmap.setPixel(x, y, if ((x < 48) == left) Color.BLACK else Color.TRANSPARENT)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
    }.toByteArray()
    private fun import(entries: List<Pair<String, ByteArray>>) =
        IconPacks.import(context, archive(entries).inputStream()).also {
            installed += requireNotNull(IconPacks.activeId.value)
        }
    @After fun reset() {
        IconPacks.reset(context)
        installed.forEach { IconPacks.delete(context, it) }
    }

    @Test fun registryAndTemplateExposeOnlyCanonicalIds() {
        val ids = AppIcon.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        aliases.forEach { (old, canonical) ->
            assertFalse("Removed settings row $old", old in ids)
            assertEquals(canonical, AppIcon.byId(old)?.id)
            assertTrue(canonical in ids)
        }
        for (family in listOf("arrow", "chevron")) for (direction in listOf("up", "down", "left", "right"))
            assertNotNull(AppIcon.byId("action.${family}_$direction"))
        val output = ByteArrayOutputStream().also { IconPacks.exportTemplate(context, it) }
        val exported = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.startsWith("icons/")) exported += entry.name.removePrefix("icons/").removeSuffix(".svg")
            }
        }
        assertEquals(ids.toSet(), exported.toSet()); assertEquals(ids.size, exported.size)
    }

    @Test fun aliasOnlyImportsPersistCanonicallyAndReloadTheSameArtwork() {
        val report = import(aliases.keys.map { "icons/$it.svg" to vertical })
        assertTrue(report.unknown.isEmpty()); assertTrue(report.rejected.isEmpty())
        assertEquals(aliases.values.toSet(), report.applied.toSet())
        val folder = File(context.filesDir, "icon-packs/${IconPacks.activeId.value}/icons")
        assertEquals(aliases.values.map { "$it.svg" }.toSet(), folder.list()!!.toSet())
        IconPacks.load(context)
        assertEquals(aliases.values.toSet(), IconPacks.images.value.vectors.keys)
        IconPacks.images.value.vectors.values.forEach { assertEquals(SvgIconParser.parse(vertical).toImageVector(), it) }
    }

    @Test fun oldInstalledAliasFilenamesLoadWithoutRewritingThem() {
        val id = "alias697fixture"
        val directory = File(context.filesDir, "icon-packs/$id").also { File(it, "icons").mkdirs() }
        File(directory, "pack.json").writeBytes(manifest)
        aliases.keys.forEach { File(directory, "icons/$it.svg").writeBytes(horizontal) }
        context.getSharedPreferences("icon-packs", 0).edit().putString("active", id).commit()
        IconPacks.load(context); installed += id
        assertEquals(aliases.values.toSet(), IconPacks.images.value.vectors.keys)
        IconPacks.images.value.vectors.values.forEach { assertEquals(SvgIconParser.parse(horizontal).toImageVector(), it) }
        assertEquals(aliases.keys.map { "$it.svg" }.toSet(), File(directory, "icons").list()!!.toSet())
    }

    @Test fun canonicalArtworkWinsInBothOrdersAcrossRasterAndVectorFormats() {
        for ((old, canonical) in aliases) for (canonicalFormat in listOf("png", "svg"))
            for (aliasFormat in listOf("png", "svg")) for (reverse in listOf(false, true)) {
                val canonicalBytes = if (canonicalFormat == "png") png(true) else horizontal
                val aliasBytes = if (aliasFormat == "png") png(false) else vertical
                val entries = listOf("icons/$canonical.$canonicalFormat" to canonicalBytes, "icons/$old.$aliasFormat" to aliasBytes)
                val prepared = IconPacks.prepare(archive(if (reverse) entries.reversed() else entries).inputStream())
                assertEquals(listOf(canonical), prepared.report.applied)
                if (canonicalFormat == "svg") {
                    assertEquals(SvgIconParser.parse(horizontal).toImageVector(), prepared.vectors[canonical])
                    assertTrue(prepared.masks.isEmpty())
                } else {
                    val image = requireNotNull(prepared.masks[canonical])
                    assertEquals(255, Color.alpha(image.getPixel(image.width / 4, image.height / 2)))
                    assertEquals(0, Color.alpha(image.getPixel(image.width * 3 / 4, image.height / 2)))
                    assertTrue(prepared.vectors.isEmpty())
                }
                assertArrayEquals(canonicalBytes, prepared.files["$canonical.$canonicalFormat"])
                prepared.masks.values.forEach { it.recycle() }
            }
    }

    @Test fun canonicalMixedFormatWinnersSurviveInstallAndReloadWithOriginalBytes() {
        val canonical = "action.ellipsis"
        val old = "action.more"
        for (canonicalFormat in listOf("png", "svg")) for (reverse in listOf(false, true)) {
            val winner = if (canonicalFormat == "png") png(true) else horizontal
            val aliasFormat = if (canonicalFormat == "png") "svg" else "png"
            val loser = if (aliasFormat == "png") png(false) else vertical
            val entries = listOf("icons/$canonical.$canonicalFormat" to winner, "icons/$old.$aliasFormat" to loser)
            import(if (reverse) entries.reversed() else entries)
            val directory = File(context.filesDir, "icon-packs/${IconPacks.activeId.value}/icons")
            assertEquals(setOf("$canonical.$canonicalFormat"), directory.list()!!.toSet())
            assertArrayEquals(winner, File(directory, "$canonical.$canonicalFormat").readBytes())
            IconPacks.load(context)
            if (canonicalFormat == "svg") {
                assertEquals(SvgIconParser.parse(horizontal).toImageVector(), IconPacks.images.value.vectors[canonical])
                assertTrue(IconPacks.images.value.masks.isEmpty())
            } else {
                val bitmap = requireNotNull(IconPacks.images.value.masks[canonical])
                assertEquals(255, Color.alpha(bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)))
                assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)))
                assertTrue(IconPacks.images.value.vectors.isEmpty())
            }
        }
    }

    @Test fun rejectedCanonicalFallsBackToAliasAndBuiltinsRemainAvailable() {
        for ((old, canonical) in aliases) {
            val report = import(listOf("icons/$canonical.svg" to "<svg><script/></svg>".toByteArray(), "icons/$old.svg" to vertical))
            assertEquals(listOf(canonical), report.applied)
            assertEquals("icons/$canonical.svg", report.rejected.single().first)
            assertEquals(SvgIconParser.parse(vertical).toImageVector(), IconPacks.images.value.vectors[canonical])
            IconPacks.load(context)
            assertEquals(SvgIconParser.parse(vertical).toImageVector(), IconPacks.images.value.vectors[canonical])
        }
        IconPacks.reset(context)
        assertTrue(IconPacks.images.value.masks.isEmpty()); assertTrue(IconPacks.images.value.vectors.isEmpty())
        assertEquals(AppIcon.MenuDownload.builtin, requireNotNull(AppIcon.byId("action.save")).builtin)
    }
}
