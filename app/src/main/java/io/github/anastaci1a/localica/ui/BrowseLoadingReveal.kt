package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.browse.*
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Placeholder and artwork call the same radius function with the same eased alpha. */
internal fun browseLoadingArtCorner(roundedPx: Float, sizePx: Float, artist: Boolean,
    artistOnly: Boolean, phase: Float): Float = if (!artist) roundedPx else {
    val start = if (artistOnly) sizePx / 2f else roundedPx.coerceAtMost(sizePx / 2f)
    start + (sizePx / 2f - start) * phase.coerceIn(0f, 1f)
}

/** Distance, not item count/density, determines speed. Capture positions before
 * contraction so changing row heights cannot move an unrevealed deadline. */
internal object BrowseLoadingMotion {
    const val WaveDpPerSecond = 1800f
    const val FadeMillis = 300f
    const val ContractMillis = 240f
    fun delayMillis(xDp: Float, yDp: Float): Float =
        hypot(xDp.coerceAtLeast(0f), yDp.coerceAtLeast(0f)) * 1000f / WaveDpPerSecond
    fun fade(elapsedMillis: Float, delayMillis: Float): Float =
        ease((elapsedMillis - delayMillis) / FadeMillis)
    fun reservation(elapsedMillis: Float, rowFinishedMillis: Float): Float =
        1f - ease((elapsedMillis - rowFinishedMillis) / ContractMillis)
    private fun ease(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
}

@Stable
internal class BrowseLoadingReveal(private val columns: Int, initiallyComplete: Boolean) {
    val elapsed = Animatable(0f)
    var complete by mutableStateOf(initiallyComplete)
        private set
    var interruptedAt by mutableStateOf<Float?>(null)
        private set
    private val cells = mutableMapOf<Int, BrowseLoadingCell>()
    private val rowEnds = mutableMapOf<Int, MutableFloatState>()
    fun cell(index: Int): BrowseLoadingCell = cells.getOrPut(index) { BrowseLoadingCell(this, index) }
    fun finish() { complete = true }
    fun revealImmediately() { if (!complete && interruptedAt == null) interruptedAt = elapsed.value }
    fun placed(index: Int, delay: Float) {
        val row = rowEnds.getOrPut(index / columns) { mutableFloatStateOf(0f) }
        row.floatValue = maxOf(row.floatValue, delay + BrowseLoadingMotion.FadeMillis)
    }
    fun rowFinished(index: Int): Float = rowEnds[index / columns]?.floatValue ?: Float.POSITIVE_INFINITY
}

@Stable
internal class BrowseLoadingCell(private val owner: BrowseLoadingReveal, val index: Int) {
    var delay by mutableFloatStateOf(Float.POSITIVE_INFINITY)
        private set
    fun place(xDp: Float, yDp: Float) {
        if (!delay.isFinite()) {
            delay = BrowseLoadingMotion.delayMillis(xDp, yDp)
            owner.placed(index, delay)
        }
    }
    val alpha: Float get() = if (owner.complete || owner.interruptedAt != null) 1f else BrowseLoadingMotion.fade(owner.elapsed.value, delay)
    // Fade frames only redraw. Remeasure starts when the row's reserved height actually changes.
    private val reserved = derivedStateOf(structuralEqualityPolicy()) {
        if (owner.complete) 0f else {
            val rowEnd = owner.rowFinished(index)
            val interrupted = owner.interruptedAt
            if (interrupted == null) BrowseLoadingMotion.reservation(owner.elapsed.value, rowEnd)
            else BrowseLoadingMotion.reservation(interrupted, rowEnd) *
                BrowseLoadingMotion.reservation(owner.elapsed.value, interrupted)
        }
    }
    val reservation: Float get() = reserved.value
}

@Composable
internal fun rememberBrowseLoadingReveal(key: Any?, ready: Boolean, cached: Boolean, columns: Int,
    widthDp: Float, heightDp: Float, reducedMotion: Boolean, geometryKey: Any? = null): BrowseLoadingReveal {
    val reveal = remember(key) { BrowseLoadingReveal(columns, cached || reducedMotion) }
    // A geometry/pinch/rotation change must not reuse old positional deadlines. Finishing
    // an already mounted wave preserves its live rows instead of starting them over.
    var originalGeometry by remember(reveal) { mutableStateOf(Triple(columns, widthDp, heightDp) to geometryKey) }
    if (originalGeometry != (Triple(columns, widthDp, heightDp) to geometryKey)) {
        reveal.finish()
        originalGeometry = Triple(columns, widthDp, heightDp) to geometryKey
    }
    LaunchedEffect(reveal, ready, reducedMotion, reveal.complete, reveal.interruptedAt) {
        if (reducedMotion) reveal.finish()
        if (ready && !reveal.complete) {
            withFrameNanos { } // Collect the first laid-out row positions before advancing.
            val target = reveal.interruptedAt?.plus(BrowseLoadingMotion.ContractMillis) ?:
                (BrowseLoadingMotion.delayMillis(widthDp, heightDp) + BrowseLoadingMotion.FadeMillis + BrowseLoadingMotion.ContractMillis)
            val remaining = (target - reveal.elapsed.value).roundToInt().coerceAtLeast(0)
            reveal.elapsed.animateTo(target, tween(remaining, easing = LinearEasing))
            reveal.finish()
        }
    }
    return reveal
}

/** All loading art and metadata in this viewport use exactly one draw-only clock. */
internal val LocalLoadingShimmer = compositionLocalOf<State<Float>?> { null }
internal val LocalLoadingShimmerActivity = staticCompositionLocalOf<LoadingShimmerActivity?> { null }

/** Visible pending artwork keeps the existing viewport clock alive after catalog reveal. */
@Stable
internal class LoadingShimmerActivity {
    var count by mutableIntStateOf(0)
        private set
    fun acquire() { count++ }
    fun release() { count-- }
}

@Composable
internal fun rememberBrowseLoadingShimmer(active: Boolean, reducedMotion: Boolean): State<Float>? =
    if (active && !reducedMotion) rememberInfiniteTransition(label = "Library loading").animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Library shimmer") else null

@Composable
internal fun BrowseLoadingShape(modifier: Modifier, cornerPx: () -> Float, alpha: () -> Float = { 1f }) {
    Box(modifier.graphicsLayer {
        this.alpha = alpha()
        shape = RoundedCornerShape(cornerPx())
        clip = true
    }) { ArtworkPlaceholder(true, Modifier.fillMaxSize(), showIcon = false) }
}

/** Uses the same metrics as real rows, including artwork-free and trailing-info lists. */
@Composable
internal fun BrowseLoadingItem(style: BrowseStyle, geometry: BrowseCellGeometry, showInformation: Boolean, artistOnly: Boolean = false) {
    val density = LocalDensity.current
    val corner = remember { mutableFloatStateOf(with(density) { 8.dp.toPx() }) }
    val titleHeight = with(density) { style.browseTitleLine(geometry.columns).sp.roundToPx() } *
        if (style.presentation == Presentation.List) 1 else 2
    val infoLine = with(density) { MaterialTheme.typography.bodySmall.lineHeight.roundToPx() }
    val infoHeight = if (showInformation) infoLine * if (style.presentation == Presentation.List) 1 else 2 else 0
    Layout(modifier = Modifier.fillMaxWidth().testTag("browse-skeleton-item").padding(horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp), content = {
        Box(Modifier.graphicsLayer {
            shape = if (artistOnly) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(corner.floatValue)
            clip = true
        }.testTag("browse-skeleton-art")) {
            BrowseLoadingShape(Modifier.fillMaxSize(), { 0f })
        }
        BrowseLoadingShape(Modifier.testTag("browse-skeleton-title"), { corner.floatValue })
        BrowseLoadingShape(Modifier.testTag("browse-skeleton-info"), { corner.floatValue })
    }) { children, constraints ->
        val width = (constraints.maxWidth - geometry.contentInset.dp.roundToPx() * 2).coerceAtLeast(0)
        val metrics = BrowseItemMetrics(style, geometry.columns, width, this.density, false)
        corner.floatValue = metrics.artCorner
        val infoWidth = metrics.loadingInformationWidth(showInformation)
        val titleWidth = metrics.titleWidth(infoWidth)
        // Gallery's visual placeholders describe one text line; keep the existing
        // two-line row reservation separate so real multiline content is never clipped.
        val blankTitleHeight = if (metrics.gallery) titleHeight / metrics.lines else titleHeight
        val placement = metrics.place(blankTitleHeight, infoWidth, if (metrics.gallery) infoLine.takeIf { showInformation } ?: 0 else infoHeight)
        val reservedHeight = metrics.place(titleHeight, infoWidth, infoHeight).height
        val art = children[0].measure(Constraints.fixed(metrics.art, metrics.art))
        val title = children[1].measure(Constraints.fixed(titleWidth, blankTitleHeight))
        val info = children[2].measure(Constraints.fixed(infoWidth, if (showInformation) infoLine else 0))
        layout(constraints.maxWidth, reservedHeight) {
            art.placeRelative(placement.artX, placement.artY)
            title.placeRelative(placement.titleX, placement.titleY)
            info.placeRelative(placement.infoX, placement.infoY)
        }
    }
}

@Composable
internal fun BrowseLoadingViewport(style: BrowseStyle, geometry: BrowseCellGeometry,
    widthPx: Int, heightPx: Int, topInset: androidx.compose.ui.unit.Dp, showInformation: Boolean, artistOnly: Boolean = false) {
    val density = LocalDensity.current
    val count = with(density) {
        val metrics = BrowseItemMetrics(style, geometry.columns, geometry.artworkWidth(widthPx, this.density), this.density, false)
        val titleHeight = style.browseTitleLine(geometry.columns).sp.roundToPx() * metrics.lines
        val infoHeight = if (showInformation) MaterialTheme.typography.bodySmall.lineHeight.roundToPx() * metrics.lines else 0
        val pitch = metrics.place(titleHeight, 0, infoHeight).height + geometry.cardVerticalInset(style).dp.roundToPx() * 2
        (ceil(heightPx.toFloat() / pitch.coerceAtLeast(1)).toInt() + 1) * geometry.columns
    }
    LazyVerticalGrid(columns = GridCells.Fixed(geometry.columns), userScrollEnabled = false,
        modifier = Modifier.fillMaxSize().testTag("browse-loading").semantics(mergeDescendants = true) { contentDescription = "Loading library" },
        contentPadding = PaddingValues(start = geometry.gridInset.dp, end = geometry.gridInset.dp, top = topInset)) {
        items(count) { BrowseLoadingItem(style, geometry, showInformation, artistOnly) }
    }
}
