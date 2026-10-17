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
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.LocalicaApplication
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.*

@Composable
internal fun rememberLyrics(track: Track?): LyricsResult? {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as? LocalicaApplication)?.lyrics ?: LyricsRepository(context) }
    val revision by ArtworkCache.revision.collectAsState()
    return rememberLyrics(track, revision, repository::load)
}

@Composable
internal fun rememberLyrics(track: Track?, revision: Long, load: suspend (Track, Long) -> LyricsResult): LyricsResult? {
    // A new key starts empty: never expose the preceding song's availability.
    return key(track?.id, track?.uri, track?.locations, revision) {
        val result by produceState<LyricsResult?>(null) { value = track?.let { load(it, revision) } ?: LyricsResult() }
        result
    }
}

private sealed interface LyricsPresentation {
    data object Loading : LyricsPresentation
    data object Unavailable : LyricsPresentation
    data object Missing : LyricsPresentation
    data class Available(val source: LyricSource) : LyricsPresentation
}

@Composable
internal fun LyricsPanel(track: Track?, positionMs: Long, reducedMotion: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, sourcesRequest: Int = 0, sourcesAnchor: GlassAnchor? = null,
    sampledAtMs: Long = 0, playing: Boolean = false, speed: Float = 1f, delayMs: Int = 0, durationMs: Long? = null,
    contentHeight: Dp? = null, result: LyricsResult? = rememberLyrics(track),
    sourcesVisible: Boolean? = null, onSourcesDismiss: (() -> Unit)? = null,
    originPreparation: GlassOriginPreparation? = null) {
    val lyricClock by produceState(positionMs, positionMs, sampledAtMs, playing, speed, delayMs, durationMs) {
        do {
            value = lyricPosition(positionMs, sampledAtMs, android.os.SystemClock.elapsedRealtime(), playing, speed, delayMs, durationMs)
            if (!playing || sampledAtMs == 0L) break
            kotlinx.coroutines.delay(32)
        } while (true)
    }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("lyric-sources", 0) }
    var chosen by rememberSaveable(track?.id) { mutableStateOf(preferences.getString(track?.id ?: "", null)) }
    LaunchedEffect(result, chosen) {
        val legacy = chosen?.takeIf { it.endsWith(":plain") } ?: return@LaunchedEffect
        val original = legacy.removeSuffix(":plain")
        if (result?.sources?.any { it.id == original && it.document.synced } == true) {
            chosen = original
            preferences.edit().putString(track?.id ?: "", original).apply()
        }
    }
    val source = result?.sources?.firstOrNull { it.id == chosen } ?: result?.sources?.firstOrNull()
    // Crossfade retains outgoing content. Capture availability in its target so a completed
    // read cannot turn an outgoing loading layer into a false missing-lyrics indicator.
    val presentation = when {
        source != null -> LyricsPresentation.Available(source)
        result == null -> LyricsPresentation.Loading
        result.problem != null -> LyricsPresentation.Unavailable
        else -> LyricsPresentation.Missing
    }
    var requestedSources by remember(track?.id) { mutableStateOf(false) }
    var sourcesTrack by remember { mutableStateOf(track?.id) }
    LaunchedEffect(sourcesRequest) { if (sourcesRequest > 0) requestedSources = true }
    val sourcesOpen = (sourcesVisible ?: requestedSources) && source != null && sourcesTrack == track?.id
    val dismissSources = { requestedSources = false; onSourcesDismiss?.invoke(); Unit }
    LaunchedEffect(source == null, sourcesVisible, sourcesRequest) { if (source == null) dismissSources() }
    LaunchedEffect(track?.id) {
        if (sourcesTrack != track?.id) { sourcesTrack = track?.id; dismissSources() }
    }
    // Keep the outgoing menu composed until its shared return handoff finishes.
    // Retained labels are visual only: a changed track cannot receive an old choice.
    var menuSources by remember { mutableStateOf(emptyList<LyricSource>()) }
    var menuTrack by remember { mutableStateOf<String?>(null) }
    SideEffect { if (sourcesOpen) { menuSources = result?.sources.orEmpty(); menuTrack = track?.id } }
    GlassMenu(sourcesOpen, sourcesAnchor ?: rememberGlassAnchor(), dismissSources,
        originPreparation = originPreparation) {
        menuSources.forEach { item ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                GlassActionButton(R.drawable.ic_lyrics,
                    item.label + if (item.document.synced) " · synced" else "",
                    Modifier.fillMaxWidth(), enabled = sourcesOpen && track?.id == menuTrack, onClick = {
                        if (sourcesOpen && track?.id == menuTrack) {
                            chosen = item.id
                            preferences.edit().putString(track?.id ?: "", item.id).apply()
                            dismissSources()
                        }
                    })
            }
        }
    }
    // The empty decoration uses the stable background viewport. Only actual lyrics resize with metadata.
    Column(modifier.testTag("lyrics-panel")) {
        androidx.compose.animation.Crossfade(presentation, Modifier.weight(1f),
            animationSpec = tween(if (reducedMotion) 0 else 300), label = "Song lyrics") { shown ->
            when (shown) {
                LyricsPresentation.Loading, LyricsPresentation.Unavailable -> Box(Modifier.fillMaxSize())
                LyricsPresentation.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // A prior song's empty state is no longer authoritative once another read starts.
                    if (presentation == LyricsPresentation.Missing) Icon(androidx.compose.ui.res.painterResource(io.github.anastaci1a.localica.R.drawable.ic_lyrics),
                        "No local lyrics", Modifier.size(56.dp).testTag("empty-lyrics"), tint = LocalContentColor.current.copy(alpha = .22f))
                }
                is LyricsPresentation.Available -> key(shown.source.id, shown.source.document) {
                    var shownPosition by remember { mutableLongStateOf(lyricClock) }
                    SideEffect { if (shown == presentation) shownPosition = lyricClock }
                    LyricsLines(shown.source.document, shownPosition, reducedMotion, onSeek,
                        Modifier.fillMaxWidth().then(contentHeight?.let { Modifier.height(it) } ?: Modifier.fillMaxHeight()))
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
                .controlScrollParent(list, ControlScrollAxis.Vertical)
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
