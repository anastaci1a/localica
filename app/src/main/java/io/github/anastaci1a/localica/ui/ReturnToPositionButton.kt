package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.R

internal val LocalReturnPolicyObserver = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun ReturnToPositionButton(up: Boolean, label: String, visible: Boolean = true, onClick: () -> Unit) {
    val policyObserver = LocalReturnPolicyObserver.current
    if (policyObserver != null) SideEffect { policyObserver("button visible=$visible") }
    var direction by remember { mutableStateOf(up) }
    SideEffect { if (visible) direction = up }
    val activate = controlActivation(enabled = visible, onClick = onClick)
    val input = if (visible) Modifier.ownButtonDrag()
        .semantics { stateDescription = if (direction) "Scroll up" else "Scroll down" }
        .clickable(role = Role.Button, onClickLabel = label, onClick = activate)
        else Modifier.clearAndSetSemantics { }
    LiquidGlassSurface(Modifier.size(CompactControlSize).clip(CircleShape).then(input), pressFeedback = visible) {
        Icon(painterResource(R.drawable.ic_arrow_down), label,
            Modifier.align(Alignment.Center).size(24.dp).graphicsLayer { rotationZ = if (direction) 180f else 0f })
    }
}

/** Retain exit drawing without retaining its click, drag ownership or accessible action. */
@Composable
internal fun FloatingReturnToPositionButton(up: Boolean, label: String, visible: Boolean,
    reducedMotion: Boolean, modifier: Modifier = Modifier, returning: Boolean = false, onClick: () -> Unit) {
    val motion = rememberFloatingControlMotion(visible, reducedMotion)
    val rise = with(LocalDensity.current) { FloatingControlRise.toPx() }
    val retained = motion.retained
    val policyObserver = LocalReturnPolicyObserver.current
    if (policyObserver != null) SideEffect { policyObserver("floating visible=$visible retained=$retained reducedRequest=$reducedMotion returning=$returning") }
    if (retained && !returning) Box(modifier.testTag("floating-return-control")
        .offset { IntOffset(0, motion.offsetY(rise).roundToInt()) }
        .graphicsLayer {
            alpha = motion.alpha
            val scale = .92f + .08f * motion.progress
            scaleX = scale; scaleY = scale
        }) {
        val interactive = motion.interactive
        if (policyObserver != null) SideEffect { policyObserver("floating-input visible=$visible interactive=$interactive") }
        ReturnToPositionButton(up, label, visible = interactive, onClick = onClick)
    }
}
