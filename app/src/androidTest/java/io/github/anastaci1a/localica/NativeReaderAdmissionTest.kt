package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.playback.QueueTransportPlayer
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.track
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real native loading threads, descriptors and writer, with controlled provider-style stalls. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeReaderAdmissionTest {
    @Test fun writeWaitsUntilNativeCloseActuallyCompletes() = probe()
    @Test fun writeWaitsForCancelledStalledNativeRead() = probe(gateRead = true)
    @Test fun preloadedDuplicateSourceMustCloseBeforeWrite() = probe(preload = true)
    @Test fun cancellationWhileNativeReadIsStalledNeverStartsWriter() = probe(gateRead = true, cancel = true)
    @Test fun timeoutWhileNativeReadIsStalledNeverStartsWriter() = probe(gateRead = true, timeout = true)
    @Test fun failedNativeClosePreventsWriterAndFutureEdits() = probe(failClose = true)
    @Test fun newerSystemPauseWhileNativeReadDrainsPreventsEditResume() = probe(gateRead = true, pause = true)

    private fun probe(gateRead: Boolean = false, preload: Boolean = false,
        cancel: Boolean = false, timeout: Boolean = false, failClose: Boolean = false, pause: Boolean = false) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "reader-probe-$id").apply { mkdirs() }
        val file = File(directory, "tagged.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val uri = Uri.fromFile(file).toString()
        val track = Track(uri, "Before", fileName = file.name, relativePath = file.name)
        val current = File(directory, "current.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> current.outputStream().use(input::copyTo) }
        val currentTrack = Track(Uri.fromFile(current).toString(), "Current", fileName = current.name, relativePath = current.name)
        val entries = if (preload) listOf(currentTrack, track, track) else listOf(track)
        val queue = ListeningQueue(name = "Probe", entries = entries.map { QueueEntry(track = it) })
        val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val readerHeld = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val allowReader = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val gated = AtomicBoolean(false)
        val readers = NativeReaders()
        val factory = DefaultDataSource.Factory(context)
        val tracked = DataSource.Factory {
            val delegate = factory.createDataSource()
            object : DataSource by delegate {
                private var target = false
                private var held = false
                override fun open(dataSpec: DataSpec): Long {
                    target = dataSpec.uri.toString() == uri
                    return delegate.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (target && gateRead && gated.compareAndSet(false, true)) {
                        held = true
                        readerHeld.complete(Unit)
                        awaitUninterruptibly(allowReader)
                    }
                    return delegate.read(buffer, offset, length)
                }
                override fun close() {
                    try {
                        if (target && !gateRead && gated.compareAndSet(false, true)) {
                            held = true
                            readerHeld.complete(Unit)
                            awaitUninterruptibly(allowReader)
                        }
                    } finally {
                        delegate.close()
                        if (held) {
                            closed.set(true)
                            if (failClose) throw IOException("Controlled close acknowledgement failure")
                        }
                    }
                }
            }
        }
        val player = withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(tracked))).build().apply { volume = 0f }
        }
        val resumeRequests = AtomicInteger()
        // Keep raw ExoPlayer for native preload configuration in the other cases. This case
        // records all actor assignments while retaining the real engine/read-close boundary.
        val attached: Player = if (pause) object : ForwardingPlayer(player) {
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) resumeRequests.incrementAndGet()
                super.setPlayWhenReady(playWhenReady)
            }
        } else player
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("reader-probe-$id", mode)
        })
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + if (timeout) StandardTestDispatcher(scheduler) else Dispatchers.Default)
        var request: Deferred<Result<Unit>>? = null
        try {
            withContext(Dispatchers.IO) { database.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            val queues = QueueController(database.queues(), preferences, (context.applicationContext as LocalicaApplication).library, actor, readers)
            queues.attach(attached)
            // Hold either natural-EOF close or a provider-style read that ignores cancellation.
            // Both keep the real delegate descriptor open on the loading thread.
            withTimeout(10000) { readerHeld.await() }
            withContext(Dispatchers.Main) {
                assertEquals(queue.currentEntryId, player.currentMediaItem?.mediaId)
                player.play()
            }
            val documents = object : EditableDocuments {
                override fun writable(track: Track) = uri
                override fun open(uri: String) = file.inputStream()
                override fun replace(uri: String, source: File) {
                    writerEntered.complete(Unit)
                    check(closed.get()) { "Probe refuses to overwrite until the actual descriptor is closed" }
                    source.copyTo(file, overwrite = true)
                }
            }
            val writer = RecoverableTagWriter(File(directory, "recovery"), documents)
            request = caller.async { runCatching {
                queues.withFileEdit(track.id) {
                    writerStarted.complete(Unit)
                    writer.edit(track, TagPatch(mapOf(EditField.Title to "After")))
                    writer.complete()
                }
            } }
            if (timeout) scheduler.runCurrent()
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { player.mediaItemCount == 0 }) delay(10) }
            if (timeout) scheduler.runCurrent()
            val admitted = withTimeoutOrNull(500) { writerStarted.await(); true } ?: false
            withContext(Dispatchers.Main) { assertFalse(player.isPlaying) }
            assertFalse("The controlled native descriptor must still be open", closed.get())
            assertFalse("Writer must wait for the actual loading-thread close", admitted)
            assertFalse(writerEntered.isCompleted)
            if (pause) QueueTransportPlayer(attached, queues).pause()
            if (cancel) {
                request.cancelAndJoin()
            } else if (timeout) {
                scheduler.advanceTimeBy(15000)
                scheduler.runCurrent()
                withTimeout(5000) {
                    while (!request.isCompleted) { scheduler.runCurrent(); delay(10) }
                }
                assertTrue(request.await().exceptionOrNull() is TimeoutCancellationException)
            }
            allowReader.countDown()
            withTimeout(5000) { while (!closed.get()) delay(10) }
            if (cancel || timeout) {
                assertFalse(writerStarted.isCompleted)
                assertFalse(writerEntered.isCompleted)
            } else {
                val result = withTimeout(10000) { request.await() }
                if (failClose) {
                    assertTrue(result.exceptionOrNull() is IOException)
                    assertFalse(writerStarted.isCompleted)
                    val retry = runCatching { queues.withFileEdit(track.id) { fail("Failed close was forgotten") } }
                    assertTrue(retry.exceptionOrNull() is IOException)
                } else {
                    result.getOrThrow()
                    assertTrue(writerStarted.isCompleted)
                    assertTrue(writerEntered.isCompleted)
                }
            }
            withTimeout(10000) { while (!withContext(Dispatchers.Main) {
                player.mediaItemCount > 0 && player.isPlaying == !pause
            }) delay(10) }
            withContext(Dispatchers.Main) {
                assertEquals(queue.currentEntryId, player.currentMediaItem?.mediaId)
                assertEquals(!pause, player.playWhenReady)
            }
            if (pause) assertEquals("Edit completion must never request stale true after Pause", 0, resumeRequests.get())
            assertEquals(queue.entries, queues.state.value.library.active!!.entries)
        } finally { withContext(NonCancellable) {
            allowReader.countDown()
            try { if (readerHeld.isCompleted) withTimeout(5000) { while (!closed.get()) delay(10) } }
            finally {
                if (timeout) { request?.cancel(); scheduler.runCurrent() }
                try {
                    suspend fun joinRequest() {
                        while (request?.isCompleted == false) {
                            if (timeout) scheduler.runCurrent()
                            delay(10)
                        }
                    }
                    try { withTimeout(10000) { joinRequest() } }
                    catch (failure: TimeoutCancellationException) {
                        request?.cancel()
                        withTimeout(20000) { joinRequest() }
                        throw failure
                    }
                } finally {
                    caller.cancel(); actor.cancel()
                    withContext(Dispatchers.Main) { player.release() }
                    database.close()
                    context.deleteSharedPreferences("reader-probe-$id")
                    // Never delete a fixture while a writer could still be using it.
                    if (request?.isCompleted != false) directory.deleteRecursively()
                }
            }
        } }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Probe gate timed out" }
            try { check(latch.await(remaining, TimeUnit.NANOSECONDS)); return }
            catch (_: InterruptedException) { /* Model a provider whose read or close cannot be interrupted. */ }
        }
    }
}
