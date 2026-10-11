package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassActionButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiquidGlassSurface(modifier.heightIn(min = CompactControlSize)
        .clickable(role = Role.Button, interactionSource = null, indication = null, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
