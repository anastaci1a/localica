package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupEnvelopeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogHostUsesNaturalWidthWithoutQueryingTheLiveSubcomposition() {
        var natural by mutableStateOf(220.dp)
        var expected = 0
        compose.setContent { LocalicaTheme {
            expected = with(LocalDensity.current) { natural.roundToPx() }
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GlassAlertDialog({}, { ActionTextButton({}) { androidx.compose.material3.Text("Save") } },
                    modifier = Modifier.testTag("natural-dialog"),
                    title = { androidx.compose.material3.Text("Options") },
                    widthMeasurement = {
                        Box(Modifier.width(natural)) { androidx.compose.material3.Text("inert width face") }
                    }, text = {
                        // This cannot answer intrinsic requests: only the declared face may do so.
                        BoxWithConstraints {
                            check(constraints.hasBoundedWidth)
                            androidx.compose.material3.Text("Live choices")
                        }
                    })
            }
        } }
        compose.onNodeWithText("Live choices").assertIsDisplayed()
        compose.onNodeWithText("inert width face").assertDoesNotExist()
        assertEquals(expected, compose.onNodeWithTag("natural-dialog").fetchSemanticsNode().size.width)
        compose.runOnIdle { natural = 260.dp }
        compose.onNodeWithText("Save").assertIsDisplayed()
        assertEquals(expected, compose.onNodeWithTag("natural-dialog").fetchSemanticsNode().size.width)
    }

    @Test fun widthEnvelopeReactsToWindowAndDeclarationWithoutExposingMeasurement() {
        var cap by mutableStateOf(300.dp)
        var wide by mutableStateOf(true)
        var expected = 0
        compose.setContent { LocalicaTheme {
            expected = with(LocalDensity.current) { minOf(cap, if (wide) 240.dp else 160.dp).roundToPx() }
            Box(Modifier.width(cap)) {
                PopupNaturalWidth(measurement = {
                    PopupMeasurementMaximum {
                        Box(Modifier.width(160.dp)) { androidx.compose.material3.Text("hidden short") }
                        if (wide) Box(Modifier.width(240.dp)) { androidx.compose.material3.Text("hidden wide") }
                    }
                }) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).testTag("natural-body")) {
                        check(constraints.hasBoundedWidth)
                        androidx.compose.material3.Text("Visible")
                    }
                }
            }
        } }
        fun checkWidth() {
            val actual = compose.onNodeWithTag("natural-body").fetchSemanticsNode().size.width
            assertEquals(expected, actual)
            compose.onNodeWithText("hidden short").assertDoesNotExist()
            compose.onNodeWithText("hidden wide").assertDoesNotExist()
        }
        checkWidth()
        compose.runOnIdle { wide = false }; checkWidth()
        compose.runOnIdle { cap = 120.dp }; checkWidth()
        compose.runOnIdle { cap = 300.dp }; checkWidth()
    }

    @Test fun statelessFacesMatchLiveFieldHeightsAtLargeFontsAndNarrowWidths() {
        var scale by mutableFloatStateOf(1f)
        var width by mutableStateOf(180.dp)
        val sizes = mutableMapOf<String, IntSize>()
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                    @Composable fun Sample(key: String, body: @Composable () -> Unit) {
                        Box(Modifier.fillMaxWidth().onSizeChanged { sizes[key] = it }) { body() }
                    }
                    Sample("live-link") { SettingsLink("A lengthy option heading", "A longer summary that wraps on narrow screens") {} }
                    Sample("face-link") { SettingsLinkFace("A lengthy option heading", "A longer summary that wraps on narrow screens", null) }
                    Sample("live-slider") { SliderPreference("Items per row: 5", 5f, {}, valueRange = 1f..5f, steps = 3) }
                    Sample("face-slider") { SliderPreferenceFrame("Items per row: 5", null, null, null) { Spacer(Modifier.fillMaxWidth().height(InterfaceSliderHeight)) } }
                    Sample("live-switch") { BooleanPreference("Save only for this list", false) {} }
                    Sample("face-switch") { BooleanPreferenceFrame("Save only for this list") { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) } }
                    Sample("live-action") { GlassActionButton(R.drawable.ic_shuffle, "Randomize") {} }
                    Sample("face-action") { Box(Modifier.heightIn(min = CompactControlSize)) { GlassActionButtonFace(R.drawable.ic_shuffle, "Randomize") } }
                    Sample("live-strip") { GlassActionRow {
                        GlassActionButton(R.drawable.ic_shuffle, "Randomize") {}
                        GlassActionButton(R.drawable.ic_seek_back, "Revert") {}
                    } }
                    Sample("face-strip") { GlassActionRowMeasurement {
                        GlassActionButtonFace(R.drawable.ic_shuffle, "Randomize", Modifier.heightIn(min = CompactControlSize))
                        GlassActionButtonFace(R.drawable.ic_seek_back, "Revert", Modifier.heightIn(min = CompactControlSize))
                    } }
                    val labels = listOf("Compact", "Normal", "Large")
                    Sample("live-choice") { ChoicePreference("Size", "Normal", labels, { it }) {} }
                    Sample("face-choice") {
                        ChoicePreferenceFrame("Size", labels, false, choices = {
                            GlassChoicesMeasurement(labels)
                        }, link = { SettingsLinkFace("Size", "Normal", null) })
                    }
                    Sample("live-button") { ActionTextButton({}) { androidx.compose.material3.Text("Use list-type defaults") } }
                    Sample("face-button") { TextButtonMeasurement("Use list-type defaults") }
                    Sample("live-apply-actions") { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionTextButton({}) { androidx.compose.material3.Text("Close") }
                        ActionTextButton({}) { androidx.compose.material3.Text("Apply") }
                    } }
                    Sample("live-save-actions") { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionTextButton({}) { androidx.compose.material3.Text("Cancel") }
                        ActionTextButton({}) { androidx.compose.material3.Text("Save") }
                    } }
                    Sample("face-actions") { BrowseOptionsActionsMeasurement() }
                }
            }
        } }
        for (fieldWidth in listOf(180.dp, 360.dp)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { scale = fontScale; width = fieldWidth }
            compose.runOnIdle {
                for (kind in listOf("link", "slider", "switch", "button", "action", "strip", "choice"))
                    assertEquals("$kind at font scale $fontScale, width $fieldWidth", sizes.getValue("live-$kind").height, sizes.getValue("face-$kind").height)
                assertEquals("Action envelope at $fontScale / $fieldWidth",
                    maxOf(sizes.getValue("live-apply-actions").height, sizes.getValue("live-save-actions").height),
                    sizes.getValue("face-actions").height)
            }
        }
    }

    @Test fun choiceMeasurementPreservesSeparatePixelRoundingAtFractionalDensity() {
        val sizes = mutableMapOf<String, IntSize>()
        compose.setContent { LocalicaTheme {
            // Pixel 2 / API 26: 52dp rounds to 137px and each 4dp inset to 11px.
            // Summing first incorrectly rounds 68dp to 179px instead of 181px.
            CompositionLocalProvider(LocalDensity provides Density(2.625f, 1f)) {
                Column(Modifier.width(240.dp)) {
                    @Composable fun Sample(key: String, body: @Composable () -> Unit) {
                        Box(Modifier.fillMaxWidth().onSizeChanged { sizes[key] = it }) { body() }
                    }
                    val labels = listOf("Ascending", "Descending")
                    Sample("live") { GlassChoices(labels, 0, horizontal = true) {} }
                    Sample("measurement") { GlassChoicesMeasurement(labels) }
                    Sample("summed-spacer") {
                        Spacer(Modifier.fillMaxWidth().height(glassChoiceCellHeight() +
                            ChoiceOuterVerticalInset * 2 + ChoiceInnerInset * 2))
                    }
                }
            }
        } }
        compose.runOnIdle {
            assertEquals(181, sizes.getValue("live").height)
            assertEquals(sizes.getValue("live"), sizes.getValue("measurement"))
            assertEquals(179, sizes.getValue("summed-spacer").height)
        }
    }

    @Test fun mainAndQueueEnvelopesStayStableAcrossAllSupportedDraftsAndWindowChanges() {
        val main = io.github.anastaci1a.localica.navigation.BrowseRoute(io.github.anastaci1a.localica.navigation.MainTab.Library)
        val queue = io.github.anastaci1a.localica.navigation.BrowseRoute(
            io.github.anastaci1a.localica.navigation.MainTab.Queues,
            io.github.anastaci1a.localica.navigation.MediaRef(io.github.anastaci1a.localica.navigation.MediaKind.Queue, "fixture"))
        var route by mutableStateOf(main)
        var draft by mutableStateOf(io.github.anastaci1a.localica.browse.BrowseStyle())
        var width by mutableStateOf(360.dp)
        var height by mutableStateOf(640.dp)
        var scale by mutableFloatStateOf(1f)
        var generation by mutableIntStateOf(0)
        var randomizeOk = true
        var densityValue = 1f
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            densityValue = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalReducedMotion provides true, LocalInlineGlassDialog provides true) {
                Box(Modifier.width(width).height(height)) {
                    key(generation) {
                        val mutable = route == queue
                        BrowseSortDialog(route, draft, true, {}, { _, _ -> }, {},
                            onRandomize = if (mutable) ({ done -> done(randomizeOk) }) else null,
                            mutableOrder = if (mutable) io.github.anastaci1a.localica.library.SavedOrder(
                                listOf("a", "b"), draft.sort, draft.ascending) else null,
                            mutableOrderIds = if (mutable) listOf("a", "b") else null,
                            onRevertOrder = if (mutable) ({ _, done -> done(true) }) else null,
                            onApplySort = if (mutable) ({ _, _, done -> done(true) }) else null)
                    }
                }
            }
        } }
        val base = io.github.anastaci1a.localica.browse.BrowseStyle()
        val drafts = io.github.anastaci1a.localica.browse.ListSize.densityOrder.map { base.copy(listSize = it) } +
            (1..5).map { base.copy(presentation = io.github.anastaci1a.localica.browse.Presentation.Standard, itemsPerRow = it) } +
            io.github.anastaci1a.localica.browse.ExtraInfo.entries.map { base.copy(extraInfo = it) } +
            io.github.anastaci1a.localica.library.TrackSort.entries.map { base.copy(sort = it, ascending = false) }
        for ((w, h, font) in listOf(Triple(360.dp, 640.dp, 1f), Triple(400.dp, 240.dp, 1.5f), Triple(220.dp, 320.dp, 2f))) {
            for (caller in listOf(main, queue)) {
                var expected: IntSize? = null
                for (value in drafts) {
                    compose.runOnIdle { route = caller; draft = value; width = w; height = h; scale = font; generation++ }
                    val dialog = compose.onNodeWithTag("browse-options-dialog")
                    val size = dialog.fetchSemanticsNode().size
                    if (caller == main && font == 1f) assertTrue("Normal popup should wrap its full envelope below the window cap", size.height < h.value * densityValue)
                    if (expected == null) expected = size else assertEquals("$caller / $w / $h / $font / $value", expected, size)
                    compose.onNodeWithText("View", substring = false).performScrollTo().performClick()
                    assertEquals(expected, dialog.fetchSemanticsNode().size)
                    compose.onNodeWithText("Sort", substring = false).performScrollTo().performClick()
                    assertEquals(expected, dialog.fetchSemanticsNode().size)
                }
                if (caller == queue) {
                    compose.runOnIdle { randomizeOk = true }
                    compose.onNodeWithText("Randomize", substring = false).performScrollTo().performClick()
                    assertEquals(expected, compose.onNodeWithTag("browse-options-dialog").fetchSemanticsNode().size)
                    compose.onNodeWithText("Revert", substring = false).performScrollTo().performClick()
                    assertEquals(expected, compose.onNodeWithTag("browse-options-dialog").fetchSemanticsNode().size)
                    compose.runOnIdle { randomizeOk = false }
                    compose.onNodeWithText("Randomize", substring = false).performScrollTo().performClick()
                    compose.onNodeWithText("Couldn’t save this order. Try again.").performScrollTo().assertIsDisplayed()
                    assertEquals(expected, compose.onNodeWithTag("browse-options-dialog").fetchSemanticsNode().size)
                    compose.onNodeWithText("Close", substring = false).performScrollTo().assertIsDisplayed()
                } else compose.onNodeWithText("Save", substring = false).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun oversizedChromeScrollsToItsFooterAndNormalChromeStaysFixed() {
        var viewport by mutableStateOf(400.dp)
        lateinit var bodyScroll: androidx.compose.foundation.ScrollState
        compose.setContent { LocalicaTheme {
            bodyScroll = rememberScrollState()
            Box(Modifier.width(240.dp).height(viewport)) {
                DialogViewport(header = {
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("dialog-header")) {
                        androidx.compose.material3.Text("Options")
                    }
                }, body = {
                    Column(Modifier.fillMaxWidth().testTag("dialog-body").verticalScroll(bodyScroll)) {
                        Spacer(Modifier.height(600.dp))
                    }
                }, footer = {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(80.dp))
                        ActionTextButton({}) { androidx.compose.material3.Text("Save changes") }
                    }
                })
            }
        } }
        val header = compose.onNodeWithTag("dialog-header").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Save changes").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("dialog-body").performTouchInput { swipeUp() }
        assertEquals(header, compose.onNodeWithTag("dialog-header").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, compose.onNodeWithText("Save changes").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertTrue(bodyScroll.value > 0); viewport = 160.dp }
        compose.onNodeWithText("Save changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dialog-header").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue("Inner body remains bounded and scrollable", bodyScroll.maxValue > 0); viewport = 400.dp }
        assertEquals("Normal header returns to its original position after resize", header,
            compose.onNodeWithTag("dialog-header").fetchSemanticsNode().boundsInRoot)
        assertEquals("Normal footer returns to its original position after resize", footer,
            compose.onNodeWithText("Save changes").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun envelopeWrapsBelowCapAndInvalidatesWhenDeclaredActionsAreRemoved() {
        var includeAction by mutableStateOf(true)
        var measured = IntSize.Zero
        var expected = 0
        compose.setContent { LocalicaTheme {
            expected = with(LocalDensity.current) { (if (includeAction) 200.dp else 100.dp).roundToPx() }
            Box(Modifier.width(240.dp).height(400.dp)) {
                StablePopupBody(Modifier.onSizeChanged { measured = it }, measurement = {
                    PopupMeasurementMaximum {
                        Spacer(Modifier.height(100.dp))
                        if (includeAction) Spacer(Modifier.height(200.dp))
                    }
                }) { Spacer(Modifier.height(80.dp)) }
            }
        } }
        compose.runOnIdle { assertEquals(expected, measured.height); includeAction = false }
        compose.runOnIdle { assertEquals(expected, measured.height) }
    }

    @Test fun envelopeKeepsLargestSizeWithoutGivingShortPageArtificialScrollOrSemantics() {
        var tall by mutableStateOf(false)
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent { LocalicaTheme {
            scroll = rememberScrollState()
            Box(Modifier.width(240.dp).height(300.dp)) {
                StablePopupBody(Modifier.testTag("envelope"), measurement = {
                    PopupMeasurementMaximum {
                        Box(Modifier.height(100.dp)) { androidx.compose.material3.Text("measurement only") }
                        Spacer(Modifier.height(500.dp))
                    }
                }) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                        Spacer(Modifier.height(if (tall) 500.dp else 100.dp))
                        androidx.compose.material3.Text("current page")
                    }
                }
            }
        } }
        val first = compose.onNodeWithTag("envelope").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("measurement only").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, scroll.maxValue); tall = true }
        compose.runOnIdle { assertTrue(scroll.maxValue > 0) }
        assertEquals(first, compose.onNodeWithTag("envelope").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { tall = false }
        compose.runOnIdle { assertEquals(0, scroll.maxValue) }
        assertEquals(first, compose.onNodeWithTag("envelope").fetchSemanticsNode().boundsInRoot)
    }
}
