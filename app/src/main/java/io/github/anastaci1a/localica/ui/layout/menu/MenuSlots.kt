package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.max

/**
 * #757 geometry slots: the only code that writes padding, sizes or text roles for menus. Every value comes from the
 * resolved metrics; no entry point takes a size, padding or style. Controls arrive as lambdas from the binder.
 */
enum class MenuRowSize { Link, Control }

/** What a segmented control needs from the system: cell height from the measured line, no outer margin, and the label style. */
@Immutable class SegmentMetrics internal constructor(val cell: Dp, val margin: Dp, val style: TextStyle)
@Composable private fun segmentMetrics(role: MenuTextRole) = LocalMenuEnvironment.current.metrics.let { metrics ->
    val style = menuTextStyle(role)
    remember(metrics, style) { SegmentMetrics(metrics.segmentCell(role), Dp(0f), style) }
}

@Composable private fun textStart(): Dp {
    val env = LocalMenuEnvironment.current
    return env.metrics.dp(MenuSize.RowInset) + if (env.reservesLeading) env.metrics.dp(MenuSize.LeadingBox) + env.metrics.dp(MenuSize.LeadingGap) else Dp(0f)
}

/** Draws the surface of a glass row or card; the slot supplies the corner so the binder holds no size. */
@Composable fun MenuSurfaceSlot(surface: @Composable (corner: Dp, content: @Composable () -> Unit) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.menuPart(MenuPart.Surface)) { surface(LocalMenuEnvironment.current.metrics.dp(MenuSize.RowCorner), content) }
}

/** The shared leading slot: an icon, a marker dot or empty, always the same box and the same gap to the text. */
@Composable private fun LeadingSlot(marker: MarkerTone?, icon: (@Composable (Modifier) -> Unit)?, height: Dp?) {
    val metrics = LocalMenuEnvironment.current.metrics
    val box = metrics.dp(MenuSize.LeadingBox)
    Box(Modifier.width(box + metrics.dp(MenuSize.LeadingGap)).then(if (height != null) Modifier.height(height) else Modifier), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(box).menuPart(MenuPart.Leading), contentAlignment = Alignment.Center) {
            if (marker != null) Box(Modifier.size(metrics.dp(MenuSize.Marker)).background(Color(metrics.spec.color(marker).argb), CircleShape))
            else icon?.invoke(Modifier.size(box))
        }
    }
}

