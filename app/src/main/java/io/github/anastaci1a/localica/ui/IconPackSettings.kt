package io.github.anastaci1a.localica.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.icons.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Settings library. Source documents are opened only while preparing an import. */
@Composable
internal fun IconPackSettings(onView: (String?) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val library by IconPacks.library.collectAsState()
    val active by IconPacks.activeId.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<PreparedIconPack?>(null) }
    var deleting by remember { mutableStateOf<StoredIconPack?>(null) }
    fun install(prepared: PreparedIconPack) {
        scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                runCatching { IconPacks.install(context, prepared) }.fold(
                    { "Imported “${it.info.name}”: ${it.applied.size} icons. View icons for the complete import report." },
                    { "Couldn’t store this pack. The previous pack is kept." })
            }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching {
                requireNotNull(context.contentResolver.openInputStream(uri)).use(IconPacks::prepare)
            } }
            busy = false
            result.fold({ prepared ->
                if (IconPacks.matching(context, prepared) != null) updating = prepared else install(prepared)
            }, { message = "Couldn’t import this pack" + (if (it is IconPackException) ": ${it.message}." else ".") })
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { IconPacks.exportTemplate(context, it) } }
                    .fold({ "Exported the built-in icons as a template pack." }, { "Couldn’t export the template." })
            }
            busy = false
        }
    }
    Text(if (active == null) "Using the built-in icons" else "Using “${library.firstOrNull { it.id == active }?.report?.info?.name.orEmpty()}”",
        modifier = Modifier.testTag("icon-pack-status"))
    SettingsEntry("Active icon pack") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    IconPackRow(null, active == null, !busy, { IconPacks.reset(context) }, { onView(null) })
    library.forEach { pack ->
        IconPackRow(pack, pack.id == active, !busy, { IconPacks.select(context, pack.id) }, { onView(pack.id) }, { deleting = pack })
    }
    } }
    SettingsEntry("Import icon pack") { ActionTextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Import icon pack") } }
    SettingsEntry("Export built-in icons as a template") { ActionTextButton(enabled = !busy, onClick = { exporter.launch("localica-icon-template.zip") }) { Text("Export built-in icons as a template") } }
    updating?.let { prepared ->
        GlassUnanchoredPopup("import button; anchor tracked in #781") { GlassAlertDialog({ updating = null }, title = { Text("Update ${prepared.report.info.name}?") },
            text = { Text("Version ${prepared.report.info.version} is already in your library. Replace its icons with this import?") },
            confirmButton = { ActionTextButton({ updating = null; install(prepared) }) { Text("Update") } },
            dismissButton = { ActionTextButton({ updating = null }) { Text("Cancel") } }) }
    }
    deleting?.let { pack ->
        GlassUnanchoredPopup("row button; anchor tracked in #781") { GlassAlertDialog({ deleting = null }, title = { Text("Delete ${pack.report.info.name}?") },
            text = { Text("Remove this pack from your library? If it is active, Localica will use the built-in icons.") },
            confirmButton = { ActionTextButton({
                deleting = null
                scope.launch {
                    busy = true
                    message = withContext(Dispatchers.IO) { runCatching { IconPacks.delete(context, pack.id) }.exceptionOrNull()?.let { "Couldn’t delete this pack." } }
                    busy = false
                }
            }) { Text("Delete") } }, dismissButton = { ActionTextButton({ deleting = null }) { Text("Cancel") } }) }
    }
    message?.let { text ->
        GlassUnanchoredPopup("failure notice") { GlassAlertDialog({ message = null }, title = { Text("Icon packs") }, text = { Text(text) },
            confirmButton = { ActionTextButton({ message = null }) { Text("Close") } }) }
    }
}

@Composable
private fun IconPackRow(pack: StoredIconPack?, selected: Boolean, enabled: Boolean,
    onSelect: () -> Unit, onView: () -> Unit, onDelete: (() -> Unit)? = null) {
    val info = pack?.report?.info
    SettingsLink(info?.name ?: "Built-in icons", listOfNotNull(
        if (selected) "Active" else null, info?.version?.let { "Version $it" }, info?.author,
        if (pack == null) "${AppIcon.entries.size} icons" else "${pack.iconIds.size} of ${AppIcon.entries.size} icons replaced"
    ).joinToString(" · "), enabled = enabled, onClick = onSelect)
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icons = if (pack == null) AppIcon.entries.take(4) else pack.iconIds.take(4).mapNotNull(AppIcon::byId)
        icons.forEach { icon -> Icon(packIconPainter(icon, pack?.masks?.get(icon.id), pack?.vectors?.get(icon.id)), null, Modifier.size(24.dp)) }
    }
    Row {
        ActionTextButton(onView, enabled = enabled) { Text("View icons") }
        if (onDelete != null) ActionTextButton(onDelete, enabled = enabled) { Text("Delete") }
    }
}

/** Every registry id appears, including those a partial pack leaves to the built-in set. */
@Composable
internal fun IconPackViewer(id: String?) {
    val library by IconPacks.library.collectAsState()
    val pack = library.firstOrNull { it.id == id }
    if (id != null && pack == null) { Text("This pack is no longer in your library."); return }
    Text(pack?.report?.info?.let { listOfNotNull(it.name, "Version ${it.version}", it.author).joinToString(" · ") } ?: "Built-in icons")
    pack?.let { Text("Imported ${DateFormat.getDateTimeInstance().format(Date(it.importedAt))}", style = MaterialTheme.typography.bodySmall) }
    Text("Pack icon · Built-in icon", style = MaterialTheme.typography.bodySmall)
    AppIcon.entries.groupBy { it.id.substringBefore('.') }.forEach { (area, icons) ->
        SettingsHeading(when (area) { "tab" -> "Tabs"; "transport" -> "Transport"; "menu" -> "Song menu"; "settings" -> "Settings"; else -> "Actions" })
        icons.forEach { icon ->
            val mask = pack?.masks?.get(icon.id)
            val vector = pack?.vectors?.get(icon.id)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("pack-icon-${icon.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(packIconPainter(icon, mask, vector), "Pack: ${icon.id}", Modifier.size(24.dp))
                Icon(packIconPainter(icon, null), "Built-in: ${icon.id}", Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(icon.id)
                    Text(if (vector != null) "Reassigned · Vector" else if (mask != null) "Reassigned" else if (pack == null) "Built-in" else "Built-in (fallback)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    pack?.report?.let { report ->
        if (report.unknown.isNotEmpty()) { SettingsHeading("Unknown ids"); report.unknown.forEach { Text(it) } }
        if (report.rejected.isNotEmpty()) { SettingsHeading("Rejected entries"); report.rejected.forEach { Text("${it.first}: ${it.second}") } }
    }
}
