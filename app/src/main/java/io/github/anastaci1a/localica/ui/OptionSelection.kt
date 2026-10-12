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
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (selected) SelectionPane(Modifier.matchParentSize())
        Text(label, Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, interactionSource = null,
            indication = null, onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SortDirection(ascending: Boolean, reducedMotion: Boolean, onSelect: (Boolean) -> Unit) {
    GlassChoices(listOf("Ascending", "Descending"), if (ascending) 0 else 1, horizontal = true) { onSelect(it == 0) }
}
