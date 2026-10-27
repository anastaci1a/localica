package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import io.github.anastaci1a.localica.queue.*

/** Passive evidence for attempted complete layouts, in pixels; null keeps production silent. */
internal val LocalRepeatFitEvidence = staticCompositionLocalOf<((String) -> Unit)?> { null }

private fun Modifier.repeatFitComponent(name: String, heights: MutableMap<String, Int>?): Modifier =
    if (heights == null) this else layout { measurable, constraints ->
        val measured = measurable.measure(constraints)
        heights[name] = measured.height
        layout(measured.width, measured.height) { measured.place(0, 0) }
    }

private val repeatModeLabels = listOf("Simple", "Advanced")
private const val queueRepeatWarning = "Queue-end options apply after you turn off repeating the current song."
private data class RepeatWarningColumns(val choices: Dp, val warning: Dp)

@Composable
internal fun RepeatOptionsDialog(settings: QueueSettings, onDismiss: () -> Unit, onApply: ((QueueSettings) -> QueueSettings) -> Unit) {
    var advanced by remember { mutableStateOf(settings.advancedRepeat != null) }
    var simple by remember { mutableStateOf(settings.repeat) }
    var editingSection by remember { mutableStateOf(false) }
    // A hidden parent has no foreground to rasterize on older Android versions.
    // Child editors sample the same original scene as the parent, never that empty panel.
    val sectionBackdrop = LocalModalBackdrop.current ?: LocalFrostedBackdrop.current ?: LocalGlassDialogs.current?.backdrop
    var policy by remember { mutableStateOf(settings.advancedRepeat ?: AdvancedRepeat(
        songEnd = if (settings.repeat == QueueRepeat.One) SongEnd.Repeat else SongEnd.Next,
        queueEnd = if (settings.repeat == QueueRepeat.All) QueueEnd.Repeat else QueueEnd.Stop)) }
    val simpleScroll = rememberScrollState()
    val advancedScroll = rememberScrollState()
    GlassAlertDialog(onDismissRequest = onDismiss,
        modifier = Modifier.testTag("repeat-options-dialog").alpha(if (editingSection) 0f else 1f)
            .then(if (editingSection) Modifier.clearAndSetSemantics { } else Modifier), title = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Repeat modes")
            GlassChoices(repeatModeLabels, if (advanced) 1 else 0, horizontal = true,
                modifier = Modifier.testTag("repeat-options-tabs")) { advanced = it == 1 }
        }
    }, text = {
        // Simple's complete natural layout is the common compact viewport, not a Repeat-only
        // dp/screen-fraction cap. The shared host reserves chrome and bounds it to the window.
        StablePopupBody(Modifier.testTag("repeat-options-body"), measurement = {
            RepeatOptionsFields(advanced = false)
        }) {
            val scroll = if (advanced) advancedScroll else simpleScroll
            RepeatOptionsFields(advanced, simple = simple, policy = policy,
                modifier = Modifier.testTag("repeat-options-scroll").scrollEdgeFade(scroll).controlVerticalScroll(scroll),
                onSimple = { simple = it }, onPolicy = { policy = it }, onChildVisibility = { editingSection = it },
                sectionBackdrop = sectionBackdrop,
                onReset = { simple = QueueRepeat.Off; policy = AdvancedRepeat() })
        }
    }, confirmButton = { ActionTextButton(onClick = {
        val appliedPolicy = if (advanced) policy else null
        val appliedRepeat = if (advanced) policy.repeat else simple
        onApply { it.copy(advancedRepeat = appliedPolicy, repeat = appliedRepeat) }; onDismiss()
    }) { Text("Apply") } }, dismissButton = { ActionTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        widthMeasurement = {
            Column(Modifier.padding(24.dp)) {
                Text("Repeat modes", style = MaterialTheme.typography.titleLarge)
                GlassChoicesMeasurement(repeatModeLabels)
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    PopupMeasurementMaximum {
                        RepeatOptionsFields(advanced = false)
                        SongEnd.entries.forEach { RepeatOptionsFields(advanced = true, policy = AdvancedRepeat(songEnd = it)) }
                        QueueEnd.entries.forEach { RepeatOptionsFields(advanced = true, policy = AdvancedRepeat(queueEnd = it)) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButtonMeasurement("Cancel"); TextButtonMeasurement("Apply")
                }
            }
        })
}

