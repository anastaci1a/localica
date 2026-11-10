package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import io.github.anastaci1a.localica.ui.spec.ControlSize
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** The queue disclosure face uses the standard icon footprint in both retained and live titles. */
@Composable
internal fun QueueSelectorDisclosureIcon(painter: Painter) {
    Icon(painter, "Select queue", Modifier.size(StockUiSpec.control.getValue(ControlSize.Icon)))
}
