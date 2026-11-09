package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import io.github.anastaci1a.localica.ui.spec.ControlSize
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
import io.github.anastaci1a.localica.ui.spec.Radius
import io.github.anastaci1a.localica.ui.spec.Space

internal enum class SettingsActionLayout { Inline, Wrapping }

/** Stock ordered-setting card; inline tabs and wrapping filters retain their action geometry. */
@Composable
internal fun SettingsOrderCard(
    label: String,
    layout: SettingsActionLayout,
    moveUp: @Composable () -> Unit,
    moveDown: @Composable () -> Unit,
    remove: @Composable () -> Unit,
) {
    val spec = LocalUiSpec.current.spec
    Surface(shape = RoundedCornerShape(spec.radius.getValue(Radius.Card)), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(spec.space.getValue(Space.Base))) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            when (layout) {
                SettingsActionLayout.Inline -> Row { moveUp(); moveDown(); remove() }
                SettingsActionLayout.Wrapping -> FlowRow { moveUp(); moveDown(); remove() }
            }
        }
    }
}

/** Review metadata and its candidate actions grow together without imposing a minimum height. */
@Composable
internal fun SettingsReviewItem(
    title: String,
    summary: String,
    candidateActions: @Composable ColumnScope.() -> Unit,
) {
    val spec = LocalUiSpec.current.spec
    Column(Modifier.fillMaxWidth().padding(vertical = spec.space.getValue(Space.Compact)),
        verticalArrangement = Arrangement.spacedBy(spec.space.getValue(Space.Tight))) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(summary)
        candidateActions()
    }
}

internal data class SettingsComparisonIcon(val painter: Painter, val contentDescription: String)

/** Two fixed icon samples precede weighted, naturally wrapping metadata in logical order. */
@Composable
internal fun SettingsIconComparisonRow(
    primary: SettingsComparisonIcon,
    comparison: SettingsComparisonIcon,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val spec = LocalUiSpec.current.spec
    Row(Modifier.fillMaxWidth().padding(vertical = spec.space.getValue(Space.Compact)).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spec.space.getValue(Space.Base))) {
        Icon(primary.painter, primary.contentDescription, Modifier.size(spec.control.getValue(ControlSize.Icon)))
        Icon(comparison.painter, comparison.contentDescription, Modifier.size(spec.control.getValue(ControlSize.Icon)))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
