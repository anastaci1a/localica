package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
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
import androidx.compose.ui.text.font.FontWeight
import io.github.anastaci1a.localica.queue.*

private val repeatModeLabels = listOf("Simple", "Advanced")

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
        val labels = if (section == RepeatSection.Song) SongEnd.entries.map { it.label } else QueueEnd.entries.map { it.label }
        val regularStyle = MaterialTheme.typography.bodyMedium
        val compactStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp)
        PopupNaturalWidth({ RepeatSectionFrame(section, draft, null, null, null) }) {
            // Try the complete layouts at the real safe width/height. Compact changes
            // spacing and type roles, never system font scale, labels or touch targets.
            SubcomposeLayout(Modifier.testTag("repeat-section-dialog")) { constraints ->
                data class Fit(val compact: Boolean, val cellHeight: Dp)
                val fit = listOf(false, true).firstNotNullOfOrNull { compact ->
                    val style = if (compact) compactStyle else regularStyle
                    val padding = if (compact) 8.dp else 12.dp
                    val labelWidth = (constraints.maxWidth -
                        (padding * 2 + (ChoiceInnerInset + ChoiceLabelHorizontalInset) * 2).roundToPx()).coerceAtLeast(1)
                    val labelLayouts = labels.map { measurer.measure(AnnotatedString(it), style,
                        overflow = TextOverflow.Clip, maxLines = 2, constraints = Constraints(maxWidth = labelWidth)) }
                    val cellHeight = maxOf(48.dp, (labelLayouts.maxOf { it.size.height }).toDp() + 8.dp)
                    val natural = subcompose("measurement-$compact") {
                        Box(Modifier.clearAndSetSemantics { }) {
                            RepeatSectionFrame(section, draft, null, null, null, compact, cellHeight, style)
                        }
                    }.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    if (natural.height <= constraints.maxHeight && labelLayouts.none { it.hasVisualOverflow })
                        Fit(compact, cellHeight) else null
                }
                val live = subcompose("content") {
                    if (fit != null) RepeatSectionFrame(section, draft, onDismiss,
                        onSelect = { onSelect(draft); onDismiss() }, onChange = { draft = it },
                        compact = fit.compact, choiceHeight = fit.cellHeight,
                        choiceStyle = if (fit.compact) compactStyle else regularStyle)
                    else Column(Modifier.fillMaxWidth().padding(8.dp).testTag("repeat-section-insufficient-space"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("More space needed", style = compactStyle)
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
    compact: Boolean = false, choiceHeight: Dp? = null, choiceStyle: TextStyle? = null) {
    val style = choiceStyle ?: MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp,
        vertical = if (compact) 0.dp else 12.dp)
        .testTag(if (compact) "repeat-section-compact" else "repeat-section-regular"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        Text(section.title, style = if (compact) style.copy(fontWeight = FontWeight.SemiBold)
            else MaterialTheme.typography.titleMedium)
        CompositionLocalProvider(LocalCompactPreferenceFrames provides compact) {
            ProvideTextStyle(style) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
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
                        RepeatChoices(QueueEnd.entries, policy.queueEnd, { it.label }, onChange?.let { change ->
                            { change(policy.copy(queueEnd = it)) }
                        }, choiceHeight, style, compact)
                        if (policy.songEnd == SongEnd.Repeat) Text(
                            "Queue-end options apply after you turn off repeating the current song.",
                            style = if (compact) style else MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
