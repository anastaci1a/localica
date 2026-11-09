package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class OnlineFeaturesCatalogTest {
    @Test fun onlineServicesHaveOneParentAndDeepSearchFindsTheirControls() {
        assertEquals(listOf("Online features", "Last.fm"), SettingsCatalog.path("Last.fm"))
        assertEquals(listOf("Online features", "Artist photos"), SettingsCatalog.path("Artist photos"))
        for (label in listOf("Online features", "Last.fm username", "Sync now", "Use Last.fm counts",
            "Review matches", "Photo sources", "Photo cache size", "Clear photo cache", "Refresh all photos")) {
            assertTrue("Unsearchable $label", SettingsCatalog.search(label).any { it.setting?.id == label })
        }
    }
}
