package io.github.anastaci1a.localica.lastfm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import io.github.anastaci1a.localica.LocalicaApplication
import io.github.anastaci1a.localica.MainActivity
import io.github.anastaci1a.localica.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Only explicit manual requests are enqueued. KEEP prevents concurrent scans of the same account. */
class LastFmWork(private val app: LocalicaApplication) : LastFmBackground {
    override val updates get() = flow {
        var observed: java.util.UUID? = null
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(NAME).collect { work ->
            val active = work.firstOrNull { !it.state.isFinished }
            if (active != null) {
                observed = active.id
                emit(LastFmBackgroundState(true, LastFmProgress(active.progress.getInt("page", 0),
                    active.progress.getInt("totalPages", 0), active.progress.getInt("songs", 0), active.progress.getInt("artists", 0),
                    active.progress.getInt("albums", 0), active.progress.getInt("percent", 0))))
            } else emit(LastFmBackgroundState(false, problem = work.firstOrNull { it.id == observed }?.outputData?.getString("problem")))
        }
    }
    override suspend fun enqueue(username: String) = enqueue(username, false)
    override suspend fun enqueueFullRescan(username: String) = enqueue(username, true)
    private suspend fun enqueue(username: String, fullRescan: Boolean) {
        WorkManager.getInstance(app).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LastFmWorker>()
                .setInputData(workDataOf(USERNAME to username, FULL_RESCAN to fullRescan))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()).result.awaitResult()
    }
    override suspend fun cancelAndAwait() { WorkManager.getInstance(app).cancelUniqueWork(NAME).result.awaitResult() }
    override fun cancel() { WorkManager.getInstance(app).cancelUniqueWork(NAME) }
    override fun cancelIfConsentDenied() {
        // Consent starts closed during its asynchronous disk load. Never cancel a restored job for that transient state.
        CoroutineScope(Dispatchers.IO).launch {
            app.preferences.awaitReady()
        app.onlineMetadataConsent.load()
            if (!app.onlineMetadataConsent.allowed.value) cancel()
        }
    }
    companion object {
        const val OPEN_SETTINGS = "localica.openLastFmSettings"
        const val NAME = "lastfm-manual-sync"
        const val USERNAME = "username"
        const val FULL_RESCAN = "full-rescan"
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener({ try { continuation.resumeWith(kotlin.Result.success(get())) }
            catch (failure: Exception) { continuation.resumeWith(kotlin.Result.failure(failure)) } },
            java.util.concurrent.Executor { it.run() })
        continuation.invokeOnCancellation { cancel(true) }
    }

internal fun interface LastFmWorkRuntime {
    suspend fun controller(): LastFmController
}

class LastFmWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val runtime: LastFmWorkRuntime) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, LastFmWorkRuntime {
        val app = context.applicationContext as LocalicaApplication
        app.onlineMetadataConsent.load()
        app.lastfm
    })
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(LastFmProgress())
    override suspend fun doWork(): Result {
        val controller = runtime.controller()
        if (!controller.networkConsent.allowed.value || !controller.enabled) return Result.failure()
        val username = inputData.getString(LastFmWork.USERNAME) ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            if (controller.syncInBackground(username, id.toString(), inputData.getBoolean(LastFmWork.FULL_RESCAN, false)) { progress ->
                setProgress(workDataOf("page" to progress.page, "totalPages" to progress.totalPages, "songs" to progress.songs,
                    "artists" to progress.artists, "albums" to progress.albums, "percent" to progress.percent))
                setForeground(foreground(progress))
            }) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure(workDataOf("problem" to "Background sync could not finish. Your previous snapshot is kept.")) }
    }
    private fun foreground(progress: LastFmProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("lastfm-sync", "Last.fm sync", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 684,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LastFmWork.OPEN_SETTINGS, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "lastfm-sync").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Syncing Last.fm statistics").setContentText("${progress.percent}% · ${progress.countSummary(applicationContext.resources)}").setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress.percent, progress.totalPages == 0 && progress.reportedPercent == null)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(684, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(684, notification)
    }
}
