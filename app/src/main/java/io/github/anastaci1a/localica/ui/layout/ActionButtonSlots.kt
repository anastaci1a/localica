package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.*

/** Existing action-face dimensions expressed by the reviewed stock spacing and control catalog. */
@Composable
internal fun ActionButtonFaceSlot(modifier: Modifier, iconOnly: Boolean,
    icon: @Composable (Modifier) -> Unit, label: @Composable (Modifier) -> Unit,
    divider: (@Composable () -> Unit)? = null, retainedFace: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = StockUiSpec.control.getValue(ControlSize.SettingsIcon)) {
    val spaces = StockUiSpec.space
    val faceExtent = StockUiSpec.control.getValue(ControlSize.SettingsIcon) * 2
    val dual = divider != null
    val inset = if (dual) modifier else modifier.padding(
        horizontal = spaces.getValue(Space.Comfortable) + spaces.getValue(Space.Tight) / 2,
        vertical = spaces.getValue(Space.Base))
    Row(inset, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (dual) Arrangement.Center else Arrangement.spacedBy(spaces.getValue(Space.Base))) {
        if (dual && retainedFace) Box(Modifier.size(faceExtent), contentAlignment = Alignment.Center) { icon(Modifier.size(iconSize)) }
        else icon(if (dual) Modifier else Modifier.size(iconSize))
        divider?.invoke()
        if (!iconOnly) {
            if (dual && retainedFace) Box(Modifier.size(faceExtent), contentAlignment = Alignment.Center) { label(Modifier.size(iconSize)) }
            else label(if (dual) Modifier else Modifier.width(IntrinsicSize.Max))
        }
    }
}
