package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Historical settings geometry uses stock values, cached without per-frame allocations or token lookups. */
internal val SettingsHeadingPadding = PaddingValues(top = StockUiSpec.space.getValue(Space.Comfortable),
    bottom = StockUiSpec.space.getValue(Space.Tight))
internal val SettingsLabelGap = StockUiSpec.space.getValue(Space.Base)
private val compactPreferencePadding = PaddingValues()
private val regularPreferencePadding = PaddingValues(vertical = StockUiSpec.space.getValue(Space.Tight))
internal fun settingsPreferencePadding(compact: Boolean) = if (compact) compactPreferencePadding else regularPreferencePadding

/** The existing single icon Box keeps its width and vertical alignment; absent icons allocate no slot. */
@Composable
internal fun SettingsLeadingIconSlot(width: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart, content = content)
}

/** The existing page Column retains its modifier, arrangement and ColumnScope without an extra layout node. */
@Composable
internal fun SettingsPageBody(modifier: Modifier, verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = verticalArrangement, content = content)
}
