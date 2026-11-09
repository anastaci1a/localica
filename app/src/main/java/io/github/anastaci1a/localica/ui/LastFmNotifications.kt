package io.github.anastaci1a.localica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun LastFmNotificationPreference() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true; allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    if (!allowed) SettingsEntry("Sync notifications") {
        Text("Allow notifications to see sync progress and Stop after leaving Localica.")
        GlassActionButton(AppIcon.ActionInfo, "Enable sync notifications") {
            if (Build.VERSION.SDK_INT >= 33 && !asked && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openLocalicaSystemSettings(context, true)
        }
    }
}
