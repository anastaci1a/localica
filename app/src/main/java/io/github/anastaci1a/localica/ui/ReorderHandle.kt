package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

@Composable
internal fun ReorderHandle(label: String, onMove: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val move by rememberUpdatedState(onMove)
    androidx.compose.foundation.layout.Box(modifier.size(48.dp).semantics {
        contentDescription = label
        customActions = if (enabled) listOf(CustomAccessibilityAction("Move up") { move(-1); true },
            CustomAccessibilityAction("Move down") { move(1); true })
        else emptyList()
    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Icon(painterResource(R.drawable.ic_drag), null, Modifier.size(24.dp))
    }
}
