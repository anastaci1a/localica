package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.LocalReducedMotion
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.layout.menu.*
import io.github.anastaci1a.localica.ui.specimenPreviews
import io.github.anastaci1a.localica.ui.menu.MenuPageHost
import io.github.anastaci1a.localica.ui.pages.Fixture
import io.github.anastaci1a.localica.ui.pages.SpecimenSource
import io.github.anastaci1a.localica.ui.pages.SpecimenState
import io.github.anastaci1a.localica.ui.spec.MenuGap
import io.github.anastaci1a.localica.ui.spec.MenuSize
import io.github.anastaci1a.localica.ui.spec.MenuSpecResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * #757 M0: the device invariants I1 to I9 (DESIGN 8.2), checked on every specimen fixture at two font scales.
 * Each test collects every violation before failing, so one run reports the whole state of the specimen.
 * I7 here is the geometric half; the per-page pixel probe arrives with the first migrated page (M1).
 */
class MenuInvariantTest {
    @get:Rule val compose = createComposeRule()

    private val fixtures: List<Fixture<SpecimenState>> = SpecimenSource.fixtures
    private val scales = listOf(1f, 1.3f)
    private var shown by mutableStateOf(fixtures.first())
    private var scale by mutableFloatStateOf(1f)
    private lateinit var density: Density
    private val spec = (menuSpecBase() as MenuSpecResult.Accepted).spec

    private class Part(val part: MenuPart, val rect: Rect, val node: SemanticsNode)
    private class Label(val text: String, val flow: MenuTextFlow, val layout: TextLayoutResult)

    private fun px(dp: Dp) = with(density) { dp.toPx() }
    private fun near(a: Float, b: Float) = abs(a - b) <= 1f
    private fun Rect.inside(outer: Rect) = left >= outer.left - .5f && top >= outer.top - .5f && right <= outer.right + .5f && bottom <= outer.bottom + .5f

