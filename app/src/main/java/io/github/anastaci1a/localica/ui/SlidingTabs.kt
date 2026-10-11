package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.anastaci1a.localica.navigation.MainTab
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun magneticTabPosition(raw: Float): Float {
    val center = raw.roundToInt().toFloat()
    val delta = raw - center
    return center + delta * (.35f + 1.3f * abs(delta))
}

@Composable
internal fun SlidingTabs(tabs: List<MainTab>, active: MainTab, page: Float, source: FrostedBackdrop? = LocalFrostedBackdrop.current, onSelect: (MainTab) -> Unit) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lens = remember { Animatable(page) }
    var dragging by remember { mutableStateOf(false) }
    var raw by remember { mutableFloatStateOf(page) }
    var chosen by remember { mutableIntStateOf(tabs.indexOf(active).coerceAtLeast(0)) }
    val selected by rememberUpdatedState(onSelect)
    LaunchedEffect(page, dragging) {
        if (!dragging) {
            chosen = tabs.indexOf(active).coerceAtLeast(0)
            if (reduced) lens.snapTo(page) else lens.animateTo(page, spring(.65f, 500f))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fits = maxWidth >= 68.dp * tabs.size
        val width = if (fits) maxWidth / tabs.size else 68.dp
        val pixels = with(density) { width.toPx() }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxHeight().horizontalScroll(scroll, enabled = !fits && !dragging)
            .draggable(rememberDraggableState { delta ->
                raw = (raw + delta / pixels).coerceIn(0f, tabs.lastIndex.toFloat())
                val position = magneticTabPosition(raw)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { lens.snapTo(position) }
                val candidate = position.roundToInt().coerceIn(tabs.indices)
                if (candidate != chosen && abs(position - chosen) > .52f) {
                    chosen = candidate; selected(tabs[candidate])
                }
            }, Orientation.Horizontal, onDragStarted = {
                lens.stop(); raw = lens.value; dragging = true
            }, onDragStopped = {
                dragging = false
                lens.animateTo(chosen.toFloat(), spring(.6f, 450f))
            })) {
            SelectionLens(Modifier.offset(x = width * lens.value + 4.dp, y = 4.dp)
                .width(width - 8.dp).height(NavigationPillHeight - 8.dp), lens, chosen.toFloat(), source)
            @Composable fun Labels(color: Color, interactive: Boolean) {
                Row(Modifier.width(width * tabs.size).fillMaxHeight().then(if (interactive) Modifier else
                    Modifier.clearAndSetSemantics {}.drawWithContent {
                        val left = pixels * lens.value + 4.dp.toPx()
                        val path = Path().apply { addRoundRect(RoundRect(left, 4.dp.toPx(),
                            left + pixels - 8.dp.toPx(), size.height - 4.dp.toPx(), CornerRadius(size.height / 2))) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    })) {
                    tabs.forEach { tab ->
                        Column(Modifier.width(width).padding(4.dp).fillMaxHeight()
                            .then(if (interactive) Modifier.selectable(active == tab,
                                interactionSource = null, indication = null, role = Role.Tab, onClick = { selected(tab) }) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(painterResource(tab.icon()), if (interactive) tab.label else null, Modifier.size(29.dp), tint = color)
                            Text(tab.label, color = color, maxLines = 1, style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium), overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Labels(MaterialTheme.colorScheme.onSurfaceVariant, true)
            Labels(NavigationAccent, false)
        }
        LaunchedEffect(active, width, reduced) {
            val index = tabs.indexOf(active)
            if (!fits && index >= 0 && !dragging) {
                val target = (scroll.maxValue * index.toFloat() / tabs.lastIndex.coerceAtLeast(1)).toInt()
                if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target)
            }
        }
    }
}
