package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.ControlSize
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

@Composable
internal fun AsyncSpinnerSlot(modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(StockUiSpec.control.getValue(ControlSize.PreferenceRow))
        .padding(top = StockUiSpec.space.getValue(Space.Base)), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(Modifier.size(StockUiSpec.control.getValue(ControlSize.SettingsIcon)))
    }
}

@Composable
internal fun AsyncSectionRowSlot(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Compact))) { content() }
}

@Composable
internal fun AsyncValueSlot(modifier: Modifier, text: @Composable () -> Unit, placeholder: @Composable BoxScope.() -> Unit) {
    Box(modifier) { text(); placeholder() }
}
