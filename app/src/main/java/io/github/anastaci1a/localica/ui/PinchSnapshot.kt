package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Compose ui-graphics 1.8.2's LayerSnapshotV22/Image.toBitmap converts
 * premultiplied ImageReader bytes as straight colors, attenuating translucent decorations twice. Keep their original bytes
 * and honor row padding while freezing the source atlas. */
private suspend fun GraphicsLayer.premultipliedPinchSnapshot(density: Density, direction: LayoutDirection,
    onPhase: (String) -> Unit): ImageBitmap =
    if (Build.VERSION.SDK_INT >= 28) toImageBitmap() else legacyLayerSnapshot(density, direction, straightAlpha = false, onPhase)

/** The Android 8 emulator's gralloc reserves an 8-byte header in each CPU-readable buffer but
 * reads pixels back 16 bytes in, so an RGBA buffer whose byte count is 4084 or 4088 modulo the
 * 4096-byte page overruns its mapping by one or two pixels: a pipe read into an unwritable
 * neighbour aborts, and a writable one is silently corrupted (#501). Unused bottom rows avoid
 * those sizes; the readback still copies only the layer's own rows. */
internal fun grallocSafeReadbackHeight(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    fun overruns(rows: Int) = (width.toLong() * rows * 4 % 4096).let { it == 4084L || it == 4088L }
    var rows = height
    while (overruns(rows)) rows++
    return rows
}

/** API < 28 layer readback through an owned ImageReader (#501). Unlike Compose's LayerSnapshotV22
 * (maxImages = 1 with acquireLatestImage over-acquiring, the Image left for ImageReader.close, a resume
 * per callback), this stays within maxImages, copies and closes each Image inside its callback, publishes
 * once and clears the listener before releasing the surface and reader. [straightAlpha] reproduces
 * Compose's pixel conversion for existing readback consumers; pinch keeps the premultiplied bytes.
 * Both honor row padding. */
internal suspend fun GraphicsLayer.legacyLayerSnapshot(density: Density, direction: LayoutDirection,
    straightAlpha: Boolean, onPhase: (String) -> Unit = {}): ImageBitmap {
    val width = size.width
    val height = size.height
    return run {
        ImageReader.newInstance(width, grallocSafeReadbackHeight(width, height), PixelFormat.RGBA_8888, 2).use { reader ->
        val surface = reader.surface
        try { suspendCancellableCoroutine { continuation ->
            var postAttempted = false
            var producerFailure: Throwable? = null
            reader.setOnImageAvailableListener({ available ->
                onPhase("legacy-image-callback")
                try {
                    available.acquireLatestImage()?.use { image ->
                        onPhase("legacy-image-acquired")
                        if (continuation.isActive) {
                            producerFailure?.let { throw it }
                            val plane = image.planes[0]
                            check(plane.pixelStride == 4) { "RGBA snapshot requires four-byte pixels" }
                            val packed = ByteBuffer.allocate(width * height * 4)
                            val source = plane.buffer.duplicate()
                            // Bitmap.copyPixelsFromBuffer consumes premultiplied RGBA,
                            // unlike setPixels/createBitmap(IntArray), which premultiply.
                            repeat(height) { row ->
                                source.limit(source.capacity())
                                source.position(row * plane.rowStride)
                                source.limit(source.position() + width * 4)
                                packed.put(source)
                            }
                            packed.rewind()
                            val bitmap = if (straightAlpha) {
                                // Compose's Image.toBitmap conversion: RGBA bytes read as straight ARGB.
                                val bytes = packed.array()
                                Bitmap.createBitmap(IntArray(width * height) { i ->
                                    val o = i * 4
                                    (bytes[o + 3].toInt() and 255 shl 24) or (bytes[o].toInt() and 255 shl 16) or
                                        (bytes[o + 1].toInt() and 255 shl 8) or (bytes[o + 2].toInt() and 255)
                                }, width, height, Bitmap.Config.ARGB_8888)
                            } else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(packed) }
                            onPhase("legacy-pixels-copied")
                            continuation.resume(bitmap.asImageBitmap())
                        }
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }, Handler(Looper.getMainLooper()))
            try {
                onPhase("legacy-post-start")
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    CanvasDrawScope().draw(density, direction, Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
                        drawLayer(this@legacyLayerSnapshot)
                    }
                } finally {
                    // A failed post is ambiguous: retain ownership until an actual
                    // image callback, rather than closing a potentially queued draw.
                    postAttempted = true
                    surface.unlockCanvasAndPost(canvas)
                    onPhase("legacy-post-returned")
                }
            } catch (failure: Throwable) {
                onPhase("legacy-post-failed:${failure.javaClass.simpleName}")
                if (postAttempted) producerFailure = failure
                else if (continuation.isActive) continuation.resumeWithException(failure)
            }
        } } finally {
            reader.setOnImageAvailableListener(null, null)
            surface.release()
        }
        }
    }
}

internal class PinchPublicationExpired : IllegalStateException("Pinch capture exceeded its publication deadline")

/** One owned atlas at a time. Expiry retires publication, never a pending native resource.
 * A missing native callback can keep this owner occupied until process teardown. No caller
 * may queue, rerecord or release its source while that completion is still outstanding. */
internal class PinchReadback(private val now: () -> Long = { android.os.SystemClock.uptimeMillis() }) {
    private val completion = ReadbackCompletion()
    private val retained = RetainedLayerReadback(glassWork = false)
    var occupied = false
        private set

    suspend fun capture(context: GraphicsContext, density: Density, direction: LayoutDirection,
        timeoutMillis: Long = 3000, onDeadline: () -> Unit = {},
        onPhase: (String) -> Unit = {},
        copy: suspend (GraphicsLayer) -> ImageBitmap = { it.premultipliedPinchSnapshot(density, direction, onPhase) },
        record: (GraphicsLayer) -> Unit): ImageBitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(timeoutMillis > 0)
        check(!occupied) { "A native pinch readback is still pending" }
        val caller = currentCoroutineContext()
        caller.ensureActive()
        occupied = true
        try {
            return completion.capture(acquire = { context.createGraphicsLayer() }, read = { atlas ->
                record(atlas)
                onPhase("atlas-recorded")
                caller.ensureActive()
                val started = now()
                val handler = Handler(Looper.getMainLooper())
                var expired = false
                var deadlineFailure: Throwable? = null
                val deadline = Runnable {
                    expired = true
                    onPhase("publication-expired")
                    deadlineFailure = runCatching(onDeadline).exceptionOrNull()
                }
                handler.postDelayed(deadline, timeoutMillis)
                try {
                    val image = retained.capture(atlas, context, density, direction, copy)
                    onPhase("native-copy-returned")
                    caller.ensureActive() // External cancellation remains cancellation, not expiry.
                    // A synchronous native copy may occupy Main until after expiry,
                    // before the Handler gets a chance to run its stale-publication policy.
                    if (!expired && now() - started >= timeoutMillis) {
                        expired = true
                        deadlineFailure = runCatching(onDeadline).exceptionOrNull()
                    }
                    deadlineFailure?.let { throw it }
                    if (expired) throw PinchPublicationExpired()
                    image
                } finally { handler.removeCallbacks(deadline) }
            }, release = { context.releaseGraphicsLayer(it) })
        } finally { occupied = false }
    }
}
