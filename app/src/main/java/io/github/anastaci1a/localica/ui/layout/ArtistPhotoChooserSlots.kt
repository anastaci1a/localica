package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.ArtistPhotoChooserSpec
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** The caller supplies its existing scroll chain, after the stock viewport constraint. */
@Composable
internal fun ArtistPhotoChooserBody(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.heightIn(max = ArtistPhotoChooserSpec.maximumBodyHeight).then(modifier),
        verticalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Base)), content = content)
}

/** Loading retains intrinsic row width; populated rows fill the available width. */
@Composable
internal fun ArtistPhotoChooserRow(loading: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(if (loading) Modifier else Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Base)), content = content)
}

@Composable
internal fun RowScope.ArtistPhotoChooserCell(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(StockUiSpec.space.getValue(Space.Tight)), content = content)
}

@Composable
internal fun RowScope.ArtistPhotoChooserEmptyCell() {
    Spacer(Modifier.weight(1f))
}

@Composable
internal fun ArtistPhotoChooserFace(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
}
