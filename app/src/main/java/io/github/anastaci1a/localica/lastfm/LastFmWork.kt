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

/** Only explicit manual requests are enqueued. KEEP prevents concurrent scans of the same account. */
class LastFmWork(private val app: LocalicaApplication) : LastFmBackground {
    override suspend fun enqueue(username: String) {
        WorkManager.getInstance(app).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LastFmWorker>()
                .setInputData(workDataOf(USERNAME to username))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()).result.awaitResult()
    }
    override fun cancel() { WorkManager.getInstance(app).cancelUniqueWork(NAME) }
    override fun cancelIfConsentDenied() {
        // Consent starts closed during its asynchronous disk load. Never cancel a restored job for that transient state.
        CoroutineScope(Dispatchers.IO).launch {
            app.onlineMetadataConsent.load()
            if (!app.onlineMetadataConsent.allowed.value) cancel()
        }
    }
    companion object {
        const val OPEN_SETTINGS = "localica.openLastFmSettings"
        const val NAME = "lastfm-manual-sync"
        const val USERNAME = "username"
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener({ try { continuation.resumeWith(kotlin.Result.success(get())) }
            catch (failure: Exception) { continuation.resumeWith(kotlin.Result.failure(failure)) } },
            java.util.concurrent.Executor { it.run() })
        continuation.invokeOnCancellation { cancel(true) }
    }

class LastFmWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(LastFmProgress())
    override suspend fun doWork(): Result {
        val app = applicationContext as LocalicaApplication
        app.onlineMetadataConsent.load()
        if (!app.onlineMetadataConsent.allowed.value || BuildConfigKeyMissing(app)) return Result.failure()
        val username = inputData.getString(LastFmWork.USERNAME) ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            if (app.lastfm.syncInBackground(username, id.toString()) { progress ->
                setProgress(workDataOf("page" to progress.page, "totalPages" to progress.totalPages, "songs" to progress.songs))
                setForeground(foreground(progress))
            }) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
    }
    private fun foreground(progress: LastFmProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("lastfm-sync", "Last.fm sync", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 684,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LastFmWork.OPEN_SETTINGS, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "lastfm-sync").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Syncing Last.fm statistics").setContentText(progress.label).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(progress.totalPages, progress.page, progress.totalPages == 0)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(684, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(684, notification)
    }
    private fun BuildConfigKeyMissing(app: LocalicaApplication) = !app.lastfm.enabled
}
