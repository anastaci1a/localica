package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Home's comfortable list edges surround the measured return-control clearance. */
internal fun homeListContentPadding(obscuredInset: Dp, returnControlHeight: Int, density: Density): PaddingValues {
    val edge = StockUiSpec.space.getValue(Space.Comfortable)
    return PaddingValues(top = edge, bottom = obscuredInset + with(density) { returnControlHeight.toDp() } + edge)
}

/** The selector, optional minimize action and menu share one compact, centred header row. */
@Composable
internal fun QueueSelectorActionsRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Compact)),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Playback position takes the remaining width between sort and save actions. */
@Composable
internal fun QueuePlaybackActionsRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Compact)),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Inert View alternatives retain the live popup's compact rhythm and shadow clearance. */
@Composable
internal fun BrowseViewMeasurementColumn(shadowInset: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = shadowInset, vertical = shadowInset),
        verticalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Compact)), content = content)
}
