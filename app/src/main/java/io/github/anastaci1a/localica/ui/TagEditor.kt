package io.github.anastaci1a.localica.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import org.json.JSONObject

internal val LocalTagEdit = staticCompositionLocalOf<((List<Track>, (Boolean) -> Unit) -> Unit)?> { null }

internal val LocalArtworkEdit = staticCompositionLocalOf<((Track) -> Unit)?> { null }

@Composable
internal fun TagEditorHost(controller: TagEditingController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    CompositionLocalProvider(LocalTagEdit provides controller::open, LocalArtworkEdit provides controller::openArtwork) { content() }
    if (state.tracks.isNotEmpty() && !state.background) TagEditorDialog(state, controller)
    if (state.jobsVisible) TagJobsDialog(state, controller)
    var recoveryDismissed by remember(state.recovery) { mutableStateOf(false) }
    if (state.recovery != null && !recoveryDismissed && state.tracks.isEmpty()) GlassAlertDialog(
        onDismissRequest = { recoveryDismissed = true }, title = { Text("Interrupted tag edit") },
        text = { Column { Text("An original copy of ${state.recovery} is retained. Restore it to undo the interrupted edit. You can also recover from Settings.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = controller::recover) { Text("Restore original") } },
        dismissButton = { TextButton(onClick = { recoveryDismissed = true }) { Text("Later") } })
}

@Composable
internal fun TagRecoverySettings(controller: TagEditingController) {
    val state by controller.state.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    SettingsLink("Tag editing jobs", if (state.busy) "${state.results.size} of ${state.tracks.size} files checked" else "Recent outcomes and interrupted work",
        icon = io.github.anastaci1a.localica.R.drawable.ic_edit, onClick = controller::showJobs)
    if (state.recovery != null) TextButton(onClick = { confirming = true }) { Text("Recover tag edit: ${state.recovery}") }
    if (confirming) GlassAlertDialog(onDismissRequest = { confirming = false }, title = { Text("Restore original file?") },
        text = { Text("This replaces ${state.recovery} with the copy saved before its interrupted edit.") },
        confirmButton = { TextButton(onClick = { confirming = false; controller.recover() }) { Text("Restore original") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

@Composable
private fun TagEditorDialog(state: TagEditorState, controller: TagEditingController) {
    val context = LocalContext.current
    val identity = state.tracks.joinToString { it.id }
    var values by rememberSaveable(identity) { mutableStateOf(JSONObject().apply {
        EditField.entries.forEach { field -> put(field.name, state.tracks.map(field::value).distinct().singleOrNull().orEmpty()) }
    }.toString()) }
    var changed by rememberSaveable(identity) { mutableStateOf(emptyList<String>()) }
    var imageUri by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var removeImage by rememberSaveable(identity) { mutableStateOf(false) }
    var choosing by remember(identity, state.artworkOnly) { mutableStateOf(state.artworkOnly) }
    var viewing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var grantMessage by remember { mutableStateOf<String?>(null) }
    val cover by produceState<CoverImage?>(null, imageUri) {
        value = null; imageError = null
        imageUri?.let { uri -> try { value = CoverImages(context).read(uri) } catch (failure: Exception) { imageError = failure.message } }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            grantMessage = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (state.tracks.none { track -> track.locations.any { it.folderUri == uri.toString() } })
                    "Choose the same music folder that was added to the library." else "Write access granted."
            }.getOrElse { "This folder provider did not grant write access." }
        }
    }
    fun patch() = TagPatch(changed.associate { name -> EditField.valueOf(name) to JSONObject(values).getString(name) },
        if (removeImage) ArtworkChange.Remove else cover?.let { ArtworkChange.Replace(it) } ?: ArtworkChange.Keep)
    if (!state.artworkOnly || !choosing) GlassDialog({ if (!state.busy) controller.close() }, GlassPlacement.Sheet) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f), color = androidx.compose.ui.graphics.Color.Transparent) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.artworkOnly) "Change artwork" else "Edit ${state.tracks.size} ${if (state.tracks.size == 1) "song" else "songs"}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !state.busy, onClick = controller::close) { Text("Close editor") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.artworkOnly) Text("Only checked fields change. A checked blank field removes that tag.", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val artworkBackdrop = rememberFrostedBackdrop(cover to removeImage, dark = LocalFrostedBackdrop.current?.dark == true)
                    Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp)) {
                        Box(Modifier.matchParentSize().recordFrostedBackdrop(artworkBackdrop, LocalFrostedBackdrop.current)) {
                        if (!removeImage) {
                            val image = cover
                            if (image == null) AlbumArtwork(state.tracks.first().uri, Modifier.fillMaxSize())
                            else {
                                val bitmap = remember(image) { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) }
                                Image(bitmap.asImageBitmap(), "New artwork preview", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                            }
                        }
                        }
                        CompositionLocalProvider(LocalFrostedBackdrop provides artworkBackdrop) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_edit, "Choose artwork", enabled = !state.busy) { choosing = true }
                            ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_trash, "Remove artwork from selected songs", enabled = !state.busy) { removeImage = true; imageUri = null }
                        }
                        }
                    }
                    TextButton(onClick = { viewing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("View current artwork") }
                    imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (removeImage) Text("Artwork will be removed", Modifier.align(Alignment.CenterHorizontally))
                    if (imageUri != null || removeImage) TextButton(enabled = !state.busy,
                        onClick = { imageUri = null; removeImage = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Keep existing artwork") }
                    if (!state.artworkOnly) EditField.entries.forEach { field ->
                        val common = state.tracks.map(field::value).distinct().size == 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InterfaceCheckbox(field.name in changed, { checked -> changed = if (checked) changed + field.name else changed - field.name }, enabled = !state.busy)
                            ClearableTextField(JSONObject(values).getString(field.name), { text ->
                                values = JSONObject(values).put(field.name, text).toString()
                                if (field.name !in changed) changed = changed + field.name
                            }, enabled = !state.busy, label = { Text(field.label) }, placeholder = { if (!common) Text("Mixed values — unchanged") },
                                modifier = Modifier.weight(1f).clearTextFocusOnBack(), singleLine = field != EditField.Comment,
                                colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                    state.tracks.flatMap { it.locations }.distinctBy { it.folderUri }.forEach { location ->
                        TextButton(enabled = !state.busy, onClick = { grant.launch(Uri.parse(location.folderUri)) }) {
                            Text("Grant write access: ${Uri.parse(location.folderUri).lastPathSegment.orEmpty().substringAfterLast(':').substringAfterLast('/').ifBlank { "music folder" }}")
                        }
                    }
                    grantMessage?.let { Text(it) }
                    Text("MP3, M4A, FLAC, Ogg Vorbis, and WAV are checked before writing. Unsupported files/providers remain read-only.", style = MaterialTheme.typography.bodySmall)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.results.forEach { result -> Text("${result.track.fileName}: ${result.message}",
                        color = if (result.saved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                    if (state.recovery != null) TextButton(enabled = !state.busy, onClick = { controller.close() }) { Text("Close editor to recover interrupted edit") }
                }
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    if (state.busy) {
                        TextButton(onClick = controller::continueInBackground) { Text("Continue in background") }
                        TextButton(onClick = controller::stopAfterCurrent) { Text("Stop after current file") }
                    }
                    Button(enabled = !state.busy && state.recovery == null && imageError == null && (imageUri == null || cover != null) &&
                        (changed.isNotEmpty() || cover != null || removeImage), onClick = { confirming = true }) { Text("Review changes") }
                }
            }
        }
    }
    if (choosing) ImageChooser({ choosing = false; if (state.artworkOnly && imageUri == null) controller.close() }) { imageUri = it; removeImage = false; choosing = false }
    if (viewing) ArtworkViewer(state.tracks.first().uri) { viewing = false }
    if (confirming) GlassAlertDialog(onDismissRequest = { confirming = false }, title = { Text("Save changes to ${state.tracks.size} files?") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { changed.forEach { name -> Text("${EditField.valueOf(name).label}: ${JSONObject(values).getString(name).ifBlank { "Remove tag" }.take(160)}") }
            if (removeImage) Text("Remove artwork") else if (cover != null) Text("Replace artwork")
            Text("Changes are written to the audio files. Playback pauses while editing a file in the active queue, then resumes after verification.") } },
        confirmButton = { TextButton(onClick = { confirming = false; controller.save(patch()) }) { Text("Save changes") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

@Composable
private fun TagJobsDialog(state: TagEditorState, controller: TagEditingController) {
    GlassDialog(controller::hideJobs, GlassPlacement.Sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tag editing jobs", style = MaterialTheme.typography.titleLarge)
            if (state.busy) {
                Text("${state.results.size} of ${state.tracks.size} files checked")
                LinearProgressIndicator(progress = { state.results.size.toFloat() / state.tracks.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = controller::stopAfterCurrent) { Text("Stop after current file") }
            }
            if (state.jobs.isEmpty()) Text("No tag editing jobs yet.")
            state.jobs.forEach { job ->
                HorizontalDivider()
                Text("${job.total} songs · ${job.status}", style = MaterialTheme.typography.titleSmall)
                job.outcomes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.recovery != null) Text("An original file is retained. Use Tag jobs and recovery in Settings before retrying.")
            TextButton(onClick = controller::hideJobs, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