/** Title with an optional value: the value sits at the end of the title line, or moves as a whole to its own line. */
@Composable private fun TitleLine(title: String, value: Value<String>?, flow: MenuTextFlow, emphasis: MenuEmphasis, modifier: Modifier = Modifier) {
    if (value == null) { MenuText(MenuTextRole.RowTitle, title, modifier.menuPart(MenuPart.Title), flow, emphasis); return }
    val gap = LocalMenuEnvironment.current.metrics.dp(MenuSize.ControlGap)
    Layout(modifier = modifier, content = {
        MenuText(MenuTextRole.RowTitle, title, Modifier.menuPart(MenuPart.Title), flow, emphasis)
        MenuValueText(MenuTextRole.RowValue, value, Modifier.menuPart(MenuPart.Value))
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val valueWidth = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val sameLine = !constraints.hasBoundedWidth || measurables[0].maxIntrinsicWidth(Constraints.Infinity) + gap.roundToPx() + valueWidth <= constraints.maxWidth
        val valuePlaceable = measurables[1].measure(loose.copy(maxWidth = Constraints.Infinity))
        val titlePlaceable = measurables[0].measure(if (sameLine && constraints.hasBoundedWidth)
            loose.copy(maxWidth = (constraints.maxWidth - gap.roundToPx() - valuePlaceable.width).coerceAtLeast(0)) else loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else titlePlaceable.width + gap.roundToPx() + valuePlaceable.width
        if (sameLine) {
            val height = max(titlePlaceable.height, valuePlaceable.height)
            layout(width, height) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else layout(width, titlePlaceable.height + valuePlaceable.height) {
            titlePlaceable.placeRelative(0, 0)
            valuePlaceable.placeRelative(0, titlePlaceable.height)
        }
    }
}

/**
 * The one row frame, read by the live row, its inert measurement face and its retained popup face. [modifier]
 * carries behaviour (activation, semantics) only. [glyph] is a chevron-sized trailing icon; [control] a live control.
 */
@Composable fun MenuRowSlot(size: MenuRowSize, title: String, modifier: Modifier = Modifier, summary: Value<String>? = null, helper: String? = null,
    value: Value<String>? = null, enabled: Boolean = true, flow: MenuTextFlow = MenuTextFlow.Static, marker: MarkerTone? = null,
    icon: (@Composable (Modifier) -> Unit)? = null, glyph: (@Composable (Modifier) -> Unit)? = null, control: (@Composable () -> Unit)? = null) {
    val env = LocalMenuEnvironment.current
    val metrics = env.metrics
    val minimum = metrics.dp(if (env.compact) MenuSize.CompactRow else if (size == MenuRowSize.Link) MenuSize.LinkRow else MenuSize.ControlRow)
    val emphasis = if (enabled) MenuEmphasis.Normal else MenuEmphasis.Disabled
    Row(modifier.fillMaxWidth().heightIn(min = minimum).menuPart(MenuPart.Row)
        .padding(horizontal = metrics.dp(MenuSize.RowInset), vertical = metrics.dp(if (size == MenuRowSize.Link) MenuSize.LinkRowPadding else MenuSize.ControlRowPadding)),
        verticalAlignment = Alignment.CenterVertically) {
        if (marker == null && (icon != null || env.reservesLeading)) LeadingSlot(null, icon, null)
        // A marker is centred on the first line of the title, like a bullet, so it sits inside the text group.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            if (marker != null) LeadingSlot(marker, null, metrics.line(MenuTextRole.RowTitle))
            Column(Modifier.weight(1f)) {
                TitleLine(title, value, flow, emphasis, Modifier.fillMaxWidth())
                if (summary != null) MenuValueText(MenuTextRole.RowSummary, summary, Modifier.menuPart(MenuPart.Summary), MenuTextFlow.Static)
                if (helper != null) MenuText(MenuTextRole.Helper, helper, Modifier.padding(top = metrics.dp(MenuSize.HelperGap)).menuPart(MenuPart.Helper))
            }
        }
        if (glyph != null) Box(Modifier.padding(start = metrics.dp(MenuSize.ChevronGap)).menuPart(MenuPart.Trailing)) { glyph(Modifier.size(metrics.dp(MenuSize.Chevron))) }
        if (control != null) Box(Modifier.padding(start = metrics.dp(MenuSize.ControlGap)).menuPart(MenuPart.Trailing)) { control() }
    }
}

/** Headings start at the page inset. */
@Composable fun MenuHeadingSlot(text: String) { MenuText(MenuTextRole.SectionHeading, text, Modifier.fillMaxWidth().menuPart(MenuPart.Heading)) }

/** A section caption starts where row text starts. */
@Composable fun MenuCaptionSlot(text: String) { MenuText(MenuTextRole.Helper, text, Modifier.fillMaxWidth().padding(start = textStart()).menuPart(MenuPart.Caption)) }

/** The system-owned state of a rejected page or token join. Nothing of the rejected declaration is shown. */
@Composable fun MenuFailureSlot() {
    val metrics = LocalMenuEnvironment.current.metrics
    MenuText(MenuTextRole.NoticeText, "This page could not be shown.", Modifier.fillMaxWidth().padding(metrics.dp(MenuSize.PageInset)), tone = NoticeTone.Error)
}

private fun requiredSegmentWidth(labels: List<String>, widths: List<Int>, inset: Int, lens: Int): Int = labels.size * ((widths.maxOrNull() ?: 0) + inset * 2) + lens * 2

@Composable private fun segmentsWidth(labels: List<String>, role: MenuTextRole): Dp {
    val metrics = LocalMenuEnvironment.current.metrics
    val measurer = rememberTextMeasurer()
    val style = menuTextStyle(role)
    return remember(labels, style, metrics) { with(metrics.density) {
        requiredSegmentWidth(labels, labels.map { measurer.measure(AnnotatedString(it), style, softWrap = false, maxLines = 1).size.width },
            metrics.dp(MenuSize.SegmentLabelInset).roundToPx(), metrics.dp(MenuSize.SegmentInset).roundToPx()).toDp() } }
}

/**
 * The tab strip. Equal cells when every label fits on one line; otherwise cells keep the width the widest label
 * needs and the strip scrolls sideways. A tab label is never split or ellipsized.
 */
@Composable fun MenuTabStripSlot(labels: List<String>, fade: @Composable (ScrollState) -> Modifier, strip: @Composable (SegmentMetrics) -> Unit) {
    val env = LocalMenuEnvironment.current
    val required = segmentsWidth(labels, MenuTextRole.TabLabel)
    val segments = segmentMetrics(MenuTextRole.TabLabel)
    val inset = env.metrics.dp(MenuSize.PageInset)
    if (required <= env.contentWidth) Box(Modifier.fillMaxWidth().padding(start = inset, end = inset, top = env.metrics.dp(MenuGap.PageTop)).menuPart(MenuPart.Tab)) { strip(segments) }
    else {
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().padding(top = env.metrics.dp(MenuGap.PageTop)).then(fade(scroll)).horizontalScroll(scroll).padding(horizontal = inset)) {
            Box(Modifier.width(required).menuPart(MenuPart.Tab)) { strip(segments) }
        }
    }
}

/**
 * The choice row. Inline segmented control when every label fits on one line at the current font scale and width,
 * otherwise [box] (the choice box that opens the popup). The decision is measured once, here.
 */
@Composable fun MenuChoiceSlot(title: String, labels: List<String>, helper: String?, enabled: Boolean,
    inline: @Composable (SegmentMetrics) -> Unit, box: @Composable () -> Unit) {
    val env = LocalMenuEnvironment.current
    val metrics = env.metrics
    if (!enabled || segmentsWidth(labels, MenuTextRole.ControlLabel) > env.contentWidth) { box(); return }
    Column(Modifier.fillMaxWidth().menuPart(MenuPart.Row).padding(vertical = metrics.dp(MenuSize.ControlRowPadding))) {
        MenuText(MenuTextRole.RowTitle, title, Modifier.padding(start = textStart(), end = metrics.dp(MenuSize.RowInset)).menuPart(MenuPart.Title))
        Box(Modifier.fillMaxWidth().menuPart(MenuPart.Segment)) { inline(segmentMetrics(MenuTextRole.ControlLabel)) }
        if (helper != null) MenuText(MenuTextRole.Helper, helper, Modifier.padding(start = textStart(), top = metrics.dp(MenuSize.HelperGap)).menuPart(MenuPart.Helper))
    }
}

/** Title with the value at the end of its line, the track, optional end labels and helper. */
@Composable fun MenuSliderSlot(title: String, valueLabel: String, low: String?, high: String?, helper: String?, enabled: Boolean,
    track: @Composable (Modifier) -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    val inset = metrics.dp(MenuSize.RowInset)
    Column(Modifier.fillMaxWidth().menuPart(MenuPart.Row).padding(vertical = metrics.dp(MenuSize.ControlRowPadding))) {
        TitleLine(title, Value.Ready(valueLabel), MenuTextFlow.Static, if (enabled) MenuEmphasis.Normal else MenuEmphasis.Disabled,
            Modifier.fillMaxWidth().padding(start = textStart(), end = inset))
        track(Modifier.fillMaxWidth().height(metrics.dp(MenuSize.Slider)))
        if (low != null && high != null) Row(Modifier.fillMaxWidth().padding(horizontal = inset), horizontalArrangement = Arrangement.SpaceBetween) {
            MenuText(MenuTextRole.Helper, low)
            MenuText(MenuTextRole.Helper, high)
        }
        if (helper != null) MenuText(MenuTextRole.Helper, helper, Modifier.padding(start = textStart(), top = metrics.dp(MenuSize.HelperGap)).menuPart(MenuPart.Helper))
    }
}

/** A hug-width action, left-aligned at the row inset, with the full touch height. */
@Composable fun MenuActionSlot(content: @Composable () -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    Box(Modifier.fillMaxWidth().heightIn(min = metrics.dp(MenuSize.TouchTarget)).menuPart(MenuPart.Row).padding(start = metrics.dp(MenuSize.RowInset)),
        contentAlignment = Alignment.CenterStart) { content() }
}

/** Frame of a full-width control (text field, preview): the system owns the inset; the control owns its inside. */
@Composable fun MenuControlSlot(error: String? = null, content: @Composable () -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    Column(Modifier.fillMaxWidth().menuPart(MenuPart.Row).padding(vertical = metrics.dp(MenuSize.ControlRowPadding))) {
        content()
        if (error != null) MenuText(MenuTextRole.Helper, error, Modifier.padding(start = textStart(), top = metrics.dp(MenuSize.HelperGap)).menuPart(MenuPart.Helper),
            tone = NoticeTone.Error)
    }
}

/** Menu roles and compact rows inside a popup body, where no page scaffold exists. */
@Composable fun MenuPopupSlot(content: @Composable () -> Unit) {
    val metrics = rememberMenuMetrics() ?: error("The stock menu tokens were rejected")
    val environment = remember(metrics) { MenuEnvironment(metrics, false, Dp.Infinity, true) }
    CompositionLocalProvider(LocalMenuEnvironment provides environment, content = content)
}

/** A scrolling popup body (choice lists). [scrollable] is behaviour from the binder. */
@Composable fun MenuPopupListSlot(scrollable: @Composable (ScrollState) -> Modifier, content: @Composable () -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    val scroll = rememberScrollState()
    Column(scrollable(scroll).padding(horizontal = metrics.dp(MenuGap.RowToRow), vertical = metrics.dp(MenuGap.HeadingToRow))) { content() }
}

@Composable fun MenuProgressSlot(status: String, bar: @Composable (Modifier) -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    Column(Modifier.fillMaxWidth().menuPart(MenuPart.Row).padding(vertical = metrics.dp(MenuSize.ControlRowPadding))) {
        bar(Modifier.fillMaxWidth().padding(horizontal = metrics.dp(MenuSize.RowInset)))
        MenuText(MenuTextRole.RowSummary, status, Modifier.padding(start = textStart(), end = metrics.dp(MenuSize.RowInset), top = metrics.dp(MenuSize.HelperGap))
            .menuPart(MenuPart.Summary))
    }
}

/** A notice: tone icon in the leading slot, text in the tone's ink, and an optional fix action under the text. */
@Composable fun MenuNoticeSlot(text: String, tone: NoticeTone, icon: @Composable (Modifier) -> Unit, fix: (@Composable () -> Unit)?) {
    val metrics = LocalMenuEnvironment.current.metrics
    Row(Modifier.fillMaxWidth().heightIn(min = metrics.dp(MenuSize.TouchTarget)).menuPart(MenuPart.Row)
        .padding(horizontal = metrics.dp(MenuSize.RowInset), vertical = metrics.dp(MenuSize.ControlRowPadding)), verticalAlignment = Alignment.CenterVertically) {
        LeadingSlot(null, icon, null)
        Column(Modifier.weight(1f)) {
            MenuText(MenuTextRole.NoticeText, text, Modifier.menuPart(MenuPart.Title), tone = tone)
            if (fix != null) Box(Modifier.padding(top = metrics.dp(MenuSize.HelperGap))) { fix() }
        }
    }
}

/** Children left to right in reading order, wrapping to further lines; used for a card's actions. */
@Composable fun MenuWrapSlot(content: @Composable () -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    val across = metrics.dp(MenuSize.ControlGap)
    val down = metrics.dp(MenuGap.RowToRow)
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0; var y = 0; var line = 0
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > constraints.maxWidth) { x = 0; y += line + down.roundToPx(); line = 0 }
            xs[index] = x; ys[index] = y
            x += placeable.width + across.roundToPx(); line = max(line, placeable.height)
        }
        layout(constraints.maxWidth, y + line) { placeables.forEachIndexed { index, placeable -> placeable.placeRelative(xs[index], ys[index]) } }
    }
}

