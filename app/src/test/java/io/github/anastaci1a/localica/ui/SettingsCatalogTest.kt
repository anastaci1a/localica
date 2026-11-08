package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    @Test fun everySettingHasAUniqueIdentityAndIsReachableByItsFullTitle() {
        val entries = SettingsCatalog.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach { spec ->
            assertTrue(spec.title.isNotBlank())
            assertTrue("Missing page for ${spec.id}", spec.page in SettingsCatalog.parents)
            assertTrue("Unsearchable ${spec.id}", SettingsCatalog.search(spec.title).any { it.setting == spec })
            spec.revealedBy?.let { reveal -> assertTrue("Missing reveal for ${spec.id}", entries.any { it.id == reveal }) }
        }
    }

    @Test fun choiceValuesAndSynonymsFindTheOwningControl() {
        SettingsCatalog.entries.forEach { spec -> spec.values.forEach { value ->
            assertTrue("${spec.id} cannot be reached by $value", SettingsCatalog.search(value).any { it.setting == spec })
        } }
        assertTrue(SettingsCatalog.search("grey").any { it.title == "Interface gray" })
        assertEquals("Lyrics timing correction", SettingsCatalog.search("sync").first().title)
        assertEquals("Exclude short files", SettingsCatalog.search("minimum duration").first().title)
        assertEquals("Control expansion", SettingsCatalog.search("control expansion").first().title)
    }

    @Test fun tokenMatchingIsAndNormalizedAndHasNoTypoTolerance() {
        assertEquals(SettingsCatalog.search("grey"), SettingsCatalog.search("GRÁY"))
        assertTrue(SettingsCatalog.search("blurr").isEmpty())
        assertTrue(SettingsCatalog.search("blur nonexistent").isEmpty())
        assertTrue(SettingsCatalog.search("  ").isEmpty())
        assertTrue(SettingsCatalog.search("glass").size <= 30)
    }

    @Test fun topLevelOrderAndPlayerParentStayConstantAndGlassHasInternalPages() {
        assertEquals(listOf("Interface", "Playback and queues", "Library and tags", "About and licenses"),
            SettingsCatalog.parents.filterValues { it == null }.keys.toList())
        assertEquals(listOf("Interface", "Player"), SettingsCatalog.path("Player"))
        for (page in listOf("Glass appearance", "Glass advanced", "Navigation selector")) {
            assertEquals(listOf("Interface", "Glass", page), SettingsCatalog.path(page))
        }
        SettingsCatalog.parents.keys.forEach { page -> assertEquals(page, SettingsCatalog.path(page).last()) }
    }
}
