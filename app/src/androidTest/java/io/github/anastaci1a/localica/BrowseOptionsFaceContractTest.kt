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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest

/** Native text shaping, including the 220dp host minus 48dp chrome and 24dp body inset. */
private data class BrowseFaceProbe(val title: String, val summary: String, val live: @Composable () -> Unit)

/** Same placed-node geometry and node density as Compose 1.8.2 BoundsAssertions. */
private fun SemanticsNode.faceBounds(): DpRect = with(layoutInfo.density) {
    assertTrue("A measured settings face must be placed", layoutInfo.isPlaced)
    val position = positionInRoot
    DpRect(position.x.toDp(), position.y.toDp(),
        (position.x + size.width).toDp(), (position.y + size.height).toDp())
}

/** Optional paired-run evidence, never a substitute for the original assertions. */
private class BrowseFaceTrace {
    private val values = StringBuilder()
    private var count = 0
    fun add(vararg fields: Any?) {
        fields.forEach { field ->
            val value = field.toString()
            values.append(value.length).append(':').append(value)
            count++
        }
    }
    fun publish(scale: Float, width: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        InstrumentationRegistry.getInstrumentation().addResults(android.os.Bundle().apply {
            putString("browse_face_trace_${scale}_$width", "$scale/$width/$count/$digest")
        })
    }
}

class BrowseOptionsFaceContractTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everySelectableAndLegacySummaryUsesTheMeasuredFaceWithoutClippedText() {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("browseFaceMode", "cached")
        require(mode == "original" || mode == "cached")
        android.util.Log.i("BrowseFaceFixture", "mode=" + mode + ",explicit=" + arguments.containsKey("browseFaceMode"))
        val recordTrace = arguments.getString("browseFaceTrace", "false").also {
            require(it == "true" || it == "false")
        } == "true"
        val declaration = browseOptionsDeclaration(BrowseRoute(MainTab.Library))
        val scoped = browseOptionsDeclaration(BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "fixture")), mutable = true)
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
        val fallbackProbes = listOf(
            BrowseFaceProbe("Sort by", sortLabel(io.github.anastaci1a.localica.library.TrackSort.Count)) {
                ChoicePreference("Sort by", io.github.anastaci1a.localica.library.TrackSort.Count,
                    scoped.sortFields, ::sortLabel, popup = true) {}
            },
            BrowseFaceProbe("Extra information", extraInfoLabel(io.github.anastaci1a.localica.browse.ExtraInfo.Inherited)) {
                ChoicePreference("Extra information", io.github.anastaci1a.localica.browse.ExtraInfo.Inherited,
                    scoped.information, ::extraInfoLabel, popup = true) {}
            })
        val probes = summaries + fallbackProbes
        var width by mutableStateOf(148.dp)
        var font by mutableFloatStateOf(1f)
        val sizes = mutableMapOf<String, IntSize>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(2.625f, font), LocalReducedMotion provides true) {
                Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                    probes.forEachIndexed { index, probe ->
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
            // This phase changes no UI after width/font publication. Use the same
            // public matchers and both tree policies as the original per-query path.
            val all = SemanticsMatcher("Every settings-face node") { true }
            val merged = if (mode == "cached") compose.onAllNodes(all).fetchSemanticsNodes() else null
            val unmerged = if (mode == "cached") compose.onAllNodes(all, useUnmergedTree = true).fetchSemanticsNodes() else null
            val trace = if (recordTrace) BrowseFaceTrace() else null
            probes.forEachIndexed { index, probe ->
                val summary = probe.summary
                compose.runOnIdle { assertEquals("$summary / $scale / $w", sizes["face-$index"], sizes["live-$index"]); assertNotNull(sizes["live-$index"]) }
                trace?.add(index, summary, sizes["face-$index"], sizes["live-$index"])
                for (prefix in listOf("live", "face")) {
                    val matcher = hasText(summary) and hasAnyAncestor(hasTestTag("$prefix-$index"))
                    val text = compose.onAllNodes(matcher, useUnmergedTree = true)
                    // A summary may equal its title; check every matching text node.
                    val parent = merged?.single { hasTestTag("$prefix-$index").matches(it) }?.faceBounds()
                        ?: compose.onNodeWithTag("$prefix-$index").getUnclippedBoundsInRoot()
                    val nodes = unmerged?.filter(matcher::matches) ?: text.fetchSemanticsNodes()
                    trace?.add(prefix, parent, nodes.size)
                    for ((nodeIndex, node) in nodes.withIndex()) {
                        val bounds = if (unmerged != null) node.faceBounds() else text[nodeIndex].getUnclippedBoundsInRoot()
                        assertTrue("Text left containment: $summary", bounds.left >= parent.left)
                        assertTrue("Text right containment: $summary", bounds.right <= parent.right)
                        assertTrue("Text top containment: $summary", bounds.top >= parent.top)
                        assertTrue("Text bottom containment: $summary", bounds.bottom <= parent.bottom)
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                        assertTrue(layouts.isNotEmpty())
                        trace?.add(nodeIndex, bounds, node.size, layouts.size)
                        layouts.forEach { result ->
                            val context = "$summary / $prefix / $scale / $w: node=${node.size} " +
                                "layout=${result.size} paragraphWidth=${result.multiParagraph.width}"
                            assertEquals("Use the final measured text node: $context", node.size, result.size)
                            assertFalse("No omitted text lines: $context", result.didOverflowHeight)
                            assertTrue("Nonempty native text layout: $context", result.lineCount > 0)
                            trace?.add(result.size, result.didOverflowHeight, result.lineCount, result.layoutInput.text.text)
                            // A cached paragraph may retain a wider allocation than the final
                            // node (observed 210px paragraph / 124px line and glyphs). Its
                            // didOverflowWidth alone does not establish visible clipping.
                            // Check final line/visible-character geometry instead, without
                            // changing row containment, width, font or truncation rules.
                            for (line in 0 until result.lineCount) {
                                assertFalse("No ellipsis: $context", result.isLineEllipsized(line))
                                assertTrue("Line left fits: $context", result.getLineLeft(line) >= 0f)
                                assertTrue("Line right fits: $context", result.getLineRight(line) <= node.size.width)
                                assertTrue("Line top fits: $context", result.getLineTop(line) >= 0f)
                                assertTrue("Line bottom fits: $context", result.getLineBottom(line) <= node.size.height)
                                trace?.add(line, result.isLineEllipsized(line), result.getLineLeft(line),
                                    result.getLineRight(line), result.getLineTop(line), result.getLineBottom(line))
                            }
                            result.layoutInput.text.forEachIndexed { offset, character ->
                                // Trailing wrap whitespace has no visible ink.
                                if (!character.isWhitespace()) {
                                    val glyph = result.getBoundingBox(offset)
                                    assertTrue("Glyph left fits: $context / $offset / $glyph", glyph.left >= 0f)
                                    assertTrue("Glyph right fits: $context / $offset / $glyph", glyph.right <= node.size.width)
                                    assertTrue("Glyph top fits: $context / $offset / $glyph", glyph.top >= 0f)
                                    assertTrue("Glyph bottom fits: $context / $offset / $glyph", glyph.bottom <= node.size.height)
                                    trace?.add(offset, glyph)
                                }
                            }
                        }
                    }
                    val finalNodes = if (unmerged != null) nodes else text.fetchSemanticsNodes()
                    assertTrue(finalNodes.isNotEmpty())
                    trace?.add(finalNodes.size)
                }
            }
            trace?.publish(scale, w.toString())
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
