package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One native Window request for a caller-allocated image, with existing late-callback ownership. */
internal fun copyNativeWindowOnce(
    window: Window,
    image: Bitmap,
    timeoutMessage: String,
    failureMessage: (Int) -> String,
): Bitmap {
    val completed = CountDownLatch(1)
    val ownership = Any()
    var returned = false
    var abandoned = false
    var result = PixelCopy.ERROR_UNKNOWN
    try {
        PixelCopy.request(window, image, { code ->
            synchronized(ownership) { result = code; returned = true; if (abandoned) image.recycle() }
            completed.countDown()
        }, Handler(Looper.getMainLooper()))
    } catch (failure: Throwable) { image.recycle(); throw failure }
    if (!completed.await(5, TimeUnit.SECONDS)) {
        synchronized(ownership) { if (returned) image.recycle() else abandoned = true }
        throw AssertionError(timeoutMessage)
    }
    if (result != PixelCopy.SUCCESS) { image.recycle(); throw AssertionError(failureMessage(result)) }
    return image
}
