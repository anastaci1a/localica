package io.github.anastaci1a.localica

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.rememberGlassAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassReturnCheckTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unresolvedReturnStillFailsAtTheOriginalAnimationTimeBound() {
        lateinit var anchor: GlassAnchor
        compose.setContent { LocalicaTheme { anchor = rememberGlassAnchor() } }
        compose.runOnIdle { anchor.hidden = true; anchor.modalPresent = true }
        val start = compose.mainClock.currentTime
        var diagnostics = 0
        var failure: ComposeTimeoutException? = null
        try {
            awaitGlassReturn(compose, anchor) { diagnostics++; "deliberately unresolved return" }
        } catch (timeout: ComposeTimeoutException) {
            failure = timeout
        }
        assertNotNull("A stuck return must still fail", failure)
        val elapsed = compose.mainClock.currentTime - start
        assertTrue("Five-second bound with at most one rounded frame: $elapsed", elapsed in 5000..5032)
        assertEquals("Failure state is captured exactly once", 1, diagnostics)
        assertTrue(anchor.hidden)
        assertTrue(anchor.modalPresent)
    }
}
