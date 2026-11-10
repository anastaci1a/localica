package io.github.anastaci1a.localica.artistphoto

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.anastaci1a.localica.LocalicaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ArtistPhotoWorkStatus(val pending: Int = 0, val failed: Set<String> = emptySet(), val artist: String? = null)

/** One serial queue shares the provider throttle. Each artist is checkpointed before advancing. */
class ArtistPhotoWork(private val app: LocalicaApplication) {
    private val manager by lazy { WorkManager.getInstance(app) }
    private val jobs = ArtistPhotoJobs(File(io.github.anastaci1a.localica.preferences.PreparedPreferences.filesDirectory(app), "artist-photo-work"))
    private val lock = Mutex()
    private val mutableStatus = MutableStateFlow(ArtistPhotoWorkStatus())
    val status = mutableStatus.asStateFlow()

    fun bind(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            app.onlineMetadataConsent.allowed.collect { allowed ->
                lock.withLock { publish() }
                if (allowed) schedule() else manager.cancelUniqueWork(NAME)
            }
        }
    }

    suspend fun enqueue(request: ArtistPhotoRequest, refresh: Boolean = false) = withContext(Dispatchers.IO) {
        if (!app.onlineMetadataConsent.allowed.value) return@withContext
        if (!refresh && !app.artistPhotos.needsFetch(request)) return@withContext
        val added = lock.withLock {
            if (!refresh && jobs.all().any { it.request.artistKey == request.artistKey && it.state == "pending" }) false
            else { jobs.enqueue(request, refresh); publish(); true }
        }
        if (added) schedule()
    }

    suspend fun refresh(requests: List<ArtistPhotoRequest>) = withContext(Dispatchers.IO) {
        if (!app.onlineMetadataConsent.allowed.value) return@withContext
        lock.withLock { requests.forEach { jobs.enqueue(it, refresh = true) }; publish() }
        schedule()
    }

    suspend fun retry() = withContext(Dispatchers.IO) {
        if (!app.onlineMetadataConsent.allowed.value) return@withContext
        lock.withLock { jobs.retryFailures(); publish() }
        schedule()
    }

    private fun schedule() {
        val work = OneTimeWorkRequestBuilder<ArtistPhotoWorker>().addTag(NAME)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // Appending closes the enqueue-versus-last-checkpoint race without cancelling active downloads.
        manager.enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    private fun publish(artist: String? = null) {
        val all = jobs.all()
        mutableStatus.value = ArtistPhotoWorkStatus(all.count { it.state == "pending" },
            all.filter { it.state == "failed" }.map { it.request.artistKey }.toSet(), artist)
    }

    internal suspend fun drain(progress: suspend (Int, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val started = android.os.SystemClock.elapsedRealtime()
        while (app.onlineMetadataConsent.allowed.value) {
            val job = lock.withLock {
                jobs.all().firstOrNull { it.state == "pending" }?.also { publish(it.request.name) }
            } ?: break
            if (android.os.SystemClock.elapsedRealtime() - started > 8 * 60_000) return@withContext false
            progress(status.value.pending, job.request.name)
            var failed = false
            try { app.artistPhotos.observe(job.request, job.refresh).collect { failed = it.failed } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            if (!app.onlineMetadataConsent.allowed.value) break
            lock.withLock { jobs.complete(job, succeeded = !failed); publish() }
        }
        lock.withLock { publish() }
        true
    }

    private companion object { const val NAME = "artist-photo-acquisition" }
}

class ArtistPhotoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LocalicaApplication
        app.preferences.awaitReady()
        app.onlineMetadataConsent.load()
        val done = app.artistPhotoWork.drain { remaining, artist ->
            setProgress(workDataOf("remaining" to remaining, "artist" to artist))
        }
        return if (done) Result.success() else Result.retry()
    }
}
