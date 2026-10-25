package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*

/** No input, anchors, glass recordings or effects: only the live controls' shared layout faces. */
@Composable
private fun ChoiceMeasurement(title: String, labels: List<String>, popup: Boolean = false) {
    val required = choiceRequiredCellWidth(labels)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cellWidth = with(density) { required.toPx() }
    val policy = androidx.compose.runtime.remember(labels, popup, cellWidth) {
        object : androidx.compose.ui.layout.MeasurePolicy {
            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int): Int =
                children[if (!popup && labels.size in 2..3) 0 else 1].maxIntrinsicWidth(height)

            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int): Int =
                children[if (!popup && labels.size in 2..3) 0 else 1].minIntrinsicWidth(height)

            override fun androidx.compose.ui.layout.MeasureScope.measure(children: List<androidx.compose.ui.layout.Measurable>,
                constraints: androidx.compose.ui.unit.Constraints): androidx.compose.ui.layout.MeasureResult {
                val index = if (choiceFitsInline(labels.size, cellWidth, constraints.maxWidth.toFloat(), popup)) 0 else 1
                val measured = children[index].measure(constraints)
                return layout(measured.width, measured.height) { }
            }

        }
    }
    androidx.compose.ui.layout.Layout(content = {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            GlassChoicesMeasurement(labels)
        }
        PopupMeasurementMaximum { labels.forEach { SettingsLinkFace(title, it, null) } }
    }, measurePolicy = policy)

}

@Composable
internal fun BrowseOptionsMeasurement(declaration: BrowseOptionsDeclaration, shadowInset: Dp) {
    @Composable fun ScopeMeasurement() {
        HorizontalDivider()
        BooleanPreferenceFrame("Save only for this list") { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) }
        if (declaration.overridden) {
            TextButtonMeasurement("Use list-type defaults")
        }
    }
    PopupMeasurementMaximum {
        Column(Modifier.fillMaxWidth().padding(horizontal = shadowInset, vertical = shadowInset),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (declaration.randomize) {
                GlassActionRowMeasurement {
                    GlassActionButtonFace(R.drawable.ic_shuffle, "Randomize", Modifier.heightIn(min = CompactControlSize))
                    GlassActionButtonFace(R.drawable.ic_revert, "Revert", Modifier.heightIn(min = CompactControlSize))
                }
                if (declaration.reverse) GlassActionRowMeasurement {
                    GlassActionButtonFace(R.drawable.ic_reverse, "Reverse", Modifier.heightIn(min = CompactControlSize))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            ChoiceMeasurement("Sort by", declaration.sortFields.map(::sortLabel), popup = true)
            GlassChoicesMeasurement(listOf("Ascending", "Descending"))
            if (declaration.reserveError) Text("Couldn’t save this order. Try again.", color = MaterialTheme.colorScheme.error)
        }
        for (gallery in listOf(false, true)) Column(
            Modifier.fillMaxWidth().padding(horizontal = shadowInset, vertical = shadowInset),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceMeasurement("View mode", listOf("List", "Gallery"))
            if (gallery) PopupMeasurementMaximum {
                for (count in 1..5) SliderPreferenceFrame(itemsPerRowTitle(count), null, null, null) {
                    Spacer(Modifier.fillMaxWidth().height(InterfaceSliderHeight))
                }
            } else ChoiceMeasurement("Size", declaration.sizes.map { it.name }, popup = true)
            ChoiceMeasurement("Extra information", declaration.information.map(::extraInfoLabel), popup = true)
            if (declaration.quickSearch)
                BooleanPreferenceFrame("Quick-search box") { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) }
            if (declaration.mutable) ScopeMeasurement()
        }
    }
}

@Composable
internal fun BrowseOptionsActionsMeasurement() {
    PopupMeasurementMaximum {
        for (labels in listOf(listOf("Close", "Apply"), listOf("Cancel", "Save")))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                labels.forEach { TextButtonMeasurement(it) }
            }
    }
}

/** Entire supported envelope, including chrome; independent of the current draft/tab. */
@Composable
internal fun BrowseOptionsWidthMeasurement(declaration: BrowseOptionsDeclaration, shadowInset: Dp) {
    Column(Modifier.padding(24.dp)) {
        GlassChoicesMeasurement(listOf("Sort", "View"), listOf(R.drawable.ic_sort, R.drawable.ic_eye), tab = true)
        BrowseOptionsMeasurement(declaration, shadowInset)
        if (declaration.footerScope) {
            BooleanPreferenceFrame("Save only for this list") { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) }
            if (declaration.overridden) TextButtonMeasurement("Use list-type defaults")
        }
        BrowseOptionsActionsMeasurement()
    }
}
