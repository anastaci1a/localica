package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native text shaping, including the 220dp host minus 48dp chrome and 24dp body inset. */
private data class BrowseFaceProbe(val title: String, val summary: String, val live: @Composable () -> Unit)

class BrowseOptionsFaceContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everySelectableAndLegacySummaryUsesTheMeasuredFaceWithoutClippedText() {
        val declaration = browseOptionsDeclaration(BrowseRoute(MainTab.Library))
        val summaries = declaration.sortSummaries.map { value ->
            BrowseFaceProbe("Sort by", sortLabel(value)) {
                ChoicePreference("Sort by", value, declaration.sortFields, ::sortLabel, popup = true) {}
            }
        } + declaration.sizeSummaries.map { value ->
            BrowseFaceProbe("Size", value.name) {
                ChoicePreference("Size", value, declaration.sizes, { it.name }, popup = true) {}
            }
        } + declaration.informationSummaries.map { value ->
            BrowseFaceProbe("Extra information", extraInfoLabel(value)) {
                ChoicePreference("Extra information", value, declaration.information, ::extraInfoLabel, popup = true) {}
            }
        }
        var width by mutableStateOf(148.dp)
        var font by mutableFloatStateOf(1f)
        val sizes = mutableMapOf<String, IntSize>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(2.625f, font), LocalReducedMotion provides true) {
                Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                    summaries.forEachIndexed { index, probe ->
                        Box(Modifier.fillMaxWidth().testTag("live-$index").onSizeChanged { sizes["live-$index"] = it }) {
                            probe.live()
                        }
                        Box(Modifier.fillMaxWidth().testTag("face-$index").onSizeChanged { sizes["face-$index"] = it }) {
                            SettingsLinkFace(probe.title, probe.summary, null)
                        }
                    }
                }
            }
        } }
        for (scale in listOf(1f, 1.5f, 2f)) for (w in listOf(148.dp, 280.dp)) {
            compose.runOnIdle { width = w; font = scale }
            summaries.forEachIndexed { index, probe ->
                val summary = probe.summary
                compose.runOnIdle { assertEquals("$summary / $scale / $w", sizes["face-$index"], sizes["live-$index"]); assertNotNull(sizes["live-$index"]) }
                for (prefix in listOf("live", "face")) {
                    val text = compose.onAllNodes(hasText(summary) and hasAnyAncestor(hasTestTag("$prefix-$index")), useUnmergedTree = true)
                    // A summary may equal its title; check every matching text node.
                    val parent = compose.onNodeWithTag("$prefix-$index").getUnclippedBoundsInRoot()
                    for ((nodeIndex, node) in text.fetchSemanticsNodes().withIndex()) {
                        val bounds = text[nodeIndex].getUnclippedBoundsInRoot()
                        assertTrue("Text left containment: $summary", bounds.left >= parent.left)
                        assertTrue("Text right containment: $summary", bounds.right <= parent.right)
                        assertTrue("Text top containment: $summary", bounds.top >= parent.top)
                        assertTrue("Text bottom containment: $summary", bounds.bottom <= parent.bottom)
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                        assertTrue(layouts.isNotEmpty())
                        layouts.forEach { assertFalse("Clipped $summary / $scale / $w", it.hasVisualOverflow) }
                    }
                    assertTrue(text.fetchSemanticsNodes().isNotEmpty())
                }
            }
        }
    }

    @Test fun everyGalleryCaptionKeepsItsLiveSliderEnvelopeAndNativeEndpoints() {
        val titles = browseOptionsDeclaration(BrowseRoute(MainTab.Library)).sliderTitles
        var font by mutableFloatStateOf(1f)
        var width by mutableStateOf(148.dp)
        val sizes = mutableMapOf<String, IntSize>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(2.625f, font), LocalReducedMotion provides true) {
                Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                    titles.forEachIndexed { index, title ->
                        Box(Modifier.fillMaxWidth().onSizeChanged { sizes["live-$index"] = it }) {
                            SliderPreference(title, (index + 1).toFloat(), {}, valueRange = 1f..5f, steps = 3)
                        }
                        Box(Modifier.fillMaxWidth().onSizeChanged { sizes["face-$index"] = it }) {
                            SliderPreferenceFrame(title, null, null, null) {
                                Spacer(Modifier.fillMaxWidth().height(InterfaceSliderHeight))
                            }
                        }
                    }
                }
            }
        } }
        for (scale in listOf(1f, 1.5f, 2f)) for (w in listOf(148.dp, 280.dp)) {
            compose.runOnIdle { width = w; font = scale }
            compose.runOnIdle { titles.indices.forEach { index ->
                assertNotNull(sizes["live-$index"])
                assertEquals("Caption "+titles[index]+" / $scale / $w", sizes["face-$index"], sizes["live-$index"])
            } }
            compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(1f, 1f..5f, 3))).assertCountEquals(1)
            compose.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(5f, 1f..5f, 3))).assertCountEquals(1)
        }
    }
}
