package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BackdropSnapshotBackground(val image: ImageBitmap, val registration: BackdropRegistration)

/** Native readback covers only a transient producer. Restore the full coordinate space off-main,
 * using its already captured backing outside that region, so moving samples never carry a crop. */
internal suspend fun expandBackdropSnapshot(image: ImageBitmap, region: IntRect, size: IntSize,
    background: BackdropSnapshotBackground): ImageBitmap {
    val crop = softwareGlassSnapshot(image, region.size)
    val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(output).apply {
            save()
            val r = background.registration
            concat(android.graphics.Matrix().apply { setValues(floatArrayOf(r.xx, r.xy, r.tx, r.yx, r.yy, r.ty, 0f, 0f, 1f)) })
            drawBitmap(background.image.asAndroidBitmap(), 0f, 0f, null)
            restore()
            drawBitmap(crop.asAndroidBitmap(), region.left.toFloat(), region.top.toFloat(),
                android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) })
        }
        currentCoroutineContext().ensureActive()
        return output.asImageBitmap()
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    } finally { crop.asAndroidBitmap().recycle() }
}
