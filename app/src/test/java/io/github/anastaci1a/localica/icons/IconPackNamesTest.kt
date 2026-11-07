package io.github.anastaci1a.localica.icons

import io.github.anastaci1a.localica.ui.AppIcon
import org.junit.Assert.*
import org.junit.Test

class IconPackNamesTest {
    @Test fun entryNamesMapToIds() {
        assertEquals("menu.pin", IconPackNames.iconId("icons/menu.pin.png"))
        assertEquals("tab.home", IconPackNames.iconId("icons/tab.home.WEBP"))
        assertEquals("action.repeat_one", IconPackNames.iconId("icons/action.repeat_one.SVG"))
        assertNull(IconPackNames.iconId("icons/menu.pin.jpg"))
        assertNull(IconPackNames.iconId("icons/sub/menu.pin.png"))
        assertNull(IconPackNames.iconId("other/menu.pin.png"))
        assertNull(IconPackNames.iconId("icons/.png"))
    }
    @Test fun sizesAreSquareAndAtLeast96() {
        assertNull(IconPackNames.sizeProblem(96, 96))
        assertNotNull(IconPackNames.sizeProblem(95, 95))
        assertNotNull(IconPackNames.sizeProblem(96, 128))
        assertNotNull(IconPackNames.sizeProblem(2000, 2000))
    }
    @Test fun idsAreUniqueAndDocumented() {
        assertEquals(AppIcon.entries.size, AppIcon.ids.size)
        val docs = java.io.File("../docs/icon-packs.md").readText()
        AppIcon.entries.forEach { assertTrue("docs/icon-packs.md must list ${it.id}", "`${it.id}`" in docs) }
    }
}
