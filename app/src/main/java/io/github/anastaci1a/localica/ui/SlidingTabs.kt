package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.MainTab

@Composable
internal fun SlidingTabs(tabs: List<MainTab>, active: MainTab, page: Float, onSelect: (MainTab) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fits = maxWidth >= 56.dp * tabs.size
        val width = if (fits) maxWidth / tabs.size else 56.dp
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxHeight().nestedScroll(HorizontalGestureOwner).horizontalScroll(scroll)) {
            Canvas(Modifier.width(width * tabs.size).fillMaxHeight()) {
                val inset = 4.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = .09f),
                    topLeft = Offset(width.toPx() * page.coerceIn(0f, tabs.lastIndex.toFloat()) + inset, inset),
                    size = Size(width.toPx() - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius((size.height - 2 * inset) / 2))
            }
            Row {
                tabs.forEach { tab ->
                    Column(Modifier.width(width).padding(4.dp).fillMaxHeight().clip(CircleShape)
                        .selectable(active == tab, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null, role = Role.Tab, onClick = { onSelect(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(painterResource(tab.icon()), tab.label, Modifier.size(24.dp),
                            tint = if (active == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tab.label, maxLines = 1, style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        LaunchedEffect(active, width, reducedMotion) {
            val index = tabs.indexOf(active)
            if (!fits && index >= 0) {
                val target = (scroll.maxValue * index.toFloat() / tabs.lastIndex.coerceAtLeast(1)).toInt()
                if (reducedMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
            }
        }
    }
}
