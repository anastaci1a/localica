package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Size
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

/** Compose ui-graphics 1.8.2's LayerSnapshotV22/Image.toBitmap converts
 * premultiplied ImageReader bytes as straight colors, attenuating translucent decorations twice. Keep their original bytes
 * and honor row padding while freezing the source atlas. */
internal suspend fun GraphicsLayer.pinchSnapshot(density: Density, direction: LayoutDirection): ImageBitmap {
    if (Build.VERSION.SDK_INT >= 28) return toImageBitmap()
    val width = size.width
    val height = size.height
    return checkNotNull(awaitPinchSnapshot {
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { reader ->
        val surface = reader.surface
        try { suspendCancellableCoroutine { continuation ->
            reader.setOnImageAvailableListener({ available ->
                try {
                    available.acquireLatestImage()?.use { image ->
                        if (continuation.isActive) {
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
                        drawLayer(this@pinchSnapshot)
                    }
                } finally { surface.unlockCanvasAndPost(canvas) }
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        } } finally {
            reader.setOnImageAvailableListener(null, null)
            surface.release()
        }
        }
    }) { "Pinch snapshot did not arrive before its deadline" }
}
