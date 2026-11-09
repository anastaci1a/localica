package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPackException
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.AppIcon
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() = IconPacks.reset(context)

    private fun png(size: Int, w: Int = size, h: Int = size) = ByteArrayOutputStream().also {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()
    private fun zip(manifest: String?, vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry("pack.json")); z.write(manifest.toByteArray()); z.closeEntry() }
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
    }.toByteArray()
    private val manifest = """{"name":"Test","version":"1","author":"Me"}"""

    @Test fun aSingleIconPackIsValidAndOthersFallBack() {
        val report = IconPacks.import(context, ByteArrayInputStream(zip(manifest, "icons/action.pin.png" to png(96))))
        assertEquals(listOf("action.pin"), report.applied)
        assertTrue(report.unknown.isEmpty() && report.rejected.isEmpty())
        assertNotNull(IconPacks.masks.value["action.pin"])
        assertNull(IconPacks.masks.value["action.remove"])
        assertEquals("Test", IconPacks.info.value?.name)
    }
    @Test fun unknownAndBadImagesAreReportedNotFatal() {
        val report = IconPacks.import(context, ByteArrayInputStream(zip(manifest, "icons/action.pin.png" to png(96),
            "icons/nope.png" to png(96), "icons/action.remove.png" to png(64), "icons/nav.home.png" to png(96, 96, 128),
            "icons/nav.songs.png" to byteArrayOf(1, 2, 3))))
        assertEquals(listOf("action.pin"), report.applied)
        assertEquals(listOf("nope"), report.unknown)
        assertEquals(setOf("icons/action.remove.png", "icons/nav.home.png", "icons/nav.songs.png"), report.rejected.map { it.first }.toSet())
    }
    @Test fun unusablePacksAreRefusedAndKeepTheCurrentPack() {
        IconPacks.import(context, ByteArrayInputStream(zip(manifest, "icons/action.pin.png" to png(96))))
        assertThrows(IconPackException::class.java) { IconPacks.import(context, ByteArrayInputStream(zip(null, "icons/action.pin.png" to png(96)))) }
        assertThrows(IconPackException::class.java) { IconPacks.import(context, ByteArrayInputStream(zip(manifest))) }
        assertThrows(IconPackException::class.java) { IconPacks.import(context, ByteArrayInputStream(zip("""{"name":"x"}""", "icons/action.pin.png" to png(96)))) }
        assertNotNull(IconPacks.masks.value["action.pin"])
    }
    @Test fun packPersistsAcrossReloadAndResetClearsIt() {
        IconPacks.import(context, ByteArrayInputStream(zip(manifest, "icons/action.pin.png" to png(96))))
        IconPacks.load(context)
        assertNotNull(IconPacks.masks.value["action.pin"])
        IconPacks.reset(context)
        IconPacks.load(context)
        assertTrue(IconPacks.masks.value.isEmpty())
        assertNull(IconPacks.info.value)
    }
    @Test fun templateExportReimportsIdentically() {
        val out = ByteArrayOutputStream()
        IconPacks.exportTemplate(context, out)
        val report = IconPacks.import(context, ByteArrayInputStream(out.toByteArray()))
        assertEquals(AppIcon.ids, report.applied.toSet())
        assertTrue(report.unknown.isEmpty() && report.rejected.isEmpty())
        val first = IconPacks.images.value.vectors
        assertEquals(AppIcon.ids, first.keys)
        val again = ByteArrayOutputStream()
        IconPacks.exportTemplate(context, again)
        IconPacks.reset(context)
        IconPacks.import(context, ByteArrayInputStream(again.toByteArray()))
        val second = IconPacks.images.value.vectors
        assertEquals(first, second)
        assertTrue(first.values.all { it.root.size > 0 })
    }
}
