package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupScrollEdgesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var sampleView: android.view.View
    private var stage by mutableIntStateOf(1)

    @Test fun modalBodiesFadeAtOverflowEdgesWithoutMovingTheirChrome() {
        lateinit var scroll: ScrollState
        var tall by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassAlertDialog({}, title = { Text("Scrolling options") },
                    confirmButton = { ActionTextButton({}) { Text("Done") } }, text = {
                        ScrollSample(scroll, tall)
                    })
            }
        } }
        val header = compose.onNodeWithText("Scrolling options").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertEdges(top = 1f, bottom = 0f)
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue / 2) }; stage++ }
        assertEdges(top = 0f, bottom = 0f)
        assertEquals(header, compose.onNodeWithText("Scrolling options").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) }; stage++ }
        assertEdges(top = 0f, bottom = 1f)
        compose.runOnIdle { tall = false; stage++ }
        compose.runOnIdle { assertEquals(0, scroll.maxValue) }
        assertEdges(top = 1f, bottom = 1f)
        compose.runOnIdle { tall = true; stage++ }
        assertEdges(top = 1f, bottom = 0f)
    }

    @Test fun existingExplicitFadeIsNotAppliedTwiceByTheModalScrollPolicy() {
        lateinit var scroll: ScrollState
        var explicit by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassAlertDialog({}, confirmButton = {}, text = {
                    ScrollSample(scroll, tall = true, explicit = explicit)
                })
            }
        } }
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue / 2) }; stage++ }
        val automatic = pixels()
        compose.runOnIdle { explicit = true; stage++ }
        val existing = pixels()
        assertEquals(automatic.width, existing.width)
        assertEquals(automatic.height, existing.height)
        for (y in 0 until automatic.height) {
            assertEquals("One fade at row $y", automatic[automatic.width / 2, y].red,
                existing[existing.width / 2, y].red, .02f)
        }
    }

    @Test fun fullPagesKeepTheirOwnTreatmentAndReversedModalScrollingFadesTheCorrectEdge() {
        lateinit var scroll: ScrollState
        var modal by mutableStateOf(false)
        compose.setContent {
            scroll = rememberScrollState()
            CompositionLocalProvider(LocalPopupScrollEdges provides modal) {
                ScrollSample(scroll, tall = true, reversed = true)
            }
        }
        assertEdges(top = 1f, bottom = 1f)
        compose.runOnIdle { modal = true; stage++ }
        assertEdges(top = 0f, bottom = 1f)
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) }; stage++ }
        assertEdges(top = 1f, bottom = 0f)
    }

    @Composable private fun ScrollSample(scroll: ScrollState, tall: Boolean,
        explicit: Boolean = false, reversed: Boolean = false) {
        val view = LocalView.current
        SideEffect { sampleView = view }
        Box(Modifier.width(180.dp).background(Color.Black).testTag("scroll-sample")) {
            Column(Modifier.fillMaxWidth().height(120.dp)
                .then(if (explicit) Modifier.scrollEdgeFade(scroll, reversed) else Modifier)
                .controlVerticalScroll(scroll, reverseScrolling = reversed)) {
                Box(Modifier.fillMaxWidth().height(if (tall) 600.dp else 120.dp).background(Color.White))
            }
            PresentationMarker { stage }
        }
    }

    private fun pixels() = capturePresentedPopupPixels(compose,
        compose.onNodeWithTag("scroll-sample"), stage) { sampleView }

    private fun assertEdges(top: Float, bottom: Float) {
        val pixels = pixels()
        val x = pixels.width / 2
        assertEquals("Top edge", top, pixels[x, 1].red, .08f)
        assertEquals("Bottom edge", bottom, pixels[x, pixels.height - 2].red, .08f)
        assertEquals("Middle stays visible", 1f, pixels[x, pixels.height / 2].red, .02f)
    }
}