/** Omitting callbacks uses only inert shared faces: no input, glass recordings or effects. */
@Composable
private fun RepeatOptionsFields(advanced: Boolean, modifier: Modifier = Modifier,
    simple: QueueRepeat = QueueRepeat.Off, policy: AdvancedRepeat = AdvancedRepeat(),
    onSimple: ((QueueRepeat) -> Unit)? = null, onPolicy: ((AdvancedRepeat) -> Unit)? = null,
    onChildVisibility: (Boolean) -> Unit = {},
    sectionBackdrop: FrostedBackdrop? = null,
    onReset: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!advanced) RepeatChoices(QueueRepeat.entries, simple, { when (it) {
            QueueRepeat.Off -> "Play next song"; QueueRepeat.All -> "Repeat queue"; QueueRepeat.One -> "Repeat song"
        } }, onSimple)
        else {
            RepeatSection.entries.forEach { section ->
                val summary = if (section == RepeatSection.Song) policy.songEnd.label else policy.queueEnd.label
                if (onPolicy == null) SettingsLinkFace(section.title, summary, null)
                else RepeatSectionPreference(section, policy, summary, sectionBackdrop, onChildVisibility, onPolicy)
            }
        }
        if (onReset == null) TextButtonMeasurement("Reset")
        else ActionTextButton(onClick = onReset) { Text("Reset") }
    }
}

internal enum class RepeatSection(val title: String) {
    Song("When a song ends"), Queue("When a queue ends")
}

@Composable
private fun RepeatSectionPreference(section: RepeatSection, policy: AdvancedRepeat, summary: String,
    backdrop: FrostedBackdrop?,
    onVisibility: (Boolean) -> Unit,
    onSelect: (AdvancedRepeat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DisposableEffect(open) {
        val wasOpen = open
        if (wasOpen) onVisibility(true)
        onDispose { if (wasOpen) onVisibility(false) }
    }
    val anchor = rememberGlassAnchor()
    SettingsLink(section.title, summary, anchor = anchor) { open = true }
    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor, LocalModalBackdrop provides backdrop) {
        RepeatSectionDialog(section, policy, { open = false }, onSelect)
    }
}

