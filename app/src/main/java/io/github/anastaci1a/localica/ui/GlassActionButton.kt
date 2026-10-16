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
import androidx.compose.ui.unit.constrainWidth
import io.github.anastaci1a.localica.R
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassActionButton(icon: Int, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val opacity by animateControlStateFloat(if (enabled) 1f else .38f, "Glass action availability")
    LiquidGlassSurface(modifier.alpha(opacity).heightIn(min = CompactControlSize).ownButtonDrag(enabled)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = null, onClick = onClick), pressFeedback = enabled) {
        GlassActionButtonFace(icon, label)
    }
}

@Composable
internal fun GlassActionButtonFace(icon: Int, label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp))
        // Scrolling action strips measure with infinite width. Bound both the
        // live and measurement paragraphs to the same natural text width.
        Text(label, Modifier.width(IntrinsicSize.Max), style = MaterialTheme.typography.labelLarge)
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

/** Inert counterpart of the scrolling strip, including its conditional footer. */
@Composable
internal fun GlassActionRowMeasurement(content: @Composable RowScope.() -> Unit) {
    val inset = glassShadowInset()
    Layout(content = {
        Row(Modifier.shadowViewportOutset(inset, horizontal = true).padding(inset), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        // The live footer uses standard IconButtons: a 40dp container with the
        // same Material minimum-interactive constraint, without input or effects.
        Box(Modifier.minimumInteractiveComponentSize().size(40.dp))
    }, measurePolicy = object : androidx.compose.ui.layout.MeasurePolicy {
        override fun androidx.compose.ui.layout.MeasureScope.measure(children: List<androidx.compose.ui.layout.Measurable>,
            constraints: Constraints): androidx.compose.ui.layout.MeasureResult {
            val strip = children[0].measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0))
            val width = constraints.constrainWidth(strip.width)
            val footer = if (strip.width > width) children[1].measure(glassActionNavigationConstraints(constraints, strip.height)) else null
            return layout(width, strip.height + (footer?.height ?: 0)) { }
        }
        override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int) =
            children[0].maxIntrinsicWidth(height)
        override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int) =
            children[0].minIntrinsicWidth(height)
    })
}
