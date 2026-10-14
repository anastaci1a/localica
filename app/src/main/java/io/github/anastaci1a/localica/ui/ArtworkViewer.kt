package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.ArtworkReader

@Composable
internal fun ArtworkViewer(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState<Pair<String?, android.graphics.Bitmap?>>(null to null, uri) {
        value = uri to ArtworkReader(context).read(uri, 2048)
    }
    val visual = rememberPlayerVisual(uri)
    val backdrop = rememberFrostedBackdrop(uri)
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    GlassDialog(onDismiss, GlassPlacement.Page) {
        Box(Modifier.fillMaxSize()) {
            PlayerBackdrop(visual.palette, Modifier.fillMaxSize().recordFrostedBackdrop(backdrop))
            CompositionLocalProvider(LocalFrostedBackdrop provides backdrop, LocalContentColor provides androidx.compose.ui.graphics.Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_close, "Close artwork", onClick = onDismiss)
                    GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_eye, "Reset zoom", onClick = { scale = 1f; offset = Offset.Zero })
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_plus, "Zoom in", onClick = { scale = (scale + 1f).coerceAtMost(6f) })
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).graphicsLayer { clip = true }, contentAlignment = Alignment.Center) {
                    val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        val limitX = width * (scale - 1f) / 2; val limitY = height * (scale - 1f) / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                    }
                    ArtworkImage(cover.second?.takeIf { cover.first == uri }?.asImageBitmap(), cover.first != uri,
                        Modifier.fillMaxSize().transformable(transform)
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, emptyText = "No embedded artwork available")
                }
            }
            }
        }
    }
}
