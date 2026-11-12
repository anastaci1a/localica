package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #854 review F5: a resting popup sits exactly where v0.34.1 puts it, for every motion kind and window shape: a 12 dp window
 * margin (0 for Pages). The spring overshoot exists only in flight and never moves or shrinks the resting layout. */
class PopupRestMarginTest {
    @Test fun theRestingMarginIsV0341sForEveryDensity() {
        for (scale in listOf(1f, 2f, 2.4375f, 2.625f, 3.5f)) {
            val density = Density(scale)
            assertEquals("v0.34.1 used 12.dp.toPx()", 12f * scale, density.popupMarginPx(page = false), 0f)
            assertEquals(0f, density.popupMarginPx(page = true), 0f)
        }
    }

    /** Source contract: the popup layout's margin has one input, the placement; no motion term (overshoot, kind, law) reaches it. */
    @Test fun noMotionTermReachesTheRestingLayout() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/GlassMorph.kt").readText()
        val margins = Regex("""val margin = ([^\n]*)""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(listOf("density.popupMarginPx(page)"), margins)
        // Comments may name the overshoot; code may not read or compute one for layout.
        val motionTerm = Regex("""\.overshoot\b|val overshoot\b|springOvershoot\(""")
        assertFalse("no overshoot term in the popup layout", motionTerm.containsMatchIn(source))
        assertFalse(motionTerm.containsMatchIn(File("src/main/java/io/github/anastaci1a/localica/ui/engine/PopupWindowMotion.kt").readText()))
    }
}
