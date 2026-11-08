package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.Radius
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Stock-only settings geometry also works before the full Stage 3 root spec ingress lands. */
@Composable
internal fun SettingsStack(modifier: Modifier = Modifier, inset: Space? = null, gap: Space? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val spaces = StockUiSpec.space
    SettingsEntryHost(modifier) {
        Column(Modifier.fillMaxWidth().then(inset?.let { Modifier.padding(spaces.getValue(it)) } ?: Modifier),
            verticalArrangement = gap?.let { Arrangement.spacedBy(spaces.getValue(it)) } ?: Arrangement.Top,
            content = content)
    }
}

internal fun settingsReviewRadius() = StockUiSpec.radius.getValue(Radius.Menu)

@Composable
internal fun SettingsCandidateRow(modifier: Modifier, indicator: @Composable () -> Unit,
    label: @Composable (Modifier) -> Unit) {
    val spaces = StockUiSpec.space
    Row(modifier.fillMaxWidth().padding(spaces.getValue(Space.Comfortable)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Base))) {
        indicator()
        label(Modifier.weight(1f))
    }
}
