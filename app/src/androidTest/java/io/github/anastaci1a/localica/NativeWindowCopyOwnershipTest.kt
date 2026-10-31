package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class NativeWindowCopyOwnershipTest {
    private class Copy : AutoCloseable {
        val image = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        var releases = 0
        var requests = 0
        lateinit var callback: (Int) -> Unit

        fun run(
            onRequest: () -> Unit = {},
            await: (CountDownLatch) -> Boolean = { it.count == 0L },
        ): Bitmap = awaitNativeWindowCopyOnce(image, "window deadline", { "window failure $it" },
            request = { completed -> requests++; callback = completed; onRequest() },
            awaitCompletion = await,
            recycle = { releases++; image.recycle() })

        override fun close() { if (!image.isRecycled) image.recycle() }
    }

    private fun assertion(body: () -> Unit): AssertionError {
        try { body() } catch (failure: AssertionError) { return failure }
        throw IllegalStateException("Expected the copy operation to fail")
    }

    @Test fun successfulCompletionTransfersTheOriginalBitmapToItsCaller() {
        Copy().use { copy ->
            val result = copy.run(onRequest = { copy.callback(PixelCopy.SUCCESS) })
            assertSame(copy.image, result)
            assertEquals(1, copy.requests)
            assertEquals(0, copy.releases)
            assertFalse(result.isRecycled)
            result.eraseColor(android.graphics.Color.RED)
            assertEquals(android.graphics.Color.RED, result.getPixel(2, 1))
        }
    }

    @Test fun requestExceptionReleasesOnceAndPreservesTheOriginalFailure() {
        Copy().use { copy ->
            val original = IllegalArgumentException("request rejected")
            val observed = try {
                copy.run(onRequest = { throw original }, await = { error("No wait after rejection") })
                null
            } catch (failure: IllegalArgumentException) { failure }
            assertSame(original, observed)
            assertEquals(1, copy.requests)
            assertEquals(1, copy.releases)
            assertTrue(copy.image.isRecycled)
        }
    }

    @Test fun failedCompletionReleasesOnceAndKeepsTheCallerDiagnostic() {
        Copy().use { copy ->
            val failure = assertion { copy.run(onRequest = { copy.callback(PixelCopy.ERROR_SOURCE_NO_DATA) }) }
            assertEquals("window failure ${PixelCopy.ERROR_SOURCE_NO_DATA}", failure.message)
            assertEquals(1, copy.requests)
            assertEquals(1, copy.releases)
            assertTrue(copy.image.isRecycled)
        }
    }

    @Test fun timeoutKeepsThePendingDestinationAliveUntilItsLateCallback() {
        for (result in listOf(PixelCopy.SUCCESS, PixelCopy.ERROR_SOURCE_NO_DATA)) {
            Copy().use { copy ->
                val failure = assertion { copy.run(await = { latch ->
                    assertEquals(1L, latch.count)
                    false
                }) }
                assertEquals("window deadline", failure.message)
                assertEquals(1, copy.requests)
                assertEquals(0, copy.releases)
                assertFalse(copy.image.isRecycled)
                copy.callback(result)
                assertEquals(1, copy.releases)
                assertTrue(copy.image.isRecycled)
            }
        }
    }

    @Test fun callbackAtTheDeadlineReleasesOnceWithoutReturningTheDestination() {
        for (result in listOf(PixelCopy.SUCCESS, PixelCopy.ERROR_SOURCE_NO_DATA)) {
            Copy().use { copy ->
                val failure = assertion { copy.run(await = { latch ->
                    copy.callback(result)
                    assertEquals(0L, latch.count)
                    false
                }) }
                assertEquals("window deadline", failure.message)
                assertEquals(1, copy.requests)
                assertEquals(1, copy.releases)
                assertTrue(copy.image.isRecycled)
            }
        }
    }
}
