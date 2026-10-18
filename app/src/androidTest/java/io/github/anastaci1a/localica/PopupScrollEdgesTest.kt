package io.github.anastaci1a.localica

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
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
        change { runBlocking { scroll.scrollTo(scroll.maxValue / 2) } }
        assertEdges(top = 0f, bottom = 0f)
        assertEquals(header, compose.onNodeWithText("Scrolling options").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot)
        change { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        assertEdges(top = 0f, bottom = 1f)
        change { tall = false }
        compose.runOnIdle { assertEquals(0, scroll.maxValue) }
        assertEdges(top = 1f, bottom = 1f)
        change { tall = true }
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
        change { runBlocking { scroll.scrollTo(scroll.maxValue / 2) } }
        val automatic = pixels()
        change { explicit = true }
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
        change { modal = true }
        assertEdges(top = 0f, bottom = 1f)
        change { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        assertEdges(top = 1f, bottom = 0f)
    }

    @Test fun lazyModalBodiesRespectPartialRowsPaddingAndContentThatFits() {
        lateinit var scroll: LazyListState
        var count by mutableIntStateOf(8)
        var paddingPixels = 0
        var halfBandPixels = 0
        compose.setContent { LocalicaTheme {
            scroll = rememberLazyListState()
            paddingPixels = with(LocalDensity.current) { 16.dp.roundToPx() }
            halfBandPixels = with(LocalDensity.current) { 12.dp.roundToPx() }
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassAlertDialog({}, confirmButton = {}, text = {
                    ScrollFrame {
                        LazyColumn(Modifier.fillMaxWidth().height(120.dp).scrollEdgeFade(scroll)
                            .background(Color.White), state = scroll, contentPadding = PaddingValues(vertical = 16.dp)) {
                            items(count) { Spacer(Modifier.fillMaxWidth().height(60.dp)) }
                        }
                    }
                })
            }
        } }
        assertEdges(top = 1f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(0, paddingPixels / 2) } }
        assertEdges(top = 1f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(0, paddingPixels + halfBandPixels) } }
        assertEdges(top = .5f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(2, 20) } }
        assertEdges(top = 0f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(count - 1) } }
        assertEdges(top = 0f, bottom = 1f)
        change { count = 1 }
        assertEdges(top = 1f, bottom = 1f)
    }

    @Test fun registeredModalGridsShareFadesAcrossUnequalRowsAndContentChanges() {
        lateinit var scroll: LazyGridState
        var count by mutableIntStateOf(12)
        compose.setContent { LocalicaTheme {
            scroll = rememberLazyGridState()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassAlertDialog({}, confirmButton = {}, text = {
                    ScrollFrame {
                        LazyVerticalGrid(GridCells.Fixed(2), state = scroll,
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                                .listTopFade().controlScrollParent(scroll, ControlScrollAxis.Vertical).background(Color.White),
                            contentPadding = PaddingValues(vertical = 16.dp)) {
                            items(count) { index -> Spacer(Modifier.height(if (index % 2 == 0) 80.dp else 60.dp)) }
                        }
                    }
                })
            }
        } }
        assertEdges(top = 1f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(4, 20) } }
        assertEdges(top = 0f, bottom = 0f)
        change { runBlocking { scroll.scrollToItem(count - 1) } }
        assertEdges(top = 0f, bottom = 1f)
        change { count = 1 }
        assertEdges(top = 1f, bottom = 1f)
    }

    @Composable private fun ScrollSample(scroll: ScrollState, tall: Boolean,
        explicit: Boolean = false, reversed: Boolean = false) {
        ScrollFrame {
            Column(Modifier.fillMaxWidth().height(120.dp)
                .then(if (explicit) Modifier.scrollEdgeFade(scroll, reversed) else Modifier)
                .controlVerticalScroll(scroll, reverseScrolling = reversed)) {
                Box(Modifier.fillMaxWidth().height(if (tall) 600.dp else 120.dp).background(Color.White))
            }
        }
    }

    @Composable private fun ScrollFrame(content: @Composable () -> Unit) {
        val view = LocalView.current
        SideEffect { sampleView = view }
        Box(Modifier.width(180.dp).background(Color.Black).testTag("scroll-sample")) {
            content()
            PresentationMarker { stage }
        }
    }

    private fun change(block: () -> Unit) {
        compose.runOnIdle(block)
        // Publish frame identity after layout/modifier changes have become idle,
        // so a new marker cannot validate the preceding viewport recording.
        compose.runOnIdle { stage++ }
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
