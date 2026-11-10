package io.github.anastaci1a.localica.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.anastaci1a.localica.BuildConfig
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.ui.layout.SettingsStack
import io.github.anastaci1a.localica.ui.spec.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate

/** Settings → Library and tags → Library data (#720). The format is documented in docs/library-data.md. */
@Composable
internal fun LibraryDataSettings(library: LibraryRepository) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var preview by remember { mutableStateOf<Pair<LibraryDataDocument, LibraryImportPlan>?>(null) }
    val exportOrigin = rememberGlassAnchor()
    val importOrigin = rememberGlassAnchor()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = library.currentLibrary()
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
                        writeLibraryData(snapshot, out, System.currentTimeMillis(), BuildConfig.VERSION_NAME) { documentSize(context, it.uri) }
                    }
                }.fold({ "Library data exported" to "Saved ${LibraryDataText.count(it)} songs with their dates added." },
                    { "Library data" to "Couldn’t export library data. Choose another location and try again." })
            }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val document = requireNotNull(context.contentResolver.openInputStream(uri)).use(LibraryDataCodec::read)
                    document to library.previewLibraryData(document)
                }
            }
            busy = false
            read.fold({ preview = it }, { failure ->
                message = "Library data" to ((failure as? LibraryDataFormatException)?.message ?: "Couldn’t read this file. Nothing was changed.")
            })
        }
    }
    SettingsHeading("Library data")
    SettingsLink("Export library data", "Save songs and their dates added as a JSON file", enabled = !busy, anchor = exportOrigin) {
        exporter.launch("localica-library-data-${LocalDate.now()}.json")
    }
    SettingsLink("Import library data", "Apply dates added from a library data file", enabled = !busy, anchor = importOrigin) {
        importer.launch(arrayOf("application/json", "application/gzip", "application/x-gzip", "application/octet-stream", "text/plain"))
    }
    Text("Tools outside Localica can edit an exported file. Import shows what will change before applying it. Later scans keep imported dates.",
        style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    preview?.let { (document, plan) -> CompositionLocalProvider(LocalGlassOrigin provides importOrigin) {
        val changes = plan.changes
        GlassAlertDialog(onDismissRequest = { preview = null }, title = { Text(if (changes > 0) "Import library data?" else "Nothing to import") },
            text = { SettingsStack(gap = Space.Compact) {
                LibraryDataText.preview(plan).forEach { Text(it) }
            } },
            confirmButton = { if (changes > 0) ActionTextButton(onClick = {
                preview = null
                scope.launch {
                    busy = true
                    message = runCatching { library.importLibraryData(document) }.fold(
                        { "Library data imported" to LibraryDataText.applied(it).joinToString("\n") },
                        { "Library data" to "Couldn’t import library data. Nothing was changed." })
                    busy = false
                }
            }) { Text("Apply") } else ActionTextButton(onClick = { preview = null }) { Text("Close") } },
            dismissButton = if (changes > 0) ({ ActionTextButton(onClick = { preview = null }) { Text("Cancel") } }) else null)
    } }
    message?.let { (title, text) -> CompositionLocalProvider(LocalGlassOrigin provides importOrigin) {
        GlassAlertDialog(onDismissRequest = { message = null }, title = { Text(title) }, text = { Text(text) },
            confirmButton = { ActionTextButton(onClick = { message = null }) { Text("Close") } })
    } }
}

/** Plain-language counts shared by the confirmation and the result. */
internal object LibraryDataText {
    fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    fun preview(plan: LibraryImportPlan): List<String> = counts(plan) + if (plan.changes > 0)
        "Only date added is imported. Unmatched and ambiguous records are skipped." else "Every matched date added is already the same."

    fun applied(plan: LibraryImportPlan): List<String> =
        listOf("Updated date added for ${count(plan.changes)} ${if (plan.changes == 1) "song" else "songs"}.") + counts(plan)

    private fun counts(plan: LibraryImportPlan): List<String> {
        val stages = plan.matched
        return listOfNotNull(
            "Records in file: ${count(plan.records)}" + if (plan.skippedEntries > 0) " (${count(plan.skippedEntries)} unreadable entries skipped)" else "",
            "Matched: ${count(plan.matchedRecords)} (${count(stages[LibraryDataMatchStage.Id] ?: 0)} by ID, " +
                "${count(stages[LibraryDataMatchStage.Path] ?: 0)} by location, ${count(stages[LibraryDataMatchStage.Metadata] ?: 0)} by title, artist, album and duration)",
            "Unmatched: ${count(plan.unmatched)} · Ambiguous: ${count(plan.ambiguous)}",
        ) + plan.fields.map { field ->
            "${field.field.label}: ${count(field.matched)} matched, ${count(field.changed)} changed, ${count(field.unchanged)} unchanged, " +
                "${count(field.unmatched)} unmatched, ${count(field.ambiguous)} ambiguous" +
                if (field.invalid > 0) ", ${count(field.invalid)} invalid values skipped" else ""
        }
    }
}