    private fun show() {
        compose.setContent { LocalicaTheme {
            val device = LocalDensity.current
            density = Density(device.density, scale)
            CompositionLocalProvider(LocalDensity provides Density(device.density, scale), LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).fillMaxHeight()) { key(shown, scale) { MenuPageHost(SpecimenSource.declare(shown.state), previews = specimenPreviews, strict = false) } }
            }
        } }
    }

    private fun parts(): List<Part> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(MenuPartKey), useUnmergedTree = true).fetchSemanticsNodes()
        .map { Part(it.config[MenuPartKey], Rect(it.positionInRoot, it.size.toSize()), it) }

    private fun labels(): List<Label> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(MenuTextFlowKey), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        results.firstOrNull()?.let { Label(it.layoutInput.text.text, node.config[MenuTextFlowKey], it) }
    }

    /** Runs [check] on every fixture and scale and fails once with every violation it reported. */
    private fun everywhere(check: (name: String, report: (String) -> Unit) -> Unit) {
        show()
        // The first composition lays text out before fonts and icons settle: settle once before anything is measured.
        scale = scales.first(); shown = fixtures[1]; compose.waitForIdle()
        val failures = mutableListOf<String>()
        for (factor in scales) for (fixture in fixtures) {
            scale = factor; shown = fixture
            compose.waitForIdle()
            val name = "${fixture.name}@$factor"
            assertTrue("$name is admitted", AcceptedPage.admit(SpecimenSource.declare(fixture.state)) is Admission.Accepted)
            assertTrue("$name drew rows", parts().any { it.part == MenuPart.Row })
            check(name) { failures += "$name: $it" }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    private fun starts(parts: List<Part>) = parts.map { Math.round(it.rect.left) }.distinct().sorted()

    @Test fun i1_textAndHeadingsShareTheirStart() = everywhere { _, report ->
        val parts = parts()
        val root = compose.onRoot().fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        val text = parts.filter { it.part == MenuPart.Title || it.part == MenuPart.Summary || it.part == MenuPart.Helper }
        if (starts(text).size > 1) report("I1 row text starts differ: ${starts(text)} px")
        val headings = parts.filter { it.part == MenuPart.Heading }
        if (headings.any { !near(it.rect.left - root.left, px(spec.dp(MenuSize.PageInset))) }) report("I1 heading starts ${starts(headings)} px, page inset ${px(spec.dp(MenuSize.PageInset))} px")
    }

    @Test fun i2_leadingAdornmentsShareOneCentre() = everywhere { _, report ->
        val centres = parts().filter { it.part == MenuPart.Leading }.map { Math.round(it.rect.center.x) }.distinct()
        if (centres.size > 1) report("I2 leading centres differ: $centres px")
    }

    @Test fun i3_gapsFollowTheAdjacencyTable() = everywhere { _, report ->
        val all = parts()
        val flat = flatten((AcceptedPage.admit(SpecimenSource.declare(shown.state)) as Admission.Accepted).page.content.panel(), afterTabs = true)
        val rows = all.filter { it.part == MenuPart.Row }
        // Top-level stream items: rows that are not inside another row, headings and captions, in visual order.
        val drawn = all.filter { part -> part.part == MenuPart.Heading || part.part == MenuPart.Caption ||
            (part.part == MenuPart.Row && rows.none { it !== part && it.rect != part.rect && part.rect.inside(it.rect) }) }.sortedBy { it.rect.top }
        // A legend is one declared item drawn as its bar plus one row per entry: group those rows back into the item.
        val items = mutableListOf<Part>()
        var cursor = 0
        for (index in 0 until flat.count) {
            if (cursor >= drawn.size) break
            val take = ((flat.item(index).row as? LegendContent)?.entries?.size ?: 1).coerceAtMost(drawn.size - cursor)
            val group = drawn.subList(cursor, cursor + take)
            items += Part(group.first().part, Rect(group.minOf { it.rect.left }, group.minOf { it.rect.top }, group.maxOf { it.rect.right }, group.maxOf { it.rect.bottom }), group.first().node)
            cursor += take
        }
        if (cursor < drawn.size) report("I3 drew ${drawn.size} items for ${flat.count} declared")
        val strip = all.filter { it.part == MenuPart.Tab }.maxOfOrNull { it.rect.bottom }
        for (index in 0 until items.size) {
            val above = if (index == 0) strip ?: continue else items[index - 1].rect.bottom
            // The legend bar is not a part: it sits above the first entry, one row gap above it.
            val bar = (flat.item(index).row as? LegendContent)?.takeIf { it.bar }?.let { px(spec.dp(MenuSize.Marker)) + px(spec.dp(MenuGap.RowToRow)) } ?: 0f
            val expected = px(spec.dp(flat.gapBefore(index))) + bar
            if (!near(items[index].rect.top - above, expected)) report("I3 gap before item $index (${flat.item(index).key}) is ${items[index].rect.top - above} px, ${flat.gapBefore(index)} is $expected px; drawn ${items[index].part} ${items[index].rect}, above ${if (index == 0) "the tab strip" else "${items[index - 1].part} ${items[index - 1].rect}"}; stream ${items.take(5).map { "${it.part} ${it.rect}" }}, strip ${all.filter { it.part == MenuPart.Tab }.map { it.rect }}")
        }
    }

    private fun breaksInsideAWord(label: Label): Boolean = (0 until label.layout.lineCount - 1).any { line ->
        val end = label.layout.getLineEnd(line)
        end in 1 until label.text.length && label.text[end - 1].isLetterOrDigit() && label.text[end].isLetterOrDigit()
    }

    /** Text that is cut by its line limit or runs past its width. (hasVisualOverflow also reports text narrower than its constraint.) */
    private fun overflows(label: Label): Boolean = label.layout.let { layout ->
        val last = layout.lineCount - 1
        layout.lineCount > layout.layoutInput.maxLines || layout.getLineEnd(last) < label.text.length ||
            (0..last).any { layout.getLineRight(it) > layout.layoutInput.constraints.maxWidth + 1f }
    }

    private fun describe(label: Label) = "'${label.text}' lines=${label.layout.lineCount} size=${label.layout.size} constraints=${label.layout.layoutInput.constraints} exceedsMaxLines=${(0 until label.layout.lineCount).lastOrNull()?.let { label.layout.isLineEllipsized(it) }} maxLines=${label.layout.layoutInput.maxLines}"

    /** I4 for system labels: no overflow and no break inside a word. A failure is a copy or layout defect. */
    @Test fun i4_staticLabelsNeverOverflowOrBreakInsideAWord() = everywhere { _, report ->
        for (label in labels().filter { it.flow == MenuTextFlow.Static }) {
            if (overflows(label)) report("I4 static label overflows: ${describe(label)}")
            if (breaksInsideAWord(label)) report("I4 static label breaks inside a word: '${label.text}'")
        }
    }

    /**
     * I4 for user and file text, asserted separately: it may use the declared grapheme fallback (a break inside an
     * unbreakable word), but it never overflows and never splits a surrogate pair. Value text stays on one line.
     */
    @Test fun i4_dynamicTextUsesOnlyTheDeclaredFallback() = everywhere { name, report ->
        // The Data tab streams its records lazily: bring the last items (the records) into composition before reading labels.
        if (name.startsWith("Data")) runCatching {
            val last = flatten((AcceptedPage.admit(SpecimenSource.declare(shown.state)) as Admission.Accepted).page.content.panel(), afterTabs = true).count - 1
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(last)
            compose.waitForIdle()
        }
        val labels = labels()
        for (label in labels.filter { it.flow == MenuTextFlow.Dynamic }) {
            if (overflows(label)) report("I4 dynamic text overflows: ${describe(label)}")
            for (line in 0 until label.layout.lineCount - 1) {
                val end = label.layout.getLineEnd(line)
                if (end in 1 until label.text.length && label.text[end - 1].isHighSurrogate()) report("I4 dynamic text splits a surrogate pair: '${label.text}'")
            }
        }
        for (label in labels.filter { it.flow == MenuTextFlow.Value }) {
            if (label.layout.lineCount != 1 || overflows(label)) report("I4 value text is not one whole line: '${label.text}'")
        }
        // The long fixtures must actually exercise the fallback, or this test proves nothing about it.
        if (name.startsWith("Data-long")) assertTrue("$name exercises the fallback", labels.any { it.flow == MenuTextFlow.Dynamic && breaksInsideAWord(it) })
    }

    @Test fun i5_interactivePartsAreAtLeast48Dp() = everywhere { _, report ->
        val minimum = px(48.dp) - .5f
        val root = compose.onRoot().fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        val interactive = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        if (interactive.isEmpty()) report("I5 found no interactive part")
        for (node in interactive) {
            // touchBoundsInRoot is clipped to the viewport, so a part cut by the screen edge reports a short or empty box. The layout
            // size is not clipped; the expanded touch box counts only for a part fully on screen. A cut-off part smaller than the
            // minimum cannot be judged here and is left to the scrolled pixel pass at M1.
            val layout = Rect(node.positionInRoot, node.size.toSize())
            val onScreen = layout.inside(root)
            val width = if (onScreen) maxOf(layout.width, node.touchBoundsInRoot.width) else layout.width
            val height = if (onScreen) maxOf(layout.height, node.touchBoundsInRoot.height) else layout.height
            if (!onScreen && (width < minimum || height < minimum)) continue
            if (width < minimum || height < minimum) report("I5 ${node.config.getOrNull(SemanticsProperties.Text) ?: node.config.getOrNull(SemanticsProperties.ContentDescription)} is ${width}x${height} px")
        }
    }

    @Test fun i6_trailingEdgesAlignAndGlyphsAreCentred() = everywhere { _, report ->
        val all = parts()
        val trailing = all.filter { it.part == MenuPart.Trailing }
        val edges = trailing.map { Math.round(it.rect.right) }.distinct()
        if (edges.size > 1) report("I6 trailing edges differ: $edges px")
        for (part in trailing) {
            val row = all.filter { it.part == MenuPart.Row && part.rect.inside(it.rect) }.minByOrNull { it.rect.height } ?: continue
            if (!near(part.rect.center.y, row.rect.center.y)) report("I6 trailing part is ${part.rect.center.y - row.rect.center.y} px off its row centre")
        }
    }

    @Test fun i7_fullyVisibleRowsKeepTheirShadowInsideTheViewport() {
        var drawn = 0
        everywhere { _, report ->
        val root = compose.onRoot().fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        val outset = px(spec.dp(MenuSize.ShadowOutset))
        // Rows cut by the viewport edge are excluded by rule: only a row whose own bounds are fully inside is checked.
        val surfaces = parts().filter { it.part == MenuPart.Surface }
        drawn += surfaces.size
        for (part in surfaces.filter { it.rect.inside(root) }) {
            if (part.rect.left - outset < root.left - .5f || part.rect.right + outset > root.right + .5f) report("I7 surface at ${part.rect} cannot hold a $outset px outset in $root")
        }
        }
        assertTrue("I7 found no glass surface on any fixture", drawn > 0)
    }

    @Test fun i8_tabsExposeRoleAndOneSelection() = everywhere { _, report ->
        val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        if (tabs.isEmpty()) report("I8 no node has the tab role")
        else if (tabs.count { it.config.getOrNull(SemanticsProperties.Selected) == true } != 1) report("I8 ${tabs.count { it.config.getOrNull(SemanticsProperties.Selected) == true }} tabs are selected")
    }

    @Test fun i9_loadingAndReadyFacesHaveEqualBounds() {
        show()
        val failures = mutableListOf<String>()
        var detail = emptyMap<Rect, String>()
        fun rows(name: String): List<Rect> {
            shown = fixtures.single { it.name == name }
            compose.waitForIdle()
            val all = parts()
            val rows = all.filter { it.part == MenuPart.Row }.map { it.rect }.sortedWith(compareBy({ it.top }, { it.left }))
            detail = rows.associateWith { row -> all.filter { it.part != MenuPart.Row && it.rect.inside(row) }.joinToString { "${it.part}@${it.rect.top - row.top},${it.rect.height}" } }
            return rows
        }
        for (factor in scales) for (ready in fixtures.filter { it.name.endsWith("-ready") }) {
            scale = factor
            val a = rows(ready.name); val readyDetail = detail
            val b = rows(ready.name.removeSuffix("-ready") + "-loading")
            if (a.isEmpty()) failures += "${ready.name}@$factor drew no rows"
            if (a != b) failures += "I9 ${ready.name}@$factor: ${a.size} ready and ${b.size} loading rows differ at ${a.indices.filter { a[it] != b.getOrNull(it) }.take(6).map { "#$it ready ${a[it]} [${readyDetail[a[it]]}] loading ${b.getOrNull(it)} [${detail[b.getOrNull(it)]}]" }}"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
