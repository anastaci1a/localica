package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.*

/** Keeps the icon and trailing face centred independently of headline wrapping. */
@Composable
internal fun PreferenceLinkFrameSlot(modifier: Modifier, rowPart: Modifier, reserveIcon: Boolean,
    icon: (@Composable (Modifier) -> Unit)?, headline: @Composable () -> Unit,
    summary: (@Composable () -> Unit)?, trailing: @Composable (Modifier) -> Unit) {
    val spec = LocalUiSpec.current.spec
    Row(modifier.heightIn(min = PreferenceFrameSpec.linkMinimumHeight).then(rowPart)
        .padding(horizontal = spec.space.getValue(Space.Comfortable), vertical = spec.space.getValue(Space.Compact)),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null || reserveIcon) Box(Modifier.width(PreferenceFrameSpec.iconSlotWidth), contentAlignment = Alignment.CenterStart) {
            icon?.invoke(Modifier.size(spec.control.getValue(ControlSize.SettingsIcon)))
        }
        Column(Modifier.weight(1f)) {
            headline()
            summary?.invoke()
        }
        trailing(Modifier.padding(start = spec.space.getValue(Space.Comfortable)).size(PreferenceFrameSpec.chevronSize))
    }
}

/** Measures the track and endpoint slots beneath the inset heading without changing track input. */
@Composable
internal fun PreferenceSliderFrameSlot(textStart: Dp, label: @Composable (Modifier) -> Unit,
    track: @Composable () -> Unit, endpoints: (@Composable () -> Unit)?, description: (@Composable (Modifier) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = LocalUiSpec.current.spec.space.getValue(Space.Base))) {
        label(Modifier.padding(start = textStart))
        track()
        endpoints?.invoke()
        description?.invoke(Modifier.padding(start = textStart))
    }
}

/** Owns the heading's measured inset while the caller retains its text and semantics. */
@Composable
internal fun Modifier.preferenceHeadingInset(textStart: Dp): Modifier {
    val spaces = LocalUiSpec.current.spec.space
    return fillMaxWidth().padding(start = textStart, top = spaces.getValue(Space.Comfortable), bottom = spaces.getValue(Space.Tight))
}
