package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.CoverImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The square is fixed; only the source image moves and zooms beneath it. */
@Composable
internal fun ArtworkCrop(uri: String, onDismiss: () -> Unit, onCropped: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var zoom by rememberSaveable(uri) { mutableFloatStateOf(1f) }
    var centerX by rememberSaveable(uri) { mutableFloatStateOf(.5f) }
    var centerY by rememberSaveable(uri) { mutableFloatStateOf(.5f) }
    val load = rememberCropImage(uri)
    val bitmap = (load as? CropImageState.Ready)?.bitmap
    fun constrain(image: Bitmap) {
        val half = minOf(image.width, image.height) / zoom / 2f
        centerX = centerX.coerceIn(half / image.width, 1f - half / image.width)
        centerY = centerY.coerceIn(half / image.height, 1f - half / image.height)
    }
    GlassDialog({ if (!saving) onDismiss() }, GlassPlacement.Page) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Crop artwork", style = MaterialTheme.typography.titleLarge)
            Text("Move and pinch the image to fill the square.", style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                bitmap?.let { image ->
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().testTag("artwork-crop")
                        .pointerInput(image, saving) {
                            if (!saving) detectTransformGestures { centroid, pan, scale, _ ->
                                val oldScale = size.width / minOf(image.width, image.height).toFloat() * zoom
                                val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
                                zoom = (zoom * scale).coerceIn(1f, 8f)
                                val newScale = size.width / minOf(image.width, image.height).toFloat() * zoom
                                centerX += (pivot.x / oldScale - (pivot.x + pan.x) / newScale) / image.width
                                centerY += (pivot.y / oldScale - (pivot.y + pan.y) / newScale) / image.height
                                constrain(image)
                            }
                        }) {
                        val scale = size.width / minOf(image.width, image.height) * zoom
                        val left = size.width / 2 - centerX * image.width * scale
                        val top = size.height / 2 - centerY * image.height * scale
                        drawImage(image.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                        for (third in 1..2) {
                            val at = size.width * third / 3
                            drawLine(Color.White.copy(alpha = .45f), Offset(at, 0f), Offset(at, size.height), 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = .45f), Offset(0f, at), Offset(size.width, at), 1.dp.toPx())
                        }
                    }
                } ?: run { if (load is CropImageState.Loading) CircularProgressIndicator() }
            }
            (failure ?: (load as? CropImageState.Failed)?.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Zoom", style = MaterialTheme.typography.labelLarge)
            InterfaceSlider(zoom, { zoom = it; bitmap?.let(::constrain) },
                Modifier.semantics { contentDescription = "Crop zoom" }, valueRange = 1f..8f, enabled = !saving)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionTextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                ActionTextButton(onClick = { zoom = 1f; centerX = .5f; centerY = .5f }, enabled = !saving) { Text("Reset crop") }
                ActionButton(enabled = bitmap != null && !saving, onClick = {
                    val image = bitmap ?: return@ActionButton
                    saving = true; failure = null
                    scope.launch {
                        try {
                            val output = withContext(Dispatchers.IO) {
                                val side = minOf(image.width, image.height) / zoom
                                val edge = side.roundToInt().coerceIn(1, 2048)
                                val cropped = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                                android.graphics.Canvas(cropped).drawBitmap(image,
                                    android.graphics.Rect((centerX * image.width - side / 2).roundToInt().coerceAtLeast(0),
                                        (centerY * image.height - side / 2).roundToInt().coerceAtLeast(0),
                                        (centerX * image.width + side / 2).roundToInt().coerceAtMost(image.width),
                                        (centerY * image.height + side / 2).roundToInt().coerceAtMost(image.height)),
                                    android.graphics.Rect(0, 0, edge, edge), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                                val directory = java.io.File(context.cacheDir, "artwork-crops").apply { mkdirs() }
                                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                                val file = java.io.File(directory, java.util.UUID.randomUUID().toString() + ".png")
                                file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                cropped.recycle()
                                android.net.Uri.fromFile(file).toString()
                            }
                            onCropped(output)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failure = error.message ?: "Couldn’t crop this image." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Preparing…" else "Use square crop") }
            }
        }
    }
}

/** One composition-owned load per source; no bitmap or coroutine is restored as saved state. */
internal sealed interface CropImageState {
    data object Loading : CropImageState
    data class Ready(val bitmap: Bitmap) : CropImageState
    data class Failed(val message: String) : CropImageState
}

internal enum class CropLoadPhase { Started, Reading, Decoding, Ready, Failed, Cancelled, Disposed }
/** Deliberately contains no URI or exception text. Observers are for failure-only test diagnostics. */
internal data class CropLoadEvent(val generation: Any, val phase: CropLoadPhase, val active: Boolean, val errorType: String? = null)
internal fun interface CropImageLoader {
    suspend fun load(uri: String, phase: (CropLoadPhase) -> Unit): Bitmap?
}
internal val LocalCropImageLoader = staticCompositionLocalOf<CropImageLoader?> { null }
internal val LocalCropLoadObserver = staticCompositionLocalOf<((CropLoadEvent) -> Unit)?> { null }

@Composable
private fun rememberCropImage(uri: String): CropImageState {
    val context = LocalContext.current
    val loader = LocalCropImageLoader.current ?: remember(context) {
        CropImageLoader { source, phase ->
            withContext(Dispatchers.IO) {
                phase(CropLoadPhase.Reading)
                val bytes = CoverImages(context).read(source, 4096).bytes
                phase(CropLoadPhase.Decoding)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }
    val observer = LocalCropLoadObserver.current
    val state = remember(uri, loader) { mutableStateOf<CropImageState>(CropImageState.Loading) }
    val generation = remember(uri, loader) { Any() }
    LaunchedEffect(uri, loader) {
        val job = currentCoroutineContext()
        fun report(phase: CropLoadPhase, errorType: String? = null) {
            observer?.invoke(CropLoadEvent(generation, phase, job[kotlinx.coroutines.Job]?.isActive == true, errorType))
        }
        report(CropLoadPhase.Started)
        try {
            val image = loader.load(uri) { report(it) }
            // A non-cooperative old loader must not publish after disposal or source replacement.
            currentCoroutineContext().ensureActive()
            state.value = CropImageState.Ready(image ?: error("Couldn’t decode the image. Choose it again."))
            report(CropLoadPhase.Ready)
        } catch (cancelled: CancellationException) {
            report(CropLoadPhase.Cancelled)
            throw cancelled
        } catch (error: Exception) {
            // A cancelled non-cooperative read can throw an ordinary error on its way out.
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { report(CropLoadPhase.Cancelled); throw cancelled }
            state.value = CropImageState.Failed(error.message ?: "Couldn’t open this image.")
            report(CropLoadPhase.Failed, error.javaClass.simpleName)
        }
    }
    DisposableEffect(generation) {
        onDispose { observer?.invoke(CropLoadEvent(generation, CropLoadPhase.Disposed, false)) }
    }
    return state.value
}
