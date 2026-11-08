package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #655 settings icon audit and #649 download glyph. */
class SettingsIconAuditTest {
    private fun drawable(name: String) = File("src/main/res/drawable/$name.xml").readText()
    private fun pathData(xml: String) = Regex("android:pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.joinToString(" ")

    @Test fun noTwoSettingsRowsShareAnIcon() {
        val shared = settingsDestinationIcons.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("Settings rows sharing an icon: $shared", shared.isEmpty())
    }

    @Test fun dedicatedSettingsIconsAreStandardWeightVectors() {
        val dedicated = settingsDestinationIcons.values.filter { it.id.startsWith("settings.") }
        assertTrue(dedicated.isNotEmpty())
        dedicated.forEach { icon ->
            val xml = drawable("ic_" + icon.id.replace('.', '_'))
            assertTrue("${icon.id} uses the 1.8 standard stroke", "android:strokeWidth=\"1.8\"" in xml)
            assertTrue("${icon.id} uses round caps", "android:strokeLineCap=\"round\"" in xml)
            assertTrue("${icon.id} uses round joins", "android:strokeLineJoin=\"round\"" in xml)
            assertTrue("${icon.id} is a 24-unit vector", "android:viewportWidth=\"24\"" in xml)
        }
    }

    @Test fun downloadIconIsATrayWithADownArrowNotAPlainArrow() {
        val xml = drawable("ic_download")
        val subpaths = pathData(xml).count { it == 'M' || it == 'm' }
        assertTrue("ic_download needs a shaft, an arrowhead and a tray, found $subpaths subpaths", subpaths >= 3)
        assertTrue("android:strokeWidth=\"1.8\"" in xml)
        assertEquals("action.save_artwork", AppIcon.ActionSaveArtwork.id)
        assertEquals(AppIcon.MenuDownload.builtin, AppIcon.ActionSaveArtwork.builtin)
    }
}
