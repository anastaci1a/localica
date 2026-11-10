package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Fixed dialog footer: the leading divider and source actions use the stock chooser spacing. */
@Composable
internal fun ImageChooserSourceActions(content: @Composable ColumnScope.() -> Unit) {
    val spaces = StockUiSpec.space
    Column(verticalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Base))) {
        HorizontalDivider(Modifier.padding(vertical = spaces.getValue(Space.Tight)))
        content()
    }
}
