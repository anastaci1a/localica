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
private suspend fun GraphicsLayer.premultipliedPinchSnapshot(density: Density, direction: LayoutDirection): ImageBitmap {
    if (Build.VERSION.SDK_INT >= 28) return toImageBitmap()
    val width = size.width
    val height = size.height
    return run {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { reader ->
        val surface = reader.surface
        try { suspendCancellableCoroutine { continuation ->
            var postAttempted = false
            var producerFailure: Throwable? = null
            reader.setOnImageAvailableListener({ available ->
                try {
                    available.acquireLatestImage()?.use { image ->
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
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(packed)
                            continuation.resume(bitmap.asImageBitmap())
                        }
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }, Handler(Looper.getMainLooper()))
            try {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    CanvasDrawScope().draw(density, direction, Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
                        drawLayer(this@premultipliedPinchSnapshot)
                    }
                } finally {
                    // A failed post is ambiguous: retain ownership until an actual
                    // image callback, rather than closing a potentially queued draw.
                    postAttempted = true
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (failure: Throwable) {
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
    private val retained = RetainedLayerReadback()
    var occupied = false
        private set

    suspend fun capture(context: GraphicsContext, density: Density, direction: LayoutDirection,
        timeoutMillis: Long = 3000, onDeadline: () -> Unit = {},
        copy: suspend (GraphicsLayer) -> ImageBitmap = { it.premultipliedPinchSnapshot(density, direction) },
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
                caller.ensureActive()
                val started = now()
                val handler = Handler(Looper.getMainLooper())
                var expired = false
                var deadlineFailure: Throwable? = null
                val deadline = Runnable {
                    expired = true
                    deadlineFailure = runCatching(onDeadline).exceptionOrNull()
                }
                handler.postDelayed(deadline, timeoutMillis)
                try {
                    val image = retained.capture(atlas, context, density, direction, copy)
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