/** Section drafts commit to the enclosing editor only on Select, never on dismissal. */
@Composable
internal fun RepeatSectionDialog(section: RepeatSection, policy: AdvancedRepeat,
    onDismiss: () -> Unit, onSelect: (AdvancedRepeat) -> Unit) {
    var draft by remember { mutableStateOf(policy) }
    GlassDialog(onDismiss, fitContent = true) {
        val measurer = rememberTextMeasurer()
        val fitEvidence = LocalRepeatFitEvidence.current
        val labels = if (section == RepeatSection.Song) SongEnd.entries.map { it.label } else QueueEnd.entries.map { it.label }
        val regularStyle = MaterialTheme.typography.bodyMedium
        val compactStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp)
        PopupNaturalWidth({ RepeatSectionFrame(section, draft, null, null, null) }) {
            // Try the complete layouts at the real safe width/height. Compact changes
            // spacing and type roles, never system font scale, labels or touch targets.
            SubcomposeLayout(Modifier.testTag("repeat-section-dialog")) { constraints ->
                data class Fit(val chrome: DialogChrome, val cellHeight: Dp, val columns: RepeatWarningColumns?)
                fun measureCandidate(chrome: DialogChrome, columns: RepeatWarningColumns? = null): Fit? {
                    val compact = chrome == DialogChrome.Compact
                    val style = if (compact) compactStyle else regularStyle
                    val padding = chrome.perimeterDp.dp
                    val labelWidth = if (columns == null) (constraints.maxWidth -
                        (padding * 2 + (ChoiceInnerInset + ChoiceLabelHorizontalInset) * 2).roundToPx()).coerceAtLeast(1)
                    else (columns.choices.roundToPx() - 2 * (ChoiceInnerInset.roundToPx() +
                        ChoiceLabelHorizontalInset.roundToPx())).coerceAtLeast(1)
                    val labelLayouts = labels.map { measurer.measure(AnnotatedString(it), style,
                        overflow = TextOverflow.Clip, maxLines = 2, constraints = Constraints(maxWidth = labelWidth)) }
                    val cellHeight = maxOf(48.dp, (labelLayouts.maxOf { it.size.height }).toDp() + 8.dp)
                    val warningLayout = columns?.let { measurer.measure(AnnotatedString(queueRepeatWarning), style,
                        overflow = TextOverflow.Clip, constraints = Constraints(maxWidth = it.warning.roundToPx())) }
                    val componentHeights = if (fitEvidence != null) mutableMapOf<String, Int>() else null
                    val arrangement = if (columns == null) "full-width" else "queue-warning-columns"
                    val natural = subcompose("measurement-$compact-${columns?.choices?.roundToPx() ?: -1}") {
                        Box(Modifier.clearAndSetSemantics { }) {
                            RepeatSectionFrame(section, draft, null, null, null, chrome, cellHeight, style,
                                componentHeights, columns)
                        }
                    }.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val fits = natural.height <= constraints.maxHeight && labelLayouts.none { it.hasVisualOverflow } &&
                        warningLayout?.hasVisualOverflow != true
                    // Body includes warning; component heights are not additive without subtracting it.
                    fitEvidence?.invoke("section=$section chrome=$chrome arrangement=$arrangement units=px " +
                        "natural=${natural.width}x${natural.height} available=${constraints.maxWidth}x${constraints.maxHeight} " +
                        "title=${componentHeights?.get("title")} body=${componentHeights?.get("body")} " +
                        "warning=${componentHeights?.get("warning") ?: 0} footer=${componentHeights?.get("footer")} " +
                        "perimeter=${padding.roundToPx()} gap=${chrome.gapDp.dp.roundToPx()} " +
                        "cell=${cellHeight.roundToPx()} labelWidth=$labelWidth labelOverflow=${labelLayouts.any { it.hasVisualOverflow }} " +
                        "choiceColumn=${columns?.choices?.roundToPx()} warningColumn=${columns?.warning?.roundToPx()} " +
                        "warningNatural=${warningLayout?.size} warningOverflow=${warningLayout?.hasVisualOverflow} " +
                        "density=$density fontScale=$fontScale fits=$fits bodyIncludesWarning=true")
                    return if (fits) Fit(chrome, cellHeight, columns) else null
                }
                // Preserve both complete full-width layouts first. Only the Queue
                // warning case needs a bounded alternative; never split its selector.
                val fullWidthFit = DialogChrome.entries.firstNotNullOfOrNull { measureCandidate(it) }
                val fit = fullWidthFit ?: if (section == RepeatSection.Queue && draft.songEnd == SongEnd.Repeat &&
                    constraints.hasBoundedWidth) {
                    val chrome = DialogChrome.Compact
                    val available = constraints.maxWidth - 2 * chrome.perimeterDp.dp.roundToPx() - chrome.gapDp.dp.roundToPx()
                    val minimum = 48.dp.roundToPx()
                    val minimumChoices = minimum + 2 * ChoiceInnerInset.roundToPx()
                    val maximumChoices = available - minimum
                    val labelInsets = 2 * (ChoiceInnerInset.roundToPx() + ChoiceLabelHorizontalInset.roundToPx())
                    fun labelsFit(choices: Int): Boolean = labels.all { label ->
                        !measurer.measure(AnnotatedString(label), compactStyle, overflow = TextOverflow.Clip,
                            maxLines = 2, constraints = Constraints(maxWidth = (choices - labelInsets).coerceAtLeast(1)))
                            .hasVisualOverflow
                    }
                    // Search integer pixel widths, not a presumed font/word length.
                    // The finite interval preserves both the selector target and the
                    // warning column. Text-only probes do not compose extra frames.
                    val narrowestChoices = if (maximumChoices >= minimumChoices && labelsFit(maximumChoices)) {
                        var low = minimumChoices
                        var high = maximumChoices
                        while (low < high) {
                            val middle = low + (high - low) / 2
                            if (labelsFit(middle)) high = middle else low = middle + 1
                        }
                        // Independently recheck the proposed boundary and its immediate
                        // predecessor; the complete candidate below is still authoritative.
                        low.takeIf { labelsFit(it) && (it == minimumChoices || !labelsFit(it - 1)) }
                    } else null
                    fitEvidence?.invoke("section=$section arrangement=queue-warning-width-search units=px " +
                        "minimumChoices=$minimumChoices maximumChoices=$maximumChoices derivedChoices=$narrowestChoices " +
                        "availableColumns=$available labelInsets=$labelInsets")
                    // These are width candidates, not assumed heights. Every candidate
                    // remeasures full labels, unrestricted warning and the ENTIRE frame.
                    listOfNotNull(narrowestChoices, available / 2, available * 3 / 5).distinct().firstNotNullOfOrNull { choices ->
                        val warning = available - choices
                        if (choices < minimumChoices || warning < minimum) null
                        else measureCandidate(chrome, RepeatWarningColumns(choices.toDp(), warning.toDp()))
                    }
                } else null
                val live = subcompose("content") {
                    if (fit != null) RepeatSectionFrame(section, draft, onDismiss,
                        onSelect = { onSelect(draft); onDismiss() }, onChange = { draft = it },
                        chrome = fit.chrome, choiceHeight = fit.cellHeight,
                        choiceStyle = if (fit.chrome == DialogChrome.Compact) compactStyle else regularStyle,
                        warningColumns = fit.columns)
                    else Column(Modifier.fillMaxWidth().padding(DialogChrome.Compact.perimeterDp.dp).testTag("repeat-section-insufficient-space"),
                        verticalArrangement = Arrangement.spacedBy(DialogChrome.Compact.gapDp.dp)) {
                        Text("More space needed", style = MaterialTheme.typography.titleLarge)
                        Text("Enlarge the window or reduce text size.", style = compactStyle)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ActionTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = compactStyle) }
                        }
                    }
                }.single().measure(constraints.copy(minHeight = 0))
                layout(live.width, live.height) { live.place(0, 0) }
            }
        }
    }
}

