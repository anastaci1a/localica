package io.github.anastaci1a.localica

import android.content.Context
import android.util.AtomicFile
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.NetworkConsent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class LastFmWorkerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val old = SavedLastFm("user", snapshot = LastFmSnapshot("user", 1, listOf(LastFmTrack("Old", "Song", 7)), localBaseline = emptyMap()))
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }
    private fun controller(file: File, scope: CoroutineScope, consent: Consent, api: LastFmApi, time: Long) =
        LastFmController(FileLastFmStore(file), LastFmSync(api, {}), flowOf(LibrarySnapshot()), true, consent, scope, { time })
    private fun worker(controller: LastFmController, id: UUID, notifications: MutableList<ForegroundInfo>, pages: MutableList<Data>, fullRescan: Boolean = false): LastFmWorker =
        TestListenableWorkerBuilder<LastFmWorker>(context).setId(id)
            .setInputData(workDataOf(LastFmWork.USERNAME to "user", LastFmWork.FULL_RESCAN to fullRescan))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    LastFmWorker(appContext, workerParameters, LastFmWorkRuntime { controller })
            }).setForegroundUpdater { _, _, info ->
                notifications += info
                CallbackToFutureAdapter.getFuture<Void> { it.set(null); "foreground recorded" }
            }.setProgressUpdater { _, _, data ->
                pages += data
                CallbackToFutureAdapter.getFuture<Void> { it.set(null); "progress recorded" }
            }.build()
    private fun dispose(file: File) {
        file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete()
    }

    @Test fun interruptedWorkerReopensOnlyTheCompleteSnapshotAndResumesItsDurableAttempt() = runBlocking {
        val file = File(context.cacheDir, "lastfm-worker-${UUID.randomUUID()}.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val id = UUID.randomUUID(); val notifications = CopyOnWriteArrayList<ForegroundInfo>(); val pages = CopyOnWriteArrayList<Data>()
        val second = CompletableDeferred<LastFmPage>(); val consent = Consent()
        try {
            FileLastFmStore(file).write(old)
            val first = controller(file, scope, consent, LastFmApi { _, page -> if (page == 1)
                LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 90))) else second.await() }, 100_000)
            val work = async(Dispatchers.IO) { worker(first, id, notifications, pages).doWork() }
            withTimeout(5000) { first.state.first { it.progress?.page == 1 } }
            withTimeout(5000) { while (notifications.none { it.notification.extras.getCharSequence("android.text")?.contains("50%") == true }) delay(10) }
            assertEquals(old.snapshot, FileLastFmStore(file).read().snapshot)
            assertEquals(id.toString(), FileLastFmStore(file).read().activeAttempt)
            assertEquals(1, pages.last().getInt("songs", -1))
            work.cancelAndJoin(); scope.cancel()
            // Recreate the worker, controller and AtomicFile reader using only persisted bytes and WorkManager's request UUID.
            val reopened = controller(file, resumedScope, Consent(), LastFmApi { _, page ->
                LastFmPage(page, 1, listOf(LastFmTrack("New", "Complete", 200))) }, 100_001)
            val result = worker(reopened, id, notifications, pages).doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals("Complete", FileLastFmStore(file).read().snapshot!!.tracks.single().title)
            assertNull(FileLastFmStore(file).read().activeAttempt)
            assertTrue(notifications.any { it.notification.extras.getCharSequence("android.text")?.contains("100%") == true })
        } finally { scope.cancel(); resumedScope.cancel(); dispose(file) }
    }

    @Test fun interruptedAtomicWriteRecoversThePreviousSnapshotAfterReopen() = runBlocking {
        val file = File(context.cacheDir, "lastfm-crash-${UUID.randomUUID()}.json")
        try {
            FileLastFmStore(file).write(old)
            // Model death after bytes reach disk but before AtomicFile.finishWrite; neither finishWrite nor failWrite runs.
            val interrupted = AtomicFile(file).startWrite()
            interrupted.write("{partial".toByteArray()); interrupted.fd.sync(); interrupted.close()
            assertEquals(old, FileLastFmStore(file).read())
        } finally { dispose(file) }
    }

    @Test fun revokingConsentCancelsAWorkerAndNeverPublishesItsPartialPages() = runBlocking {
        val file = File(context.cacheDir, "lastfm-cancel-${UUID.randomUUID()}.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val consent = Consent()
        var calls = 0
        val secondEntered = CompletableDeferred<Unit>()
        try {
            FileLastFmStore(file).write(old)
            val controller = controller(file, scope, consent, LastFmApi { _, page -> calls++
                if (page == 1) LastFmPage(1, 2, listOf(LastFmTrack("New", "First", 99))) else { secondEntered.complete(Unit); awaitCancellation() } }, 100_000)
            val work = async(Dispatchers.IO) { worker(controller, UUID.randomUUID(), CopyOnWriteArrayList(), CopyOnWriteArrayList()).doWork() }
            withTimeout(5000) { controller.state.first { it.progress?.page == 1 } }
            withTimeout(5000) { secondEntered.await() }
            consent.setAllowed(false)
            withTimeout(5000) { work.join() }
            assertTrue(work.isCancelled)
            assertEquals(old.snapshot, FileLastFmStore(file).read().snapshot)
            val before = calls
            assertEquals(ListenableWorker.Result.failure(), worker(controller, UUID.randomUUID(), CopyOnWriteArrayList(), CopyOnWriteArrayList()).doWork())
            assertEquals(before, calls)
        } finally { scope.cancel(); dispose(file) }
    }
    /** The ordinary suite executes both phases. The host proof runs seed → force-stop → resume in separate app processes. */
    @Test fun durableAttemptCanBeResumedAfterAppProcessDeath() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("lastfmDeathPhase")
        require(phase == null || phase == "seed" || phase == "resume")
        val file = File(context.cacheDir, if (phase == null) "lastfm-death-${UUID.randomUUID()}.json" else "lastfm-death-probe.json")
        val receipt = File(file.path + ".receipt")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            if (phase != "resume") {
                FileLastFmStore(file).write(old)
                val first = controller(file, firstScope, Consent(), LastFmApi { _, page -> if (page == 1)
                    LastFmPage(1, 2, listOf(LastFmTrack("New", "Partial", 99))) else awaitCancellation() }, 100_000)
                val id = UUID.randomUUID()
                firstScope.launch { worker(first, id, CopyOnWriteArrayList(), CopyOnWriteArrayList()).doWork() }
                withTimeout(5000) { first.state.first { it.progress?.page == 1 } }
                assertEquals(old.snapshot, FileLastFmStore(file).read().snapshot)
                receipt.writeText(org.json.JSONObject().put("id", id.toString()).put("pid", android.os.Process.myPid()).toString())
                if (phase == "seed") {
                    android.util.Log.i("LastFmProcessDeath", "seed pid=${android.os.Process.myPid()} attempt=$id; previous snapshot intact")
                    return@runBlocking
                }
                firstScope.cancel()
            }
            val seed = org.json.JSONObject(receipt.readText())
            if (phase == "resume") assertNotEquals("The host must kill the seeded process before resume", seed.getInt("pid"), android.os.Process.myPid())
            val saved = FileLastFmStore(file).read()
            assertEquals(old.snapshot, saved.snapshot); assertEquals(seed.getString("id"), saved.activeAttempt)
            val resumed = controller(file, resumedScope, Consent(), LastFmApi { _, page ->
                LastFmPage(page, 1, listOf(LastFmTrack("New", "Complete", 200))) }, 100_001)
            assertEquals(ListenableWorker.Result.success(), worker(resumed, UUID.fromString(seed.getString("id")),
                CopyOnWriteArrayList(), CopyOnWriteArrayList()).doWork())
            assertEquals("Complete", FileLastFmStore(file).read().snapshot!!.tracks.single().title)
            assertNull(FileLastFmStore(file).read().activeAttempt)
            android.util.Log.i("LastFmProcessDeath", "resumed seedPid=${seed.getInt("pid")} pid=${android.os.Process.myPid()}; atomic completion verified")
        } finally {
            // Explicit seed leaves the blocked worker and its durable files for the host to kill, without graceful cancellation.
            if (phase != "seed") { firstScope.cancel(); resumedScope.cancel(); dispose(file); receipt.delete() }
        }
    }

    @Test fun fullRescanWorkUsesCompletePagingAndPublishesLiveNotificationCounts() = runBlocking {
        val file = File(context.cacheDir, "lastfm-full-" + UUID.randomUUID() + ".json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notifications = CopyOnWriteArrayList<ForegroundInfo>(); val pages = CopyOnWriteArrayList<Data>()
        var fullCalls = 0; var recentCalls = 0
        try {
            FileLastFmStore(file).write(old.copy(snapshot = old.snapshot!!.copy(refreshBoundarySeconds = 1)))
            val api = object : LastFmApi {
                override fun supportsQuickRefresh() = true
                override suspend fun page(username: String, number: Int): LastFmPage {
                    fullCalls++
                    return LastFmPage(1, 1, listOf(LastFmTrack("Artist", "Complete", 9, album = "Album")))
                }
                override suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage {
                    recentCalls++; error("Full rescan must not use recent history")
                }
            }
            val controller = controller(file, scope, Consent(), api, 100_000)
            assertEquals(ListenableWorker.Result.success(), worker(controller, UUID.randomUUID(), notifications, pages, true).doWork())
            assertEquals(1, fullCalls); assertEquals(0, recentCalls)
            assertEquals(1, pages.last().getInt("songs", -1))
            assertEquals(1, pages.last().getInt("artists", -1))
            assertEquals(1, pages.last().getInt("albums", -1))
            assertEquals(100, pages.last().getInt("percent", -1))
            assertTrue(notifications.last().notification.extras.getCharSequence("android.text")!!.contains("1 song · 1 artist · 1 album"))
            assertNotNull(notifications.last().notification.contentIntent)
        } finally { scope.cancel(); dispose(file) }
    }
}
