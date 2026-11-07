package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** U07.00 (D40): the root-installed stock presenter is the only body path; a missing one fails before anything is presented, and scopes follow the popup generation. */
class PopupPresenterTest {
    @get:Rule val compose = createComposeRule()
    private fun popup(key: Any = "menu") = PopupController(key, PopupMotion(UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it } }), StockUiSpec.expansion, key))

    @Test fun missingPresenterFailsBeforeNativeWindow() {
        var body = 0
        val error = runCatching {
            compose.setContent { val state = remember { popup() }; Popup(state, PopupModel("menu", PopupVariant.Menu)) { body++ } }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("$error", error is IllegalStateException && "stock popup body presenter" in error.message.orEmpty())
        assertEquals(0, body)
    }

    @Test fun modelKeyMustMatchTheStateKey() {
        var presented = 0
        val presenter: PopupBodyPresenter = { _, _, body -> presented++; body() }
        val error = runCatching {
            compose.setContent { val state = remember { popup() }; CompositionLocalProvider(LocalPopupBodyPresenter provides presenter) { Popup(state, PopupModel("other", PopupVariant.Menu)) {} } }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("$error", error is IllegalArgumentException)
        assertEquals(0, presented)
    }

    @Test fun presenterReceivesTheBoundBodyAndStaleScopesStop() {
        val scopes = mutableListOf<PopupScope>()
        lateinit var state: PopupController
        val home = HomeRef("settings", HomeKind.Detached)
        val presenter: PopupBodyPresenter = { s, _, body -> assertSame(state, s); if (s.visible) body() }
        compose.setContent {
            state = remember { popup() }
            CompositionLocalProvider(LocalPopupBodyPresenter provides presenter) {
                Popup(state, PopupModel("menu", PopupVariant.Menu)) { val scope = this; SideEffect { scopes += scope } }
            }
        }
        compose.runOnIdle { assertTrue(scopes.isEmpty()); state.open(home) }
        val first = compose.runOnIdle { scopes.last().also { assertTrue(it.interactive); assertFalse(it.scrubs) } }
        compose.runOnIdle { first.dismiss() }
        compose.runOnIdle { assertFalse(state.visible); assertFalse(first.interactive); state.open(home) }
        compose.runOnIdle {
            val second = scopes.last()
            assertNotSame(first, second); assertTrue(second.interactive); assertFalse(first.interactive)
            first.dismiss(); assertTrue(state.visible)
        }
    }
}
