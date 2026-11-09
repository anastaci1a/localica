package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.anastaci1a.localica.ui.LocalicaTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import io.github.anastaci1a.localica.ui.AppIcon
import io.github.anastaci1a.localica.ui.IconPackSettings
import io.github.anastaci1a.localica.ui.IconPackViewer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(app.cacheDir, "icon-library-tests")
    private var forbidSource = false
    private var resolverAccesses = 0
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = root
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("icon-library-tests-$name", mode)
        override fun getContentResolver(): android.content.ContentResolver {
            resolverAccesses++
            check(!forbidSource) { "Switching must never reopen a source URI" }
            return super.getContentResolver()
        }
    }
    @Before fun setup() {
        root.deleteRecursively(); root.mkdirs()
        context.getSharedPreferences("icon-packs", 0).edit().clear().commit()
        IconPacks.load(context)
    }
    @After fun cleanup() {
        forbidSource = false
        IconPacks.reset(context)
        root.deleteRecursively()
        context.getSharedPreferences("icon-packs", 0).edit().clear().commit()
        IconPacks.load(app)
    }
    private fun png(size: Int = 96) = ByteArrayOutputStream().also { out ->
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
    }.toByteArray()
    private fun pack(name: String = "First", id: String = "action.pin", extra: Boolean = false) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("pack.json", """{"name":"$name","version":"1","author":"Tester"}""".toByteArray())
            entry("icons/$id.png", png())
            if (extra) { entry("icons/future.icon.png", png()); entry("icons/action.remove.png", byteArrayOf(1, 2, 3)) }
        }
    }.toByteArray()
    private fun import(name: String = "First", id: String = "action.pin", extra: Boolean = false): String {
        IconPacks.import(context, pack(name, id, extra).inputStream())
        return requireNotNull(IconPacks.activeId.value)
    }

    @Test fun largeSourceImagesStayStoredWhileDecodedLibraryMasksAreBounded() {
        val source = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json")); zip.write("""{"name":"Large","version":"1"}""".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("icons/action.pin.png")); zip.write(png(1024)); zip.closeEntry()
        } }.toByteArray()
        IconPacks.import(context, source.inputStream())
        val id = requireNotNull(IconPacks.activeId.value)
        assertEquals(192, IconPacks.masks.value.getValue("action.pin").width)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(File(root, "icon-packs/$id/icons/action.pin.png").path, bounds)
        assertEquals(1024, bounds.outWidth)
        IconPacks.load(context)
        assertEquals(192, IconPacks.masks.value.getValue("action.pin").width)
        assertEquals(android.graphics.Color.BLACK, IconPacks.masks.value.getValue("action.pin").getPixel(96, 96))
    }

    @Test fun switchUsesCachedImagesAfterBothSourceUrisAreRemoved() {
        val sources = listOf(File(root, "first.zip"), File(root, "second.zip"))
        sources[0].writeBytes(pack()); sources[1].writeBytes(pack("Second", "transport.play"))
        val ids = sources.map { source ->
            context.contentResolver.openInputStream(Uri.fromFile(source))!!.use { IconPacks.import(context, it) }
            requireNotNull(IconPacks.activeId.value)
        }
        val cached = IconPacks.library.value.associate { it.id to it.masks }
        sources.forEach { assertTrue(it.delete()) }
        forbidSource = true
        // Even internal images are unavailable: switching must retain the original decoded objects.
        val stored = File(root, "icon-packs")
        val hidden = File(root, "hidden")
        assertTrue(stored.renameTo(hidden))
        try {
            repeat(4) { ids.forEach { id ->
                IconPacks.select(context, id)
                assertSame(cached[id], IconPacks.masks.value)
                assertEquals(id, IconPacks.activeId.value)
            } }
            assertEquals("Exactly the two imports access their document resolver", 2, resolverAccesses)
        } finally { assertTrue(hidden.renameTo(stored)) }
        IconPacks.load(context)
        assertEquals(ids.last(), IconPacks.activeId.value)
    }

    @Test fun deletingActiveFallsBackAndKeepsOtherPackAcrossReload() {
        val first = import()
        val second = import("Second", "transport.play")
        IconPacks.delete(context, second)
        assertNull(IconPacks.activeId.value)
        assertTrue(IconPacks.masks.value.isEmpty())
        IconPacks.load(context)
        assertNull(IconPacks.activeId.value)
        assertEquals(listOf(first), IconPacks.library.value.map { it.id })
        IconPacks.select(context, first)
        IconPacks.reset(context)
        assertEquals(1, IconPacks.library.value.size)
    }

    @Test fun legacySinglePackMigratesOnceAndRemainsActive() {
        val legacy = File(root, "icon-pack")
        File(legacy, "icons").mkdirs()
        File(legacy, "pack.json").writeText("""{"name":"Legacy","version":"2","author":"Earlier"}""")
        File(legacy, "icons/action.pin.png").writeBytes(png())
        IconPacks.load(context)
        val migrated = IconPacks.library.value.single()
        assertEquals("Legacy", migrated.report.info.name)
        assertEquals(migrated.id, IconPacks.activeId.value)
        assertNotNull(IconPacks.masks.value["action.pin"])
        assertFalse(legacy.exists())
        IconPacks.load(context)
        assertEquals(listOf(migrated.id), IconPacks.library.value.map { it.id })
        assertEquals(migrated.id, IconPacks.activeId.value)
    }

    @Test fun reimportPreparesWithoutMutationThenUpdatesInPlaceAndPersistsReport() {
        val first = import()
        val prepared = IconPacks.prepare(pack(id = "transport.play", extra = true).inputStream())
        assertEquals(first, IconPacks.matching(context, prepared)?.id)
        assertNotNull(IconPacks.masks.value["action.pin"])
        IconPacks.install(context, prepared)
        assertEquals(first, IconPacks.activeId.value)
        assertEquals(1, IconPacks.library.value.size)
        assertNull(IconPacks.masks.value["action.pin"])
        assertNotNull(IconPacks.masks.value["transport.play"])
        IconPacks.load(context)
        val stored = IconPacks.library.value.single()
        assertEquals(first, stored.id)
        assertEquals(listOf("future.icon"), stored.report.unknown)
        assertEquals("icons/action.remove.png", stored.report.rejected.single().first)
        assertTrue(stored.importedAt > 0)
    }

    @Test fun viewerListsEveryReassignedAndFallbackIdAndImportProblems() {
        val first = import(extra = true)
        IconPacks.load(context)
        compose.setContent { LocalicaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { IconPackViewer(first) } } }
        AppIcon.entries.forEach { compose.onNodeWithTag("pack-icon-${it.id}").assertExists() }
        compose.onAllNodesWithText("Reassigned").assertCountEquals(1)
        compose.onAllNodesWithText("Built-in (fallback)").assertCountEquals(IconPacks.builtinImageRows().count { row -> row.ids.any { it != "action.pin" } })
        compose.onNodeWithText("future.icon").assertExists()
        compose.onNodeWithText("icons/action.remove.png: not a decodable image").assertExists()
        listOf("Navigation", "Transport", "Selection", "Actions").forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test fun listSelectsLiveAndDeleteRequiresConfirmation() {
        val first = import()
        import("Second", "transport.play")
        compose.setContent {
            val registryOwner = checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
            androidx.compose.runtime.CompositionLocalProvider(
            androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner,
            androidx.compose.ui.platform.LocalContext provides context) {
            LocalicaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { IconPackSettings {} } }
        } }
        compose.onNodeWithText("First").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(first, IconPacks.activeId.value) }
        compose.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, IconPacks.library.value.size) }
        compose.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5_000) { IconPacks.library.value.size == 1 }
        compose.runOnIdle { assertNull(IconPacks.activeId.value) }
    }
}
