package io.github.anastaci1a.localica.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** New static controls must join the search catalog in the same change. Dynamic groups index their parent. */
class SettingsCatalogSourceTest {
    @Test fun everyStaticSettingsControlHasACatalogIdentity() {
        val directory = File("src/main/java/io/github/anastaci1a/localica/ui")
        val sources = listOf("FolderSettings.kt", "OnlineFeaturesSettings.kt", "ArtistPhotosSettings.kt", "InterfaceSettings.kt", "GlassSettings.kt", "QueueScreen.kt",
            "IconPackSettings.kt", "LibraryFilterSettings.kt", "DiagnosticsSettings.kt", "LibraryLicenses.kt", "TagEditor.kt")
        val call = Regex("""(?:BooleanPreference|SliderPreference|GlassSlider|ArtworkActionPreference|ChoicePreference|SettingsLink|SettingsEntry)\("([^"\n]+)"""")
        val known = SettingsCatalog.entries.map { it.id }.toSet() + SettingsCatalog.parents.keys
        val missing = mutableListOf<String>()
        for (source in sources) {
            val text = directory.resolve(source).readText()
            // QueueScreen and TagEditor contain unrelated player/editor controls too; only inventory the settings function.
            val settingsText = when (source) {
                "QueueScreen.kt" -> text.substringAfter("internal fun QueueSettingsControls").substringBefore("internal fun SongTap.label")
                "TagEditor.kt" -> text.substringAfter("internal fun TagRecoverySettings").substringBefore("internal fun TagEditorDialog")
                else -> text
            }
            for (match in call.findAll(settingsText)) {
                val label = match.groupValues[1].substringBefore(" · ").substringBefore(":")
                if (label.contains("$")) continue // A helper is checked through its statically named callers.
                val canonical = if (label == "Correction") "Lyrics timing correction" else label
                if (canonical !in known) missing += "$source: $label"
            }
        }
        assertEquals("Unindexed settings", emptyList<String>(), missing)
    }
}
