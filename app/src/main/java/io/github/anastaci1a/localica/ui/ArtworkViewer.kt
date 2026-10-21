package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.ArtworkReader
import kotlin.math.roundToInt

@Composable
internal fun ArtworkViewer(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState<Pair<String?, android.graphics.Bitmap?>>(null to null, uri) {
        value = uri to ArtworkReader(context).read(uri, 2048)
    }
    val visual = rememberPlayerVisual(uri)
    val backdrop = rememberFrostedBackdrop(uri)
    GlassDialog(onDismiss, GlassPlacement.Page) {
        Box(Modifier.fillMaxSize()) {
            PlayerBackdrop(visual.palette, Modifier.fillMaxSize().recordFrostedBackdrop(backdrop))
            CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalContentColor provides Color.White) {
                key(uri) {
                    ArtworkViewerContent(cover.second?.takeIf { cover.first == uri }?.asImageBitmap(),
                        cover.first != uri, onDismiss)
                }
            }
        }
    }
}

/** Only the controls respect safe insets; the centered image owns the entire page. */
@Composable
internal fun ArtworkViewerContent(artwork: ImageBitmap?, loading: Boolean, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, state: ArtworkViewerState = remember { ArtworkViewerState() }) {
    val scope = rememberCoroutineScope()
    val reducedMotion by rememberUpdatedState(LocalReducedMotion.current)
    val stopSpeed = with(LocalDensity.current) { 5.dp.toPx() }
    val imageSize = artwork?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
    SideEffect { state.updateGeometry(image = imageSize) }
    DisposableEffect(state) { onDispose { state.cancelContact() } }
    OnModalGestureCancellation { state.cancelContact() }
    LaunchedEffect(reducedMotion) { if (reducedMotion) state.stopMotion() }
    val zoomIn = controlActivation { state.zoomTo(state.transform.scale + 1f) }
    val zoomOut = controlActivation { state.zoomTo(state.transform.scale - 1f) }
    val reset = controlActivation { state.reset() }
    val zoomed by remember(state) { derivedStateOf { state.transform.scale > 1f } }
    Box(modifier.fillMaxSize().testTag("artwork-viewer")) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("artwork-viewer-viewport")
            .onSizeChanged { state.updateGeometry(Size(it.width.toFloat(), it.height.toFloat()), imageSize) }
            .then(if (artwork == null) Modifier else Modifier.artworkViewerGestures(state, scope, { reducedMotion }, stopSpeed))
            .semantics(mergeDescendants = true) {
                stateDescription = "${(state.transform.scale * 100f).roundToInt()}% zoom"
                customActions = if (artwork == null) emptyList() else buildList {
                    if (state.transform.scale < ArtworkMaximumZoom) add(CustomAccessibilityAction("Zoom in") { zoomIn(); true })
                    if (state.transform.scale > 1f) {
                        add(CustomAccessibilityAction("Zoom out") { zoomOut(); true })
                        add(CustomAccessibilityAction("Reset zoom") { reset(); true })
                    }
                }
            }) {
            ArtworkImage(artwork, loading, Modifier.fillMaxSize().graphicsLayer {
                scaleX = state.transform.scale; scaleY = state.transform.scale
                translationX = state.transform.offset.x; translationY = state.transform.offset.y
            }, shape = RectangleShape, contentScale = ContentScale.Fit, emptyText = "No embedded artwork available")
        }
        Box(Modifier.matchParentSize().safeDrawingPadding().padding(16.dp)) {
            Box(Modifier.align(Alignment.TopStart)) {
                ActionIcon(R.drawable.ic_close, "Close artwork", onClick = onDismiss)
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                ActionIcon(if (zoomed) R.drawable.ic_eye else R.drawable.ic_plus,
                    if (zoomed) "Reset zoom" else "Zoom in", enabled = artwork != null,
                    onClick = { if (zoomed) state.reset() else state.zoomTo(2f) })
            }
        }
    }
}
