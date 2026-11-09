package io.github.anastaci1a.localica.icons

import io.github.anastaci1a.localica.ui.AppIcon
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackV2Test {
    private val glyph = """<svg viewBox="0 0 24 24"><path d="M4 4H20V20H4Z"/></svg>""".toByteArray()
    private val other = """<svg viewBox="0 0 24 24"><path d="M4 4L20 20H4Z"/></svg>""".toByteArray()
    private fun prepare(manifest: String, vararg files: Pair<String, ByteArray>): PreparedIconPack {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (files.toList() + ("pack.json" to manifest.toByteArray())).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return IconPacks.prepare(out.toByteArray().inputStream())
    }

    @Test fun v1ImportMigratesAliasesAndHashesIdenticalImages() {
        val prepared = prepare("""{"name":"Legacy","version":"3"}""",
            "icons/tab.home.svg" to glyph, "icons/action.check.svg" to glyph,
            "icons/action.refresh.svg" to other, "icons/action.rescan.svg" to other)
        assertEquals(setOf("nav.home", "selection.confirm", "action.refresh", "action.rescan"), prepared.report.applied.toSet())
        assertTrue(prepared.report.unknown.isEmpty())
        assertEquals(2, prepared.files.size)
        assertEquals(2, prepared.files.values.map(::imageHash).toSet().size)
        assertEquals(2, prepared.manifest.json().getInt("schemaVersion"))
        assertEquals(prepared.manifest.icons["nav.home"], prepared.manifest.icons["selection.confirm"])
        assertSame(prepared.vectors["nav.home"], prepared.vectors["selection.confirm"])
    }

    @Test fun v2ManyIdsUseOneImageAndMetadataSurvivesNormalization() {
        val manifest = """{"schemaVersion":2,"name":"Shared","version":"1","icons":{"nav.home":"shared.svg","action.refresh":"shared.svg"},"images":{"shared.svg":{"opticalOffset":{"x":0,"y":-0.5},"opticalBounds":{"left":2,"top":2,"right":22,"bottom":22},"designedWeight":1.8}}}"""
        val prepared = prepare(manifest, "icons/shared.svg" to glyph)
        assertEquals(1, prepared.files.size)
        assertSame(prepared.vectors["nav.home"], prepared.vectors["action.refresh"])
        val roundTrip = IconPackManifest.parse(prepared.manifest.json().toString().toByteArray())
        assertEquals(1.8, roundTrip.images.getValue("shared.svg").getDouble("designedWeight"), 0.0)
        assertEquals(-0.5, roundTrip.images.getValue("shared.svg").getJSONObject("opticalOffset").getDouble("y"), 0.0)
    }

    @Test fun canonicalWinsRegardlessOfMappingOrderAndBadCanonicalFallsBack() {
        for (reverse in listOf(false, true)) {
            val map = linkedMapOf("tab.home" to "old.svg", "nav.home" to "new.svg")
            val manifest = JSONObject().put("schemaVersion", 2).put("name", "Aliases").put("version", "1")
                .put("icons", JSONObject(if (reverse) map.entries.reversed().associate { it.toPair() } else map)).toString()
            val prepared = prepare(manifest, "icons/old.svg" to glyph, "icons/new.svg" to other)
            assertEquals(listOf("nav.home"), prepared.report.applied)
            assertArrayEquals(other, prepared.files.getValue(prepared.manifest.icons.getValue("nav.home")))
            val fallback = prepare(manifest, "icons/old.svg" to glyph, "icons/new.svg" to "broken".toByteArray())
            assertArrayEquals(glyph, fallback.files.values.single())
            assertEquals("icons/new.svg", fallback.report.rejected.single().first)
        }
    }

    @Test fun unknownIdsSuggestTheClosestCurrentNameAndMissingFilesAreReported() {
        val prepared = prepare("""{"schemaVersion":2,"name":"Report","version":"1","icons":{"nav.home":"good.svg","action.refres":"good.svg","action.rescan":"missing.svg"}}""",
            "icons/good.svg" to glyph)
        assertEquals(listOf("action.refres"), prepared.report.unknown)
        assertEquals("action.refresh", prepared.report.suggestions["action.refres"])
        assertEquals(listOf("icons/missing.svg" to "missing image"), prepared.report.rejected)
    }

    @Test fun manifestRejectsFutureVersionsTraversalAndInvalidMetadata() {
        for (manifest in listOf(
            """{"schemaVersion":3,"name":"x","version":"1","icons":{}}""",
            """{"schemaVersion":2,"name":"x","version":"1","icons":{"nav.home":"../a.svg"}}""",
            """{"schemaVersion":2,"name":"x","version":"1","icons":{"nav.home":"a.svg"},"images":{"a.svg":{"designedWeight":0}}}""",
            """{"schemaVersion":2,"name":"x","version":"1","icons":{"nav.home":"a.svg"},"images":{"a.svg":{"opticalBounds":{"left":3,"right":2,"top":1,"bottom":2}}}}"""
        )) assertThrows(IconPackException::class.java) { IconPackManifest.parse(manifest.toByteArray()) }
    }

    @Test fun aliasesAreDirectAndEveryRegistryIdUsesTheConsistentScheme() {
        AppIcon.legacyIds.forEach { (old, current) ->
            assertTrue(current, current in AppIcon.ids)
            assertFalse(old, old in AppIcon.ids)
            assertEquals(current, AppIcon.byId(old)?.id)
        }
        AppIcon.ids.forEach { assertTrue(it, it.matches(Regex("(nav|transport|action|settings|status|selection|sort|view)\\.[a-z][a-z0-9_]*"))) }
    }

    @Test fun defaultManifestMapsEveryIdToExistingUniqueImages() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/AppIcons.kt").readText()
        val entries = Regex("\\w+\\(\"([^\"]+)\", R\\.drawable\\.(\\w+)\\)").findAll(source).map {
            it.groupValues[1] to "${it.groupValues[2]}.xml"
        }.toList()
        assertEquals(AppIcon.ids, entries.map { it.first }.toSet())
        assertEquals(AppIcon.entries.size, entries.size)
        val images = entries.map { it.second }.distinct().map { File("src/main/res/drawable", it) }
        images.forEach { assertTrue(it.path, it.isFile) }
        assertEquals("No path duplicates in the unique image inventory", images.size, images.map { it.canonicalPath }.toSet().size)
        assertEquals("No byte-identical images in the default pack", images.size, images.map { imageHash(it.readBytes()) }.toSet().size)
        val rows = IconPacks.builtinImageRows()
        assertEquals(AppIcon.ids, rows.flatMap { it.ids }.toSet())
        assertEquals(images.size, rows.size)
    }
}