@Composable
private fun RepeatSectionFrame(section: RepeatSection, policy: AdvancedRepeat,
    onDismiss: (() -> Unit)?, onSelect: (() -> Unit)?, onChange: ((AdvancedRepeat) -> Unit)?,
    chrome: DialogChrome = DialogChrome.Regular, choiceHeight: Dp? = null, choiceStyle: TextStyle? = null,
    componentHeights: MutableMap<String, Int>? = null, warningColumns: RepeatWarningColumns? = null) {
    val compact = chrome == DialogChrome.Compact
    val style = choiceStyle ?: MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth().padding(chrome.perimeterDp.dp)
        .testTag(if (compact) "repeat-section-compact" else "repeat-section-regular"),
        verticalArrangement = Arrangement.spacedBy(chrome.gapDp.dp)) {
        Text(section.title, Modifier.testTag("repeat-section-title").repeatFitComponent("title", componentHeights), style = MaterialTheme.typography.titleLarge)
        CompositionLocalProvider(LocalCompactPreferenceFrames provides compact) {
            ProvideTextStyle(style) {
                Column(Modifier.fillMaxWidth().testTag("repeat-section-body").repeatFitComponent("body", componentHeights), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
                    if (section == RepeatSection.Song) {
                        RepeatChoices(SongEnd.entries, policy.songEnd, { it.label }, onChange?.let { change ->
                            { change(policy.copy(songEnd = it)) }
                        }, choiceHeight, style, compact)
                    } else {
                        RepeatBoolean("Reset current song to the first song", policy.resetToFirst, onChange?.let { change ->
                            { change(policy.copy(resetToFirst = it)) }
                        })
                        RepeatBoolean("Reshuffle when shuffle is enabled", policy.reshuffle, onChange?.let { change ->
                            { change(policy.copy(reshuffle = it)) }
                        })
                        @Composable fun Choices() {
                            RepeatChoices(QueueEnd.entries, policy.queueEnd, { it.label }, onChange?.let { change ->
                                { change(policy.copy(queueEnd = it)) }
                            }, choiceHeight, style, compact)
                        }
                        @Composable fun Warning(modifier: Modifier = Modifier) {
                            Text(queueRepeatWarning, modifier = modifier.repeatFitComponent("warning", componentHeights),
                                style = if (compact) style else MaterialTheme.typography.bodySmall)
                        }
                        if (warningColumns != null && policy.songEnd == SongEnd.Repeat) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(chrome.gapDp.dp),
                                verticalAlignment = Alignment.Top) {
                                Box(Modifier.width(warningColumns.choices)) { Choices() }
                                Warning(Modifier.width(warningColumns.warning))
                            }
                        } else {
                            Choices()
                            if (policy.songEnd == SongEnd.Repeat) Warning()
                        }
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().testTag("repeat-section-footer").repeatFitComponent("footer", componentHeights), horizontalArrangement = Arrangement.End) {
            if (onDismiss == null) TextButtonMeasurement("Cancel", if (compact) style else null)
            else ActionTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", style = if (compact) style else MaterialTheme.typography.labelLarge) }
            if (onSelect == null) TextButtonMeasurement("Select", if (compact) style else null)
            else ActionTextButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select", style = if (compact) style else MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun <T> RepeatChoices(values: List<T>, selected: T, label: (T) -> String, onSelect: ((T) -> Unit)?,
    cellHeight: Dp? = null, labelStyle: TextStyle? = null, compact: Boolean = false) {
    val inset = if (compact) 0.dp else ChoiceOuterVerticalInset
    if (onSelect == null) GlassChoicesMeasurement(values.map(label), horizontal = false,
        cellHeight = cellHeight, labelStyle = labelStyle, outerVerticalInset = inset)
    else GlassChoices(values.map(label), values.indexOf(selected), horizontal = false,
        cellHeight = cellHeight, labelStyle = labelStyle, outerVerticalInset = inset) { onSelect(values[it]) }
}

@Composable
private fun RepeatBoolean(label: String, value: Boolean, onValue: ((Boolean) -> Unit)?) {
    if (onValue == null) BooleanPreferenceFrame(label) { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) }
    else BooleanPreference(label, value, onValue)
}
