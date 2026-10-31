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
): Bitmap = awaitNativeWindowCopyOnce(image, timeoutMessage, failureMessage, request = { callback ->
    PixelCopy.request(window, image, { code -> callback(code) }, Handler(Looper.getMainLooper()))
})

/** The same ownership protocol with controllable request/deadline boundaries for lifecycle tests. */
internal fun awaitNativeWindowCopyOnce(
    image: Bitmap,
    timeoutMessage: String,
    failureMessage: (Int) -> String,
    request: ((Int) -> Unit) -> Unit,
    awaitCompletion: (CountDownLatch) -> Boolean = { it.await(5, TimeUnit.SECONDS) },
    recycle: () -> Unit = { image.recycle() },
): Bitmap {
    val completed = CountDownLatch(1)
    val ownership = Any()
    var returned = false
    var abandoned = false
    var result = PixelCopy.ERROR_UNKNOWN
    try {
        request { code ->
            synchronized(ownership) { result = code; returned = true; if (abandoned) recycle() }
            completed.countDown()
        }
    } catch (failure: Throwable) { recycle(); throw failure }
    if (!awaitCompletion(completed)) {
        synchronized(ownership) { if (returned) recycle() else abandoned = true }
        throw AssertionError(timeoutMessage)
    }
    if (result != PixelCopy.SUCCESS) { recycle(); throw AssertionError(failureMessage(result)) }
    return image
}
