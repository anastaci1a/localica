package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.HardwareRenderer
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent destination coverage only. Inputs are immutable receipt geometry, never captured pixels. */
@RequiresApi(29)
internal fun nativeShadowCutoutReference(width: Int, height: Int, bodies: List<Pair<RoundRect, Float>>): ImageBitmap {
    require(width > 0 && height > 0 && bodies.isNotEmpty())
    check(Build.VERSION.SDK_INT >= 29)
    val geometry = bodies.map { it.first }
    val ox = geometry.minOf { it.left }; val oy = geometry.minOf { it.top }
    // Preserve the producer's normalized path and fractional destination translation. No renderer/cache reuse.
    val path = Path().apply {
        geometry.forEach { r -> addRoundRect(r.left - ox, r.top - oy, r.right - ox, r.bottom - oy,
            floatArrayOf(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y,
                r.topRightCornerRadius.x, r.topRightCornerRadius.y,
                r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y,
                r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y), Path.Direction.CW) }
    }
    val thread = HandlerThread("cutout-reference-readback").apply { start() }
    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val renderer = HardwareRenderer()
    val node = RenderNode("independent-cutout-reference")
    try {
        val available = CountDownLatch(1)
        reader.setOnImageAvailableListener({ available.countDown() }, Handler(thread.looper))
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording(width, height)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.save()
        canvas.translate(ox, oy)
        canvas.clipOutPath(path)
        canvas.drawColor(AndroidColor.BLACK)
        canvas.restore()
        node.endRecording()
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.setOpaque(true)
        val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        check(status == HardwareRenderer.SYNC_OK) { "Reference render failed: $status" }
        check(available.await(5, TimeUnit.SECONDS)) { "Reference readback was not delivered" }
        val image = checkNotNull(reader.acquireNextImage()) { "Reference buffer missing" }
        image.use {
            val plane = it.planes.single()
            val buffer = plane.buffer
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                pixels[y * width + x] = AndroidColor.argb(buffer.get(offset + 3).toInt() and 255,
                    buffer.get(offset).toInt() and 255, buffer.get(offset + 1).toInt() and 255,
                    buffer.get(offset + 2).toInt() and 255)
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    } finally {
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
        thread.quitSafely()
        thread.join(5000)
    }
}
