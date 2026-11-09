package io.github.anastaci1a.localica.ui.layout

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import io.github.anastaci1a.localica.ui.spec.Space

/** Marker sits inside the stock text inset, so it never shifts the shared row's text or source face. */
internal fun Modifier.settingsLinkMarker(color: Color?): Modifier = if (color == null) this else drawWithContent {
    drawContent()
    val inset = StockUiSpec.space.getValue(Space.Compact).toPx()
    drawCircle(color, radius = StockUiSpec.space.getValue(Space.Tight).toPx(),
        center = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - inset else inset, size.height / 2))
}

/** Applies the stock row's text inset without introducing another row or highlight owner. */
@Composable
internal fun SettingsTextLine(textInset: Dp, content: @Composable (Modifier) -> Unit) {
    content(Modifier.fillMaxWidth().padding(start = textInset))
}
