package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun ListViewFields(route: BrowseRoute, value: BrowseStyle, onChange: (BrowseStyle) -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp.toFloat()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoicePreference("View mode", value.presentation != Presentation.List, listOf(false, true), { if (it) "Gallery" else "List" }) {
            onChange(value.copy(presentation = if (it) Presentation.Standard else Presentation.List))
        }
        if (value.presentation != Presentation.List) {
            val columns = value.itemsPerRow ?: galleryColumns(value.presentation, width).coerceIn(1, 5)
            SliderPreference("Items per row: $columns", columns.toFloat(), { onChange(value.copy(itemsPerRow = it.roundToInt().coerceIn(1, 5))) },
                valueRange = 1f..5f, steps = 3, modifier = Modifier.semantics {
                    contentDescription = "Items per row"; stateDescription = columns.toString()
                })
        } else ChoicePreference("Size", value.listSize, ListSize.entries, { it.name }) { onChange(value.copy(listSize = it)) }
        ChoicePreference("Extra information", value.extraInfo, ExtraInfo.entries, ::extraInfoLabel, popup = true) { onChange(value.copy(extraInfo = it)) }
        if (!route.search && route.collection?.kind != MediaKind.Album && (route.collection != null || route.tab != MainTab.Library))
            BooleanPreference("Quick-search box", value.quickSearch) { onChange(value.copy(quickSearch = it)) }
    }
}

private fun extraInfoLabel(info: ExtraInfo) = when (info) {
    ExtraInfo.Automatic -> "Follow sort order"
    ExtraInfo.Count -> "Number of songs"
    ExtraInfo.Added -> "Date added"
    ExtraInfo.MostPlayed -> "Play count"
    ExtraInfo.Modified -> "Date modified"
    ExtraInfo.AlbumArtist -> "Album Artist"
    else -> info.name
}