/**
 * The stat grid: the largest of 3, 2 or 1 columns in which every label and every value fits on one line. The
 * column count follows the labels and a fixed value capacity, so loading and ready grids share geometry; a ready
 * value beyond capacity drops a column and is never split. Reading order is kept and mirrored in RTL.
 */
@Composable fun MenuStatGridSlot(labels: List<String>, values: List<Value<String>>) {
    val metrics = LocalMenuEnvironment.current.metrics
    val across = metrics.dp(MenuSize.ControlGap)
    val down = metrics.dp(MenuGap.RowToRow)
    val capacity = metrics.statValueCapacity
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        labels.forEachIndexed { index, label ->
            MenuValueText(MenuTextRole.StatValue, values[index], Modifier.menuPart(MenuPart.StatValue), width = capacity)
            MenuText(MenuTextRole.StatLabel, label, Modifier.menuPart(MenuPart.StatLabel))
        }
    }) { measurables, constraints ->
        val count = measurables.size / 2
        val need = (0 until count).maxOfOrNull { max(max(measurables[it * 2].maxIntrinsicWidth(Constraints.Infinity), capacity.roundToPx()),
            measurables[it * 2 + 1].maxIntrinsicWidth(Constraints.Infinity)) } ?: 0
        val gap = across.roundToPx()
        val columns = listOf(3, 2).firstOrNull { (constraints.maxWidth - gap * (it - 1)) / it >= need } ?: 1
        val cell = (constraints.maxWidth - gap * (columns - 1)) / columns
        val cellConstraints = Constraints(maxWidth = cell)
        val placeables = measurables.map { it.measure(cellConstraints) }
        val rows = (count + columns - 1) / columns
        val heights = IntArray(rows) { row -> (0 until columns).maxOf { column -> (row * columns + column).takeIf { it < count }
            ?.let { placeables[it * 2].height + placeables[it * 2 + 1].height } ?: 0 } }
        val total = heights.sum() + down.roundToPx() * (rows - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, total) {
            var y = 0
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    if (index >= count) break
                    val x = column * (cell + gap)
                    placeables[index * 2].placeRelative(x, y)
                    placeables[index * 2 + 1].placeRelative(x, y + placeables[index * 2].height)
                }
                y += heights[row] + down.roundToPx()
            }
        }
    }
}

