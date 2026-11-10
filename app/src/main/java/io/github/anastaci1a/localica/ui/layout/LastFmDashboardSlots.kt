package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.lastfm.LastFmProgress
import io.github.anastaci1a.localica.ui.spec.ControlSize

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.Radius
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

/** Last.fm dashboard geometry; drawing has no input or animation ownership. */
@Composable
internal fun LastFmDashboardHeader(progress: LastFmProgress?, syncing: Boolean, content: @Composable () -> Unit) {
    val spaces = StockUiSpec.space
    val percent = progress?.percent ?: 0
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    Row(Modifier.fillMaxWidth().padding(spaces.getValue(Space.Comfortable)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Comfortable))) {
        Box(Modifier.size(StockUiSpec.control.getValue(ControlSize.NavigationPill)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = spaces.getValue(Space.Tight).toPx()
                val inset = stroke / 2
                drawArc(track, -90f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(accent, -90f, 360f * percent / 100, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("$percent%", Modifier.testTag("lastfm-sync-percent"), style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Tight))) {
            Text(if (syncing) "Syncing…" else "Last.fm archive", style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun LastFmStatsGrid(stats: List<Pair<String, String>>) {
    val spaces = StockUiSpec.space
    Column(Modifier.fillMaxWidth().padding(spaces.getValue(Space.Comfortable)),
        verticalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Base))) {
        stats.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spaces.getValue(Space.Compact))) {
                repeat(3) { index ->
                    Column(Modifier.weight(1f)) {
                        row.getOrNull(index)?.let { (label, value) ->
                            Text(value, style = MaterialTheme.typography.titleLarge)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

internal data class LastFmMatchSegment(val id: String, val label: String, val count: Int, val color: Color)

/** Exact ratios are inert; the labelled shared legend rows own all input. */
@Composable
internal fun LastFmMatchingBar(segments: List<LastFmMatchSegment>) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(StockUiSpec.space.getValue(Space.Base)).clip(CircleShape).testTag("lastfm-matching-strip")) {
        val total = segments.sumOf { it.count.toLong().coerceAtLeast(0) }
        var start = 0f
        segments.forEach { segment ->
            val width = if (total == 0L) 0f else size.width * segment.count.coerceAtLeast(0).toFloat() / total
            drawRect(segment.color, Offset(if (rtl) size.width - start - width else start, 0f), Size(width, size.height))
            start += width
        }
    }
}
