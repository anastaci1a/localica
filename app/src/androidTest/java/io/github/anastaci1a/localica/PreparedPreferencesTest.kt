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

}
