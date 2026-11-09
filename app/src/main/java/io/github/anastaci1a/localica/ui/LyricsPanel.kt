package io.github.anastaci1a.localica.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun LyricsPanel(track: Track?, positionMs: Long, reducedMotion: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, sourcesRequest: Int = 0, sourcesAnchor: GlassAnchor? = null,
    sampledAtMs: Long = 0, playing: Boolean = false, speed: Float = 1f, delayMs: Int = 0, durationMs: Long? = null,
    contentHeight: Dp? = null, contentOffset: Dp = 0.dp, result: LyricsResult? = rememberLyrics(track),
    sourcesVisible: Boolean? = null, onSourcesDismiss: (() -> Unit)? = null,
    originPreparation: GlassOriginPreparation? = null, contentAlpha: () -> Float = { 1f }) {
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
                GlassActionButton(AppIcon.ActionLyrics,
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
    // Documents and the empty decoration retain their content geometry inside one moving, faded viewport.
    LyricsBackdrop(presentation, modifier.testTag("lyrics-panel"), contentAlpha) {
        Column {
            val transition = updateTransition(presentation, label = "Song lyrics")
            transition.Crossfade(Modifier.weight(1f), animationSpec = tween(if (reducedMotion) 0 else 300)) { shown ->
                // Share Crossfade's transition clock so the sampled scene includes both
                // retained documents at exactly the opacity used by their visible parents.
                val sourceAlpha by transition.animateFloat(transitionSpec = { tween(if (reducedMotion) 0 else 300) },
                    label = "Lyric source alpha") { if (it == shown) 1f else 0f }
                val scene = requireNotNull(LocalLyricsBackdropScene.current)
                val ink = LocalContentColor.current
                when (shown) {
                    LyricsPresentation.Loading, LyricsPresentation.Unavailable -> Box(Modifier.fillMaxSize())
                    LyricsPresentation.Missing -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val stableHeight = contentHeight ?: maxHeight
                        Box(Modifier.fillMaxSize().testTag("lyrics-viewport")
                            .recordLyricsLayer(scene, { sourceAlpha }) { listOf(presentation, ink, stableHeight, contentOffset) }) {
                            Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                                .height(stableHeight).offset(y = contentOffset), contentAlignment = Alignment.Center) {
                                // A prior song's empty state is no longer authoritative once another read starts.
                                if (presentation == LyricsPresentation.Missing) Icon(appIconPainter(AppIcon.ActionLyrics),
                                    "No local lyrics", Modifier.size(56.dp).testTag("empty-lyrics"), tint = ink.copy(alpha = .22f))
                            }
                        }
                    }
                    is LyricsPresentation.Available -> key(shown.source.id, shown.source.document) {
                        // Read the clock while composing so a paused seek, which only moves it, still recomposes this
                        // scope; an outgoing document keeps the last position it presented.
                        var retainedPosition by remember { mutableLongStateOf(lyricClock) }
                        val shownPosition = if (shown == presentation) lyricClock else retainedPosition
                        SideEffect { if (shown == presentation) retainedPosition = lyricClock }
                        LyricsLines(shown.source.document, shownPosition, reducedMotion, onSeek,
                            Modifier.fillMaxSize(), sourceAlpha = { sourceAlpha },
                            contentHeight = contentHeight, contentOffset = contentOffset)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LyricsLines(document: ParsedLyrics, positionMs: Long, reducedMotion: Boolean, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, sourceAlpha: () -> Float = { 1f },
    contentHeight: Dp? = null, contentOffset: Dp = 0.dp) {
    val scene = LocalLyricsBackdropScene.current
    if (scene == null) {
        LyricsBackdrop(document, modifier) {
            LyricsLines(document, positionMs, reducedMotion, onSeek, Modifier.fillMaxSize(), sourceAlpha, contentHeight, contentOffset)
        }
        return
    }
    val context = LocalContext.current
    val touchExploration = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled }
    var following by rememberSaveable { mutableStateOf(!touchExploration) }
    val active = LrcParser.current(document.lines, positionMs)
    val selectedInk = MaterialTheme.colorScheme.onSurface
    val inactiveInk = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val lyricStyle = MaterialTheme.typography.headlineSmall
    val warningInk = LocalContentColor.current
    val warningStyle = MaterialTheme.typography.bodySmall
    BoxWithConstraints(modifier) {
        val stableHeight = contentHeight ?: maxHeight
        // One "current line" anchor (#717): a line rests with its centre on the centre of the lyric-lines container.
        // The list opens already there (no opening scroll), the follow glide lands there, and the return arrow
        // points from there. Row heights come from the laid-out item when it is visible and otherwise from the
        // same text layout the rows use, so a wrapped line is centred on its real height from its first frame.
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val anchors = remember(document, lyricStyle, constraints.maxWidth, density, measurer) {
            LyricLineAnchors(document.lines, lyricStyle, constraints.maxWidth, measurer, density)
        }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = active.coerceAtLeast(0),
            initialFirstVisibleItemScrollOffset = if (active >= 0) anchors.offset(anchors.height(active, emphasised = true)) else 0)
        val dragged by list.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragged) { if (dragged) following = false }
        fun anchor(index: Int) = anchors.target(index, list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size)
        LaunchedEffect(active, following, document) {
            if (following && active >= 0) {
                val initial = anchor(active)
                list.easeToItem(active, reducedMotion, offset = initial.offset, target = { anchor(active) })
            }
        }
        Box(Modifier.fillMaxSize().testTag("lyrics-viewport")
            .recordLyricsLayer(scene, sourceAlpha) {
                listOf(document, active, selectedInk, inactiveInk, lyricStyle, warningInk, warningStyle,
                    stableHeight, contentOffset, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                    list.isScrollInProgress)
            }) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .height(stableHeight).offset(y = contentOffset)) {
                document.warning?.let { Text(it, style = warningStyle) }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Half the container less half a single-line row above and below: the first and last single-line
                    // lyrics rest centred at the list bounds, and every row is centred when scrolled anchors.offset past it.
                    val bound = with(density) { ((constraints.maxHeight - anchors.single) / 2).coerceAtLeast(0).toDp() }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("lyric-lines")
                        .controlScrollParent(list, ControlScrollAxis.Vertical),
                        contentPadding = PaddingValues(top = bound, bottom = bound)) {
                        itemsIndexed(document.lines) { index, line ->
                            val selected = !document.synced || index == active
                            // Only the emphasis is per-line state. The ink is the shared theme value of this
                            // composition, so every line follows the lyrics theme ease together and a line
                            // composed mid-ease is the same colour as its neighbours (#564).
                            val emphasis by animateFloatAsState(if (selected) 1f else 0f,
                                tween(if (reducedMotion) 0 else 350), label = "Lyric emphasis")
                            Text(line.text.ifEmpty { LyricPlaceholder }, color = androidx.compose.ui.graphics.lerp(inactiveInk, selectedInk, emphasis),
                                style = lyricStyle, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth().semantics { this.selected = document.synced && index == active }
                                    .then(if (line.timeMs != null) Modifier.clickable(interactionSource = null, indication = null,
                                        onClickLabel = "Seek to lyric") { onSeek(line.timeMs); following = true } else Modifier)
                                    .padding(vertical = LyricRowPadding, horizontal = LyricRowInset))
                        }
                    }
                }
            }
        }
        // #715: the arrow shows only while a return would move the list. A manual scroll resting at the list's measured
        // first/last bound that is where the return lands anyway (the bound centres the current line, or clamps the
        // return) hides it exactly as if the user had returned; it shows again as soon as a real return would move it.
        val tolerance = with(density) { 1.dp.roundToPx() }
        val returnMoves by remember(list, anchors, active, tolerance) { derivedStateOf {
            val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            lyricReturnMoves(current?.let { it.offset + anchors.offset(it.size) },
                list.canScrollBackward, list.canScrollForward, tolerance)
        } }
        androidx.compose.animation.AnimatedVisibility(!following && returnMoves && document.synced && active >= 0,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = androidx.compose.animation.fadeIn(tween(if (reducedMotion) 0 else 300)),
            exit = androidx.compose.animation.fadeOut(tween(if (reducedMotion) 0 else 300)) +
                androidx.compose.animation.slideOutVertically(tween(if (reducedMotion) 0 else 300)) { it }) {
            val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            CompositionLocalProvider(LocalFrostedBackdrop provides scene.source) {
                // Up when the current line sits above its anchor, so the return glides in the direction of the same geometry.
                ReturnToPositionButton(up = current?.let { it.offset < -anchors.offset(it.size) } ?: (active < list.firstVisibleItemIndex),
                    label = "Return to current line", visible = !following && returnMoves) { following = true }
            }
        }
    }
}

