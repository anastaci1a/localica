package io.github.anastaci1a.localica.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.icons.IconPackException
import io.github.anastaci1a.localica.icons.IconPacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Interface → Appearance → Icon pack. Changes apply live through the shared icon resolver. */
@Composable
internal fun IconPackSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val info by IconPacks.info.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                try {
                    val report = requireNotNull(context.contentResolver.openInputStream(uri)) { "The file could not be opened" }
                        .use { IconPacks.import(context, it) }
                    buildString {
                        append("Replaced ${report.applied.size} of ${io.github.anastaci1a.localica.ui.AppIcon.entries.size} icons from “${report.info.name}”; the rest keep the built-in icons.")
                        if (report.unknown.isNotEmpty()) append("\nIgnored unknown ids: ${report.unknown.joinToString()}.")
                        if (report.rejected.isNotEmpty()) append("\nRejected: " + report.rejected.joinToString { "${it.first} (${it.second})" } + ".")
                    }
                } catch (problem: IconPackException) { "Couldn’t import this pack: ${problem.message}." }
                catch (_: Exception) { "Couldn’t import this pack." }
            }
            busy = false
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                try {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { IconPacks.exportTemplate(context, it) }
                    "Exported the built-in icons as a template pack."
                } catch (_: Exception) { "Couldn’t export the template." }
            }
            busy = false
        }
    }
    SettingsHeading("Icon pack")
    Text(info?.let { "Using “${it.name}” ${it.version}" + (it.author?.let { author -> " by $author" } ?: "") } ?: "Using the built-in icons",
        modifier = Modifier.testTag("icon-pack-status"))
    ActionTextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Import icon pack") }
    ActionTextButton(enabled = !busy && info != null, onClick = { scope.launch { withContext(Dispatchers.IO) { IconPacks.reset(context) } } }) { Text("Reset to built-in icons") }
    ActionTextButton(enabled = !busy, onClick = { exporter.launch("localica-icon-template.zip") }) { Text("Export built-in icons as a template") }
    message?.let { text ->
        GlassAlertDialog({ message = null }, title = { Text("Icon pack") }, text = { Text(text) },
            confirmButton = { ActionTextButton({ message = null }) { Text("Close") } })
    }
}
