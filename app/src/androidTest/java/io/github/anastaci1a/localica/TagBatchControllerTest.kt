package io.github.anastaci1a.localica

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real controller, batch journals and queue/library scopes; only foreground dispatch is held. */
class TagBatchControllerTest {
    @Test fun backgroundAcceptanceDuringForegroundCompletionStillCommitsOnceWorkerReleases() = fixture {
        val entered = CompletableDeferred<Unit>()
        val finish = CountDownLatch(1)
        context.beforeJobsCommit = { value ->
            if (value?.contains("Complete; awaiting Done") == true) {
                entered.complete(Unit)
                check(finish.await(15, TimeUnit.SECONDS)) { "Completion history barrier timed out" }
            }
        }
        val controller = controller()
        var complete: Boolean? = null
        onMain { controller.open(tracks) { complete = it }; controller.saveDraft(patch, draft) }
        coroutineScope {
            val work = async { onMain { controller.runPending() } }
            try {
                withTimeout(10000) { entered.await() }
                assertEquals("AwaitingDecision", controller.state.value.phase)
                assertTrue(controller.state.value.busy)
                onMain { controller.continueInBackground() }
                await { controller.state.value.background }
                assertTrue(store.read()!!.background)
            } finally { finish.countDown() }
            work.await()
        }
        await { controller.state.value.batchId == null }
        assertEquals(true, complete)
        assertNull(store.read())
        assertEquals(tracks.size, replacements)
    }

    @Test fun cancelPersistenceFailureDuringWriteClosesTheNextFileAndRestoresAfterRestart() = fixture {
        val controller = controller()
        holdFirst = true
        onMain { controller.open(tracks); controller.saveDraft(patch, draft) }
        coroutineScope {
            val work = async { onMain { controller.runPending() } }
            try {
                withTimeout(10000) { entered.await() }
                failCancel = true
                onMain { controller.cancelBatch() }
                await { controller.state.value.error?.contains("not recorded") == true }
            } finally { release.countDown() }
            work.await()
        }
        assertEquals("Only the acquired first file may finish", 1, replacements)
        assertTrue(controller.state.value.cancelPending)
        assertFalse(controller.state.value.busy)
        val starts = context.starts
        onMain { controller.resumeBatch(); controller.finishBatch(); controller.continueInBackground() }
        delay(50)
        assertEquals(starts, context.starts)
        assertEquals(1, replacements)
        assertNotNull(store.read())
        val restarted = controller()
        await { restarted.state.value.batchId != null }
        assertTrue(restarted.state.value.interruptedBatch)
        assertFalse(restarted.state.value.busy)
        assertEquals(1, replacements)
        failCancel = false
        onMain { restarted.cancelBatch() }
        await { restarted.state.value.busy }
        onMain { restarted.runPending() }
        assertNull(store.read()); assertOriginals()
    }

    @Test fun failedCancelBeforeCommitRefusesResumeDoneAndBackgroundUntilRestoreRetry() = fixture {
        val batch = store.begin(tracks, patch, draft, true)
        val controller = controller()
        await { controller.state.value.batchId == batch.id }
        failCancel = true
        onMain { controller.cancelBatch() }
        await { controller.state.value.error?.contains("not recorded") == true }
        assertTrue(controller.state.value.cancelPending)
        onMain { controller.resumeBatch(); controller.finishBatch(); controller.continueInBackground() }
        delay(50)
        assertFalse(controller.state.value.busy)
        assertEquals(0, context.starts)
        assertEquals(TagBatchPhase.Interrupted, store.read()!!.phase)
        assertEquals(0, replacements)
        // No durable Cancel exists. A fresh process must offer interrupted work and never
        // automatically resume or pretend the failed request was accepted durably.
        val restarted = controller()
        await { restarted.state.value.batchId == batch.id }
        assertTrue(restarted.state.value.interruptedBatch)
        assertFalse(restarted.state.value.busy)
        assertFalse(restarted.state.value.cancelPending)
        assertEquals(0, context.starts)
        failCancel = false
        onMain { restarted.cancelBatch() }
        await { restarted.state.value.busy }
        onMain { restarted.runPending() }
        assertNull(store.read())
        assertFalse(restarted.state.value.cancelPending)
        assertEquals(0, replacements)
        assertOriginals()
    }