/** The inside of a status card: header, stats and actions, each optional below the header. */
@Composable fun MenuCardSlot(title: String, infos: List<Value<String>>, leading: (@Composable (Modifier) -> Unit)?, stats: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?) {
    val metrics = LocalMenuEnvironment.current.metrics
    Column(Modifier.fillMaxWidth().menuPart(MenuPart.Row).padding(metrics.dp(MenuSize.RowInset)), verticalArrangement = Arrangement.spacedBy(metrics.dp(MenuGap.HeadingToRow))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) LeadingSlot(null, leading, null)
            Column(Modifier.weight(1f)) {
                MenuText(MenuTextRole.RowTitle, title, Modifier.menuPart(MenuPart.Title))
                infos.forEach { MenuValueText(MenuTextRole.RowSummary, it, Modifier.menuPart(MenuPart.Summary), MenuTextFlow.Static) }
            }
        }
        stats?.invoke()
        actions?.invoke()
    }
}

/** The legend bar: one segment per entry in the entry's marker tone, so bar and marker colours cannot differ. */
@Composable fun MenuLegendBarSlot(tones: List<MarkerTone>, weights: List<Float>) {
    val metrics = LocalMenuEnvironment.current.metrics
    val colors = tones.map { Color(metrics.spec.color(it).argb) }
    val track = LocalContentColor.current.copy(alpha = .12f)
    val rtl = metrics.direction == LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth().padding(horizontal = metrics.dp(MenuSize.RowInset)).height(metrics.dp(MenuSize.Marker)).clip(CircleShape).drawBehind {
        val total = weights.sum()
        if (total <= 0f) { drawRect(track); return@drawBehind }
        var x = 0f
        weights.forEachIndexed { index, weight ->
            val width = size.width * weight / total
            drawRect(colors[index], Offset(if (rtl) size.width - x - width else x, 0f), Size(width, size.height))
            x += width
        }
    })
}

/** The entries of a legend, one row gap apart, under the optional bar. */
@Composable fun MenuLegendSlot(bar: (@Composable () -> Unit)?, entries: @Composable () -> Unit) {
    val metrics = LocalMenuEnvironment.current.metrics
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(metrics.dp(MenuGap.RowToRow))) {
        bar?.invoke()
        entries()
    }
}

/** Two glyph-sized trailing controls of an ordered entry (move, remove), each with the full touch target. */
@Composable fun MenuTrailingActionsSlot(content: @Composable () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { content() } }
