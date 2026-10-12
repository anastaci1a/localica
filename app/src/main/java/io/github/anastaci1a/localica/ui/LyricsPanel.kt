package io.github.anastaci1a.localica.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.LocalicaApplication
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.*

@Composable
internal fun rememberLyrics(track: Track?): LyricsResult? {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as? LocalicaApplication)?.lyrics ?: LyricsRepository(context) }
    val revision by ArtworkCache.revision.collectAsState()
    // A new key starts empty: never expose the preceding song's availability.
    return key(track?.id, track?.uri, track?.locations, revision) {
        val result by produceState<LyricsResult?>(null) { value = track?.let { repository.load(it, revision) } ?: LyricsResult() }
        result
    }
}

@Composable
internal fun LyricsPanel(track: Track?, positionMs: Long, reducedMotion: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, sourcesRequest: Int = 0, sourcesAnchor: GlassAnchor? = null) {
    val context = LocalContext.current
    val result = rememberLyrics(track)
    val preferences = remember { context.getSharedPreferences("lyric-sources", 0) }
    var chosen by rememberSaveable(track?.id) { mutableStateOf(preferences.getString(track?.id ?: "", null)) }
    val source = result?.sources?.firstOrNull { it.id == chosen } ?: result?.sources?.firstOrNull()
    var sourcesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sourcesRequest) { if (sourcesRequest > 0) sourcesOpen = true }
    Column(modifier.testTag("lyrics-panel")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (source != null) Box {
                GlassMenu(sourcesOpen, sourcesAnchor ?: rememberGlassAnchor(), { sourcesOpen = false }) {
                    result?.sources?.forEach { item -> DropdownMenuItem(text = {
                        Text(item.label + if (item.document.synced) " · synced" else "")
                    }, onClick = {
                        chosen = item.id; preferences.edit().putString(track?.id ?: "", item.id).apply(); sourcesOpen = false
                    }) }
                }
            }
        }
        androidx.compose.animation.Crossfade(source, Modifier.weight(1f),
            animationSpec = tween(if (reducedMotion) 0 else 300), label = "Song lyrics") { shown ->
            when {
                result == null -> Spacer(Modifier.fillMaxSize())
                shown == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("No local lyrics found", style = MaterialTheme.typography.titleMedium)
                    Text("Use embedded lyrics or a matching .lrc file in the song’s folder.", style = MaterialTheme.typography.bodySmall)
                    result?.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                else -> key(shown.id, shown.document) {
                    var shownPosition by remember { mutableLongStateOf(positionMs) }
                    SideEffect { if (shown == source) shownPosition = positionMs }
                    LyricsLines(shown.document, shownPosition, reducedMotion, onSeek, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
internal fun LyricsLines(document: ParsedLyrics, positionMs: Long, reducedMotion: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val context = LocalContext.current
    val touchExploration = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled }
    var following by rememberSaveable { mutableStateOf(!touchExploration) }
    LaunchedEffect(dragged) { if (dragged) following = false }
    val active = LrcParser.current(document.lines, positionMs)
    LaunchedEffect(active, following, document) {
        if (following && active >= 0) {
            // The top content padding already places the active line one third down.
            list.easeToItem(active, reducedMotion)
        }
    }
    Column(modifier) {
        document.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("lyric-lines")
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black,
                        0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }, contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight / 2)) {
                itemsIndexed(document.lines) { index, line ->
                    val selected = !document.synced || index == active
                    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        tween(if (reducedMotion) 0 else 350), label = "Lyric emphasis")
                    Text(line.text.ifEmpty { "♪" }, color = color,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().semantics { this.selected = document.synced && index == active }
                            .then(if (line.timeMs != null) Modifier.clickable(interactionSource = null, indication = null,
                                onClickLabel = "Seek to lyric") { onSeek(line.timeMs); following = true } else Modifier)
                            .padding(vertical = 12.dp, horizontal = 4.dp))
                }
            }
            androidx.compose.animation.AnimatedVisibility(!following && document.synced && active >= 0,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = androidx.compose.animation.fadeIn(tween(if (reducedMotion) 0 else 300)),
                exit = androidx.compose.animation.fadeOut(tween(if (reducedMotion) 0 else 300)) +
                    androidx.compose.animation.slideOutVertically(tween(if (reducedMotion) 0 else 300)) { it }) {
                val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
                ReturnToPositionButton(up = current?.let { it.offset < 0 } ?: (active < list.firstVisibleItemIndex),
                    label = "Return to current line", visible = !following) { following = true }
            }
        }
    }
}