/** #715: whether returning to the current line would move the list. [delta] is the scroll in px (positive toward later
 *  lines) that centres the current line, or null when that line is not laid out. Away from both measured bounds the
 *  arrow keeps its behaviour; at a bound, a return the bound clamps, or one of at most [tolerance] px, would not move it. */
internal fun lyricReturnMoves(delta: Int?, canScrollBackward: Boolean, canScrollForward: Boolean, tolerance: Int): Boolean {
    if (delta == null || (canScrollBackward && canScrollForward)) return true
    val clamped = (delta > 0 && !canScrollForward) || (delta < 0 && !canScrollBackward)
    return !clamped && kotlin.math.abs(delta) > tolerance
}

private const val LyricPlaceholder = "♪"
private val LyricRowPadding = 12.dp
private val LyricRowInset = 4.dp

/** The current-line anchor geometry of [LyricsLines] (#717, shared by the opening position, the follow glide and the
 *  return arrow): rows are text plus [LyricRowPadding] above and below; the list's content padding is half the container
 *  less half a single-line row, so a row of height h is centred when scrolled (h - single) / 2 past that padding. */
internal class LyricLineAnchors(private val lines: List<LyricLine>, private val style: TextStyle, width: Int,
    private val measurer: TextMeasurer, density: Density) {
    private val textWidth = (width - 2 * with(density) { LyricRowInset.roundToPx() }).coerceAtLeast(0)
    private val rowPadding = 2 * with(density) { LyricRowPadding.roundToPx() }
    private fun measure(text: String, weight: FontWeight) = measurer.measure(text, style.copy(fontWeight = weight),
        constraints = Constraints(maxWidth = textWidth)).size.height + rowPadding
    /** A single-line row (every row is at least this tall). */
    val single = measure(LyricPlaceholder, FontWeight.Medium)
    /** The row height of [index] as its text lays out at the current weight. */
    fun height(index: Int, emphasised: Boolean) =
        measure(lines[index].text.ifEmpty { LyricPlaceholder }, if (emphasised) FontWeight.Bold else FontWeight.Medium)
    /** The scroll offset past the content padding that centres a row of [height]. */
    fun offset(height: Int) = (height - single) / 2
    /** The scroll target centring row [index], from its laid-out height when known. */
    fun target(index: Int, laidOut: Int?) = ListScrollTarget(index, offset(laidOut ?: height(index, emphasised = true)))
}
