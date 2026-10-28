package io.github.anastaci1a.localica.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.anastaci1a.localica.R

internal fun openLocalicaSystemSettings(context: Context, notifications: Boolean = false) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val intent = if (notifications) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) else details
    try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { context.startActivity(details) }
}

@Composable
internal fun ScannerPopup(state: LibraryUiState, onDismiss: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit, onQuickScan: () -> Unit = {},
    onSettings: (String) -> Unit) {
    val context = LocalContext.current
    var backgroundHelp by remember { mutableStateOf(false) }
    var failuresOpen by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val restricted = remember(revision) { Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true; revision++ }
    GlassAlertDialog(onDismissRequest = onDismiss, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Scan music", Modifier.weight(1f))
            ActionIcon(R.drawable.ic_settings, "Scanner") { onSettings("Scanner") }
        }
    }, text = {
        Column(Modifier.testTag("scanner-body").controlVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScannerActions(state, onStart, onQuickScan)
            // Keep these slots in both states: starting/stopping cannot resize the popup.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.scanning) LinearProgressIndicator(Modifier.fillMaxSize(),
                    color = NavigationAccent, trackColor = NavigationAccent.copy(alpha = .18f))
            }
            Text(if (state.scanning) "${state.scannedCount} files checked · ${state.scanStatus.completedFolders}/${state.scanStatus.totalFolders} folders"
                else state.scanStatus.message ?: "Ready to scan", minLines = 2, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTextButton(onClick = {
                    if (!notifications || restricted) backgroundHelp = true else onDismiss()
                }, enabled = state.scanning, modifier = Modifier.weight(1f)) { Text("Continue in background") }
                ActionTextButton(onClick = onCancel, enabled = state.scanning) { Text("Cancel scan") }
            }
            Text("${state.snapshot.tracks.size} songs in your library")
            SettingsLink("Folders to scan", "${state.snapshot.folders.size} selected", R.drawable.ic_folder) { onSettings("Folders to scan") }
            SettingsLink("Metadata read failures", "${state.snapshot.metadataFailures.map { it.trackId }.distinct().size} songs") { failuresOpen = true }
        }
    }, confirmButton = { ActionTextButton(onClick = onDismiss) { Text("Close") } })
    if (backgroundHelp) GlassAlertDialog(onDismissRequest = { backgroundHelp = false }, title = { Text("Background scanning") }, text = {
        Column(Modifier.controlVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!notifications) {
                Text("Notifications are off. Scanning can continue, but its progress and Cancel button won’t appear in the notification drawer.")
                if (Build.VERSION.SDK_INT >= 33 && !asked) ActionTextButton(onClick = { permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable notifications") }
                SettingsLink("Notification settings", "Open Localica’s notification controls") { openLocalicaSystemSettings(context, true) }
            }
            if (restricted) {
                Text("Android restricts Localica’s background activity. Open its app settings and allow background use under Battery.")
                SettingsLink("App settings", "Open Localica directly") { openLocalicaSystemSettings(context) }
            }
        }
    }, confirmButton = { ActionTextButton(onClick = { backgroundHelp = false; onDismiss() }) { Text("Continue in background") } },
        dismissButton = { ActionTextButton(onClick = { backgroundHelp = false }) { Text("Stay here") } })
    if (failuresOpen) SettingsPage("Metadata read failures", { failuresOpen = false }, { failuresOpen = false }) {
        MetadataReadFailures(state, Modifier.weight(1f))
    }
}

@Composable
internal fun ScannerActions(state: LibraryUiState, onFullScan: () -> Unit, onQuickScan: () -> Unit) {
    val enabled = !state.scanning && !state.loading && !state.updatingFolders && state.snapshot.folders.isNotEmpty()
    SettingsLink("Full scan", enabled = enabled, onClick = onFullScan)
    SettingsLink("Quick scan", enabled = enabled, onClick = onQuickScan)
    Text("Quick scan adds new songs. Full scan also refreshes tags and removes missing files. Your audio files stay untouched.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun MetadataReadFailures(state: LibraryUiState, modifier: Modifier = Modifier) {
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(modifier.fillMaxWidth().modalScroll(scroll), state = scroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("These files remain in the library using available metadata or filenames. A successful full scan clears their errors.") }
        if (state.snapshot.metadataFailures.isEmpty()) item { Text("No metadata read failures.") }
        items(state.snapshot.metadataFailures, key = { it.folderUri + "\n" + it.trackId }) { failure ->
            Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(failure.path, style = MaterialTheme.typography.titleSmall)
                    Text(state.snapshot.folders.firstOrNull { it.uri == failure.folderUri }?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
