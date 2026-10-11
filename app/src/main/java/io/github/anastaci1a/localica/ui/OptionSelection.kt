package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Full-row choice treatment shared by sorting and settings dialogs. */
@Composable
internal fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(label, Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(Corners.card)
        .background(if (selected) colors.primaryContainer else Color.Transparent)
        .border(1.dp, if (selected) colors.primary.copy(alpha = .55f) else Color.Transparent, Corners.card)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun SortDirection(ascending: Boolean, reducedMotion: Boolean, onSelect: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(Corners.card)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(4.dp).selectableGroup()) {
        val segmentWidth = maxWidth / 2
        val offset by animateDpAsState(if (ascending) 0.dp else segmentWidth,
            tween(if (reducedMotion) 0 else 300), label = "Sort direction")
        Box(Modifier.offset(x = offset).width(segmentWidth).height(48.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, Corners.card)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f), Corners.card))
        Row {
            listOf(true to "Ascending", false to "Descending").forEach { (value, label) ->
                Box(Modifier.weight(1f).height(48.dp).clip(Corners.card)
                    .selectable(ascending == value, role = Role.RadioButton, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (ascending == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
