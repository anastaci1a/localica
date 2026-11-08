package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.*

/** Existing action-face dimensions expressed by the reviewed stock spacing and control catalog. */
@Composable
internal fun ActionButtonFaceSlot(modifier: Modifier, iconOnly: Boolean,
    icon: @Composable (Modifier) -> Unit, label: @Composable (Modifier) -> Unit) {
    val spaces = StockUiSpec.space
    Row(modifier.padding(horizontal = spaces.getValue(Space.Comfortable) + spaces.getValue(Space.Tight) / 2,
        vertical = spaces.getValue(Space.Base)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Base))) {
        icon(Modifier.size(StockUiSpec.control.getValue(ControlSize.SettingsIcon)))
        if (!iconOnly) label(Modifier.width(IntrinsicSize.Max))
    }
}
