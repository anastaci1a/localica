package io.github.anastaci1a.localica.scanner

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

class ScanService : Service() {
    private val scanner get() = (application as LocalicaApplication).scanner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var latestStart = 0
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("scanner", "Library scanning", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        if (intent?.action == "cancel") { scanner.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, 9001, notification(scanner.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) { scanner.failedToStart(); stopSelf(); return START_NOT_STICKY }
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localica:scan")
                .apply { acquire(6 * 60 * 60 * 1000L) }
            scope.launch { scanner.state.collectLatest { state ->
                if (state.running && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED))
                    getSystemService(NotificationManager::class.java).notify(9001, notification(state))
            } }
        }
        scanner.run(intent?.getStringArrayListExtra("roots").orEmpty(), intent?.getBooleanExtra("new-files-only", false) == true,
            intent?.getBooleanExtra("full-after-quick", false) == true) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStart)
        }
        return START_NOT_STICKY
    }
    private fun notification(state: ScanState): Notification {
        val open = PendingIntent.getActivity(this, 9001, Intent(this, MainActivity::class.java)
            .putExtra("show-scanner", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 9002, Intent(this, ScanService::class.java).setAction("cancel"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "scanner").setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Scanning music").setContentText("${state.count} files checked · ${state.completedFolders}/${state.totalFolders} folders")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .addAction(0, "Cancel", cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        scanner.cancel("Android stopped the background scan at its time limit. Completed folders were saved.")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        scanner.cancel(); scope.cancel()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