    @Test fun preparationCancelNeverAdmitsFilesAndKeepsTheEditorSession() = fixture {
        val controller = controller()
        onMain { controller.open(tracks); controller.saveDraft(patch, draft); controller.cancelBatch() }
        val session = controller.state.value.sessionId
        onMain { controller.runPending() }
        assertEquals(0, replacements)
        assertNull(store.read())
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.progressVisible)
        assertEquals(session, controller.state.value.sessionId)
        assertEquals(tracks, controller.state.value.tracks)
        assertOriginals()
    }

    @Test fun duplicateServiceDispatchWritesOnceAndForegroundDoneReportsSuccess() = fixture {
        val controller = controller()
        var success: Boolean? = null
        onMain { controller.open(tracks) { success = it }; controller.saveDraft(patch, draft) }
        coroutineScope {
            val first = async { onMain { controller.runPending() } }
            val duplicate = async { onMain { controller.runPending() } }
            first.await(); duplicate.await()
        }
        assertEquals(tracks.size, replacements)
        assertEquals(tracks.size, controller.state.value.saved)
        assertEquals(TagBatchPhase.AwaitingDecision, store.read()!!.phase)
        assertNotNull(store.read()!!.draft)
        onMain { controller.finishBatch() }
        await { controller.state.value.batchId == null }
        assertEquals(true, success)
        assertNull(store.read())
    }

    @Test fun interruptedDraftBackgroundCompletesFromRetainedPatch() = fixture {
        val batch = store.begin(tracks, patch, draft, true)
        val controller = controller()
        await { controller.state.value.batchId == batch.id }
        assertEquals(draft, controller.state.value.draft)
        assertTrue(controller.state.value.interruptedBatch)
        onMain { controller.resumeBatch(); controller.continueInBackground() }
        await { controller.state.value.background }
        assertTrue(store.read()!!.background)
        onMain { controller.runPending() }
        assertEquals(tracks.size, replacements)
        assertNull(store.read())
        assertTrue(controller.state.value.tracks.isEmpty())
    }

    @Test fun failedBackgroundRetainsFirstOriginalsForExplicitRestore() = fixture {
        store.begin(tracks, patch, draft, true)
        val controller = controller()
        await { controller.state.value.batchId != null }
        failOne = tracks[1].uri
        onMain { controller.resumeBatch(); controller.continueInBackground() }
        await { controller.state.value.background }
        onMain { controller.runPending() }
        assertNotNull(store.read())
        assertTrue(store.original(store.read()!!.id, 0).isFile)
        assertEquals(1, controller.state.value.saved)
        onMain { controller.showJobs(); controller.cancelBatch() }
        await { controller.state.value.busy }
        onMain { controller.runPending() }
        assertNull(store.read()); assertOriginals()
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { withTimeout(45000) { fixture.block() } } finally { fixture.context.retire() }
    }
    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val app = instrumentation.targetContext.applicationContext as LocalicaApplication
        val context = BatchServiceContext(instrumentation.targetContext)
        private val files = List(2) { index -> File(context.filesDir, "song-$index.mp3").also { file ->
            instrumentation.context.assets.open("tagged.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        } }
        val tracks = files.map { Track(Uri.fromFile(it).toString(), "Before", fileName = it.name, relativePath = it.name) }
        private val originals = files.map(File::readBytes)
        val patch = TagPatch(mapOf(EditField.Title to "Retained name"))
        val draft = "{\"values\":{\"Title\":\"Retained name\"},\"checked\":[\"Title\"],\"removeArtwork\":false}"
        var failCancel = false
        var holdFirst = false
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var failOne: String? = null
        var replacements = 0
        val store = TagBatchStore(File(context.filesDir, "tag-batches")) { atomic, bytes ->
            val phase = JSONObject(bytes.toString(Charsets.UTF_8)).optString("phase")
            if (failCancel && phase in listOf("CancelRequested", "Restoring")) throw IOException("Before cancellation commit")
            commitRefreshRecord(atomic, bytes)
        }
        private val documents = object : EditableDocuments {
            override fun writable(track: Track) = track.uri
            override fun size(uri: String) = File(requireNotNull(Uri.parse(uri).path)).length()
            override fun open(uri: String) = File(requireNotNull(Uri.parse(uri).path)).inputStream()
            override fun replace(uri: String, source: File) {
                replacements++
                if (holdFirst) {
                    holdFirst = false; entered.complete(Unit)
                    check(release.await(15, TimeUnit.SECONDS)) { "Controller replacement barrier timed out" }
                }
                if (failOne == uri && source.name.startsWith("edited.")) {
                    failOne = null; throw IOException("Controlled write refusal")
                }
                source.copyTo(File(requireNotNull(Uri.parse(uri).path)), overwrite = true)
            }
        }
        suspend fun controller(): TagEditingController = onMain { TagEditingController(context, app.library, app.queues, app.playlists,
            RecoverableTagWriter(File(context.filesDir, "tag-recovery"), documents, batches = store)) }
        fun assertOriginals() { files.forEachIndexed { index, file -> assertArrayEquals(originals[index], file.readBytes()) } }
        suspend fun <T> onMain(action: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { action() }
        suspend fun await(condition: () -> Boolean) = withTimeout(10000) { while (!onMain { condition() }) delay(10) }
    }
}

private class BatchServiceContext(base: Context) : ContextWrapper(base) {
    private val token = "batch-controller-${UUID.randomUUID()}"
    private val directory = File(base.filesDir, token).apply { check(mkdirs()) }
    private val preferences = mutableSetOf<String>()
    var starts = 0
    var beforeJobsCommit: ((String?) -> Unit)? = null
    override fun getFilesDir(): File = directory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val owned = "$token-$name"; preferences += owned
        val preferences = baseContext.getSharedPreferences(owned, mode)
        if (name != "tag-jobs") return preferences
        return object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    var jobs: String? = null
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        if (key == "jobs") jobs = value
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        beforeJobsCommit?.invoke(jobs)
                        return editor.commit()
                    }
                }
            }
        }
    }
    override fun startForegroundService(service: Intent): ComponentName {
        starts++; return requireNotNull(service.component)
    }
    fun retire() {
        preferences.forEach { baseContext.deleteSharedPreferences(it) }
        check(directory.deleteRecursively())
    }
}
