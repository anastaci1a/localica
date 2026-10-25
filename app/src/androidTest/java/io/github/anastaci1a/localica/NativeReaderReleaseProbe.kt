package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
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
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic-only regression: expected to fail until native reader quiescence is enforced. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeReaderReleaseProbe {
    @Test fun fileWriteMustWaitUntilTheNativeDataSourceActuallyCloses() = probe(gateRead = false)
    @Test fun fileWriteMustWaitForACancelledStalledNativeRead() = probe(gateRead = true)

    private fun probe(gateRead: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "reader-probe-$id").apply { mkdirs() }
        val file = File(directory, "tagged.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val uri = Uri.fromFile(file).toString()
        val track = Track(uri, "Before", fileName = file.name, relativePath = file.name)
        val queue = ListeningQueue(name = "Probe", entries = listOf(QueueEntry(track = track)))
        val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val readerHeld = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val allowReader = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val gated = AtomicBoolean(false)
        val factory = DefaultDataSource.Factory(context)
        val tracked = DataSource.Factory {
            val delegate = factory.createDataSource()
            object : DataSource by delegate {
                private var opened = false
                override fun open(dataSpec: DataSpec): Long {
                    opened = true
                    return delegate.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (gateRead && gated.compareAndSet(false, true)) {
                        readerHeld.complete(Unit)
                        awaitUninterruptibly(allowReader)
                    }
                    return delegate.read(buffer, offset, length)
                }
                override fun close() {
                    try {
                        if (!gateRead && opened && gated.compareAndSet(false, true)) {
                            readerHeld.complete(Unit)
                            awaitUninterruptibly(allowReader)
                        }
                    } finally { delegate.close(); closed.set(true) }
                }
            }
        }
        val player = withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(tracked)).build().apply { volume = 0f }
        }
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("reader-probe-$id", mode)
        })
        var request: Job? = null
        try {
            withContext(Dispatchers.IO) { database.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            val queues = QueueController(database.queues(), preferences, (context.applicationContext as LocalicaApplication).library, actor)
            queues.attach(player)
            // Hold either natural-EOF close or a provider-style read that ignores cancellation.
            // Both keep the real delegate descriptor open on the loading thread.
            withTimeout(10000) { readerHeld.await() }
            val documents = object : EditableDocuments {
                override fun writable(track: Track) = uri
                override fun open(uri: String) = file.inputStream()
                override fun replace(uri: String, source: File) {
                    writerEntered.complete(Unit)
                    awaitUninterruptibly(allowWrite)
                    check(closed.get()) { "Probe refuses to overwrite until the actual descriptor is closed" }
                    source.copyTo(file, overwrite = true)
                }
            }
            val writer = RecoverableTagWriter(File(directory, "recovery"), documents)
            request = launch {
                val acquired = CompletableDeferred<QueueController.EditPlayback?>()
                queues.beginFileEdit(track.id) { acquired.complete(it) }
                val token = withTimeout(5000) { acquired.await() }
                try {
                    writer.edit(track, TagPatch(mapOf(EditField.Title to "After")))
                    writer.complete()
                } finally {
                    val released = CompletableDeferred<Boolean>()
                    queues.endFileEdit(token) { released.complete(it) }
                    withContext(NonCancellable) { withTimeout(5000) { check(released.await()) } }
                }
            }
            val admitted = withTimeoutOrNull(5000) { writerEntered.await(); true } ?: false
            withContext(Dispatchers.Main) {
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.isPlaying)
            }
            assertFalse("The controlled native descriptor must still be open", closed.get())
            assertFalse("Destructive write was admitted before native close (stalledRead=$gateRead)", admitted)
        } finally { withContext(NonCancellable) {
            allowReader.countDown()
            try { withTimeout(5000) { while (!closed.get()) delay(10) } }
            finally {
                allowWrite.countDown()
                try {
                    try { withTimeout(10000) { request?.join() } }
                    catch (timeout: TimeoutCancellationException) {
                        request?.cancel()
                        withTimeout(20000) { request?.join() }
                        throw timeout
                    }
                } finally {
                    actor.cancel()
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Probe gate timed out" }
            try { check(latch.await(remaining, TimeUnit.NANOSECONDS)); return }
            catch (_: InterruptedException) { /* Model a provider whose close cannot be interrupted. */ }
        }
    }
}
