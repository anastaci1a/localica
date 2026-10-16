package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset
import io.github.anastaci1a.localica.R
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassActionButton(icon: Int, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val opacity by animateControlStateFloat(if (enabled) 1f else .38f, "Glass action availability")
    LiquidGlassSurface(modifier.alpha(opacity).heightIn(min = CompactControlSize).ownButtonDrag(enabled)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null, onClick = onClick), pressFeedback = enabled) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            // A horizontal action strip measures its children with infinite width.
            // Bound the paragraph to its natural width before laying out text;
            // otherwise its paragraph can retain Infinity despite a finite glyph size.
            Text(label, Modifier.width(IntrinsicSize.Max), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Keep each action's natural text width and logical order. Explicit overflow
 * navigation preserves button-owned drags while keeping large-font actions reachable. */
@Composable
internal fun GlassActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shadowInset = glassShadowInset()
    fun move(forward: Boolean) {
        scope.launch {
            val target = (scroll.value + (if (forward) 1 else -1) * scroll.viewportSize)
                .coerceIn(0, scroll.maxValue)
            if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
    }
    Layout(modifier = modifier.fillMaxWidth(), content = {
        Row(Modifier.fillMaxWidth().nestedScroll(HorizontalGestureOwner)
            .shadowViewportOutset(shadowInset, horizontal = true).horizontalScroll(scroll)
            .padding(shadowInset), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        Row(Modifier.fillMaxWidth().then(if (scroll.maxValue == 0) Modifier.clearAndSetSemantics { } else Modifier),
            horizontalArrangement = Arrangement.End) {
            ActionIconButton(onClick = { move(false) }, enabled = scroll.canScrollBackward) {
                Icon(painterResource(R.drawable.ic_back), "Previous actions",
                    Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f })
            }
            ActionIconButton(onClick = { move(true) }, enabled = scroll.canScrollForward) {
                Icon(painterResource(R.drawable.ic_back), "More actions",
                    Modifier.graphicsLayer { scaleX = if (rtl) 1f else -1f })
            }
        }
    }) { children, constraints ->
        val strip = children[0].measure(constraints.copy(minHeight = 0))
        // horizontalScroll updates maxValue during measurement. Decide the footer
        // in this same pass, avoiding a delayed popup-envelope height change.
        val navigation = if (scroll.maxValue > 0) children[1].measure(
            glassActionNavigationConstraints(constraints, strip.height)) else null
        layout(strip.width, strip.height + (navigation?.height ?: 0)) {
            strip.placeRelative(0, 0)
            navigation?.placeRelative(0, strip.height)
        }
    }
}

/** offset preserves Infinity for a vertically scrolling parent; arithmetic on
 * that sentinel would produce an unrepresentable finite Compose constraint. */
internal fun glassActionNavigationConstraints(constraints: Constraints, stripHeight: Int): Constraints =
    constraints.copy(minHeight = 0).offset(vertical = -stripHeight)
