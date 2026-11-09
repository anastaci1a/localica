package io.github.anastaci1a.localica.icons

import io.github.anastaci1a.localica.ui.AppIcon
import org.junit.Assert.*
import org.junit.Test

class IconPackNamesTest {
    @Test fun entryNamesMapToIds() {
        assertEquals("action.pin", IconPackNames.iconId("icons/action.pin.png"))
        assertEquals("nav.home", IconPackNames.iconId("icons/nav.home.WEBP"))
        assertEquals("transport.repeat_one", IconPackNames.iconId("icons/transport.repeat_one.SVG"))
        assertNull(IconPackNames.iconId("icons/action.pin.jpg"))
        assertNull(IconPackNames.iconId("icons/sub/action.pin.png"))
        assertNull(IconPackNames.iconId("other/action.pin.png"))
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
