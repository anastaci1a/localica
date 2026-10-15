package io.github.anastaci1a.localica

import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.TrackedDataSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackedDataSourceTest {
    private val uri = Uri.parse("content://fixture/document/song")
    private val spec = DataSpec(uri)

    @Test fun alternateTreeGrantIsRejectedBeforeDelegateOpen() = runBlocking {
        val readers = NativeReaders()
        val block = readers.block(uri.toString())
        var opened = false
        val delegate = object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long { opened = true; return 1 }
        }
        val source = TrackedDataSource(delegate, readers)
        val alternate = DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://fixture/tree/root"), "song")
        assertTrue(runCatching { source.open(DataSpec(alternate)) }.exceptionOrNull() is IOException)
        assertFalse(opened)
        source.close()
        block.awaitClosed(); block.release()
    }

    @Test fun failedOpenClosesPartialResourcesAndPreservesOriginalFailure() = runBlocking {
        val readers = NativeReaders()
        lateinit var block: NativeReaders.Block
        var closes = 0
        val failure = IOException("Partial provider open")
        val delegate = object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long {
                block = readers.block(uri.toString())
                throw failure
            }
            override fun close() {
                closes++
                assertFalse(block.closed.isCompleted)
            }
        }
        val source = TrackedDataSource(delegate, readers)
        assertSame(failure, runCatching { source.open(spec) }.exceptionOrNull())
        block.awaitClosed()
        source.close()
        assertEquals(1, closes)
        block.release()
    }

    @Test fun failedOpenAndCloseRemainUnsafeForFutureEdits() = runBlocking {
        val readers = NativeReaders()
        val opening = IOException("Partial open")
        val closing = IOException("Unknown descriptor lifetime")
        var closes = 0
        val source = TrackedDataSource(object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long = throw opening
            override fun close() { closes++; throw closing }
        }, readers)
        assertSame(opening, runCatching { source.open(spec) }.exceptionOrNull())
        assertSame(closing, opening.suppressed.single())
        source.close()
        assertEquals(1, closes)
        repeat(2) {
            val block = readers.block(uri.toString())
            val failure = runCatching { block.awaitClosed() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(generateSequence(failure) { it.cause }.any { it === closing })
            block.release()
        }
    }

    @Test fun openingIsCountedBeforeDelegateReturnsAndCloseMustActuallyFinish() = runBlocking {
        val readers = NativeReaders()
        val opening = CountDownLatch(1)
        val finishOpen = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val source = TrackedDataSource(object : DataSource by ByteArrayDataSource(byteArrayOf(1)) {
            override fun open(dataSpec: DataSpec): Long {
                opening.countDown()
                check(finishOpen.await(5, TimeUnit.SECONDS))
                return 1
            }
            override fun close() { closing.countDown(); check(finishClose.await(5, TimeUnit.SECONDS)) }
        }, readers)
        val loader = async(Dispatchers.IO) { source.open(spec); source.close() }
        var block: NativeReaders.Block? = null
        try {
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            block = readers.block(uri.toString())
            val drained = async(start = CoroutineStart.UNDISPATCHED) { block.awaitClosed() }
            assertFalse(drained.isCompleted)
            finishOpen.countDown()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(drained.isCompleted)
            finishClose.countDown()
            withTimeout(5000) { drained.await(); loader.await() }
        } finally { withContext(NonCancellable) {
            finishOpen.countDown(); finishClose.countDown()
            withTimeout(5000) { loader.join() }; block?.release()
        } }
    }

    @Test fun distinctEngineSourcesAndReopensShareOneLifetimeRegistry() = runBlocking {
        val readers = NativeReaders()
        fun source() = TrackedDataSource(ByteArrayDataSource(byteArrayOf(1)), readers)
        val oldEngine = source()
        val replacementEngine = source()
        oldEngine.open(spec); replacementEngine.open(spec)
        val block = readers.block(uri.toString())
        val drained = async(start = CoroutineStart.UNDISPATCHED) { block.awaitClosed() }
        oldEngine.close(); oldEngine.close()
        assertFalse(drained.isCompleted)
        replacementEngine.close()
        drained.await()
        assertTrue(runCatching { oldEngine.open(spec) }.isFailure)
        block.release()
        oldEngine.open(spec)
        val retry = readers.block(uri.toString())
        assertFalse(retry.closed.isCompleted)
        oldEngine.close()
        retry.awaitClosed(); retry.release()
    }
}
