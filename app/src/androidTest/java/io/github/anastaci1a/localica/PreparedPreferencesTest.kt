package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.preferences.PreparedPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreparedPreferencesTest {
    @Test fun coldPreparationSuspendsWithoutOpeningOrWaitingOnMain() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(Looper.myLooper() != Looper.getMainLooper())
                opens.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        val owner = withContext(Dispatchers.Main) { PreparedPreferences(context, scope) }
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) {
                assertFalse(owner.ready.value)
                assertTrue(runCatching { owner.require("scanner") }.exceptionOrNull() is IllegalStateException)
                val waiting = launch { owner.awaitReady() }
                yield()
                assertTrue(waiting.isActive)
                waiting.cancel()
            }
            release.countDown()
            withTimeout(10_000) { owner.awaitReady() }
            val count = opens.get()
            withContext(Dispatchers.Main) {
                assertTrue(owner.ready.value)
                assertSame(app.preferences.require("scanner"), owner.require("scanner"))
                owner.require("lyric-sources").getString("unused-test-key", null)
                assertEquals(count, opens.get())
            }
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun productionWorkerRuntimeWaitsForDelayedPreparationBeforeUsingConsumers() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val opens = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(Looper.myLooper() != Looper.getMainLooper())
                opens.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        val owner = PreparedPreferences(context, scope)
        val runtime = io.github.anastaci1a.localica.lastfm.ApplicationLastFmWorkRuntime(app, owner)
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            val worker = androidx.work.testing.TestListenableWorkerBuilder<io.github.anastaci1a.localica.lastfm.LastFmWorker>(app)
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters): androidx.work.ListenableWorker =
                        io.github.anastaci1a.localica.lastfm.LastFmWorker(appContext, workerParameters, runtime)
                }).build()
            val pending = async(Dispatchers.IO) { worker.doWork() }
            delay(100)
            assertFalse(pending.isCompleted)
            assertFalse(owner.ready.value)
            release.countDown()
            // No username means no network request. The real runtime must still pass readiness first.
            assertEquals(androidx.work.ListenableWorker.Result.failure(), withTimeout(10_000) { pending.await() })
            assertSame(app.preferences.require("listening-identity"), owner.require("listening-identity"))
            assertEquals(14, opens.get())
        } finally { release.countDown(); scope.cancel() }
    }


    @Test fun delayedEntryCommandsKeepAttachmentAndUserCommandOrder() = verifyLifecycle("ordered")
    @Test fun destructionBeforeReadinessCancelsEntriesWithoutCancellingPreparation() = verifyLifecycle("before")
    @Test fun readinessRacingShutdownCannotResurrectACancelledEntry() = verifyLifecycle("racing")

    private fun verifyLifecycle(mode: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, flags: Int): SharedPreferences {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, flags)
            }
        }
        val owner = PreparedPreferences(context, preparationScope)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            val jobs = withContext(Dispatchers.Main) {
                listOf("attach", "play", "pause").map { event ->
                    owner.launchWhenReady(entryScope) { events += event }
                }
            }
            assertTrue(events.isEmpty())
            if (mode == "ordered") release.countDown()
            else withContext(Dispatchers.Main) {
                if (mode == "racing") release.countDown()
                entryScope.cancel()
                if (mode == "before") release.countDown()
            }
            withTimeout(10_000) { owner.awaitReady(); jobs.joinAll() }
            assertTrue(owner.ready.value)
            assertEquals(if (mode == "ordered") listOf("attach", "play", "pause") else emptyList<String>(), events.toList())
        } finally { release.countDown(); entryScope.cancel(); preparationScope.cancel() }
    }


    @Test fun isolatedPreparedContextRetainsItsStoreEditorAndDirectoryAcrossThemeWrappers() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val name = "prepared-isolation-" + java.util.UUID.randomUUID()
        val raw = app.getSharedPreferences(name, 0)
        val previousMaximum = app.preferences.require("queue-settings").getInt("maximum", 0)
        val opens = AtomicInteger()
        val edits = AtomicInteger()
        val directory = java.io.File(app.cacheDir, name)
        val retained = object : SharedPreferences by raw {
            override fun edit(): SharedPreferences.Editor { edits.incrementAndGet(); return raw.edit() }
        }
        val context = preparedTestContext(object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(store: String, mode: Int): SharedPreferences {
                check(store == "queue-settings")
                check(Looper.myLooper() != Looper.getMainLooper())
                opens.incrementAndGet()
                return retained
            }
        }, listOf("queue-settings"))
        try {
            val themed = android.view.ContextThemeWrapper(context, android.R.style.Theme_Material)
            withContext(Dispatchers.Main) {
                assertSame(retained, PreparedPreferences.get(themed, "queue-settings"))
                assertSame(directory, PreparedPreferences.filesDirectory(themed))
                val preferences = io.github.anastaci1a.localica.queue.QueuePreferences(themed)
                preferences.write(preferences.read().copy(maximum = 37))
                assertEquals(37, preferences.read().maximum)
                assertEquals(previousMaximum, app.preferences.require("queue-settings").getInt("maximum", 0))
            }
            assertEquals(1, opens.get())
            assertEquals(1, edits.get())
        } finally { app.deleteSharedPreferences(name) }
    }


    @Test fun newlyReadyInlineCommandCannotOvertakeAnOlderQueuedContinuation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LocalicaApplication>()
        app.preferences.awaitReady()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val inline = java.util.concurrent.atomic.AtomicBoolean(true)
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = !inline.get()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val entryScope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.getSharedPreferences(name, mode)
            }
        }
        val owner = PreparedPreferences(context, preparationScope)
        val events = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            val play = owner.launchWhenReady(entryScope) { events += "play" }
            inline.set(false)
            release.countDown()
            withTimeout(10_000) { owner.awaitReady() }
            assertTrue("The older readiness continuation must still be queued", pending.isNotEmpty())
            inline.set(true)
            val pause = owner.launchWhenReady(entryScope) { events += "pause" }
            assertTrue("The new immediate entry must remain behind the queued entry", events.isEmpty())
            while (true) { val continuation = pending.poll() ?: break; continuation.run() }
            withTimeout(10_000) { play.join(); pause.join() }
            assertEquals(listOf("play", "pause"), events)
        } finally { release.countDown(); entryScope.cancel(); preparationScope.cancel() }
    }

}
