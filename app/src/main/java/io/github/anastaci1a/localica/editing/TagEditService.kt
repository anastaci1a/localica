package io.github.anastaci1a.localica.editing

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.anastaci1a.localica.LocalicaApplication
import io.github.anastaci1a.localica.MainActivity
import io.github.anastaci1a.localica.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Keeps verified writes alive when the Activity leaves; a process loss retains recovery. */
class TagEditService : Service() {
    private val controller get() = (application as LocalicaApplication).tagEditing
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tag-edits", "Tag editing", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { controller.stopAfterCurrent(); if (work?.isActive != true) stopSelf(); return START_NOT_STICKY }
        if (work?.isActive == true) return START_NOT_STICKY
        try {
            ServiceCompat.startForeground(this, 9010, notification(controller.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) { controller.serviceFailed("Android couldn’t start background editing."); stopSelf(); return START_NOT_STICKY }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localica:tag-edit")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        work = scope.launch {
            val notifications = launch { controller.state.collectLatest { state ->
                if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    getSystemService(NotificationManager::class.java).notify(9010, notification(state))
            } }
            try { controller.runPending() }
            finally { notifications.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: TagEditorState): Notification {
        val open = PendingIntent.getActivity(this, 9010, Intent(this, MainActivity::class.java)
            .putExtra("show-tag-jobs", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 9011, Intent(this, TagEditService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "tag-edits").setSmallIcon(R.drawable.ic_edit)
            .setContentTitle("Editing song tags").setContentText("${state.results.size} of ${state.tracks.size} files checked")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(state.tracks.size, state.results.size, false).addAction(0, "Stop after current", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.interrupted()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        if (controller.state.value.busy) controller.interrupted()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
