package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

@Composable
internal fun ReturnToPositionButton(up: Boolean, label: String, onClick: () -> Unit) {
    FrostedSurface(Modifier.size(52.dp).clip(CircleShape)
        .semantics { stateDescription = if (up) "Scroll up" else "Scroll down" }
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick), shape = CircleShape) {
        Icon(painterResource(R.drawable.ic_arrow_down), label,
            Modifier.align(Alignment.Center).size(24.dp).graphicsLayer { rotationZ = if (up) 180f else 0f })
    }
}
