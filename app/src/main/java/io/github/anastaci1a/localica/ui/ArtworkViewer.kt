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
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.anastaci1a.localica.library.ArtworkReader
import io.github.anastaci1a.localica.library.ArtworkSaveResult
import io.github.anastaci1a.localica.library.ArtworkSaver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun ArtworkViewer(uri: String, artist: String? = null, album: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saveScope = rememberCoroutineScope()
    val save = {
        saveScope.launch {
            val message = when (val r = ArtworkSaver.save(context.applicationContext, uri, artist, album)) {
                is ArtworkSaveResult.Saved -> "Saved to Downloads: ${r.name}"
                ArtworkSaveResult.NoArtwork -> "No artwork to save"
                is ArtworkSaveResult.Failed -> "Could not save artwork"
            }
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else Toast.makeText(context.applicationContext, "Storage permission is needed to save artwork", Toast.LENGTH_SHORT).show()
    }
    val onSave = {
        if (ArtworkSaver.needsLegacyPermission && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else save()
    }
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
                        cover.first != uri, onDismiss, tintUris = listOf(uri), onSave = onSave)
                }
            }
        }
    }
}

/** Only the controls respect safe insets; the centered image owns the entire page. */
@Composable
internal fun ArtworkViewerContent(artwork: ImageBitmap?, loading: Boolean, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, state: ArtworkViewerState = rememberArtworkViewerState(), tintUris: List<String>? = null, onSave: () -> Unit = {}) {
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
    Box(modifier.fillMaxSize().testTag("artwork-viewer")) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("artwork-viewer-viewport")
            .onSizeChanged { state.updateGeometry(Size(it.width.toFloat(), it.height.toFloat()), imageSize) }
            .then(if (artwork == null) Modifier else Modifier.artworkViewerGestures(state, scope, { reducedMotion }, stopSpeed))
            .semantics(mergeDescendants = true) {
                if (artwork != null) stateDescription = "${(state.transform.scale * 100f).roundToInt()}% zoom"
                customActions = if (artwork == null) emptyList() else buildList {
                    if (state.transform.scale < ArtworkMaximumZoom) add(CustomAccessibilityAction("Zoom in") { zoomIn(); true })
                    if (state.transform.scale > 1f) {
                        add(CustomAccessibilityAction("Zoom out") { zoomOut(); true })
                        add(CustomAccessibilityAction("Reset zoom") { reset(); true })
                    }
                }
            }) {
            ArtworkImage(artwork, loading, Modifier.fillMaxSize().graphicsLayer {
                scaleX = if (artwork == null) 1f else state.transform.scale
                scaleY = scaleX
                translationX = if (artwork == null) 0f else state.transform.offset.x
                translationY = if (artwork == null) 0f else state.transform.offset.y
            }, shape = RectangleShape, contentScale = ContentScale.Fit, emptyText = "No embedded artwork available", tintUris = tintUris)
        }
        Box(Modifier.matchParentSize().safeDrawingPadding().padding(16.dp)) {
            Box(Modifier.align(Alignment.TopStart)) {
                ActionIcon(AppIcon.ActionClose, "Close artwork", onClick = onDismiss)
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                ActionIcon(AppIcon.ActionSave, "Save artwork", enabled = artwork != null, onClick = onSave)
            }
        }
    }
}
