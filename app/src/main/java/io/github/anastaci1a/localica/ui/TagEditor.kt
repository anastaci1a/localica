package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.layout.rememberPopupEditorFocus
import io.github.anastaci1a.localica.ui.layout.PopupEditorBodySlot

import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight
import io.github.anastaci1a.localica.ui.layout.SaveProgressBodySlot
import io.github.anastaci1a.localica.ui.components.ControlProgress


import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import org.json.JSONObject

internal val LocalTagEdit = staticCompositionLocalOf<((List<Track>, (Boolean) -> Unit) -> Unit)?> { null }

internal val LocalArtworkEdit = staticCompositionLocalOf<((Track) -> Unit)?> { null }

@Composable
internal fun TagEditorHost(controller: TagEditingController, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    val launches = remember(controller) { TagEditorLaunches({ controller.state.value }, controller::open, controller::openArtwork) }
    CompositionLocalProvider(LocalTagEdit provides launches.editTags, LocalArtworkEdit provides launches.editArtwork,
        LocalTagEditorLaunches provides launches) { content() }
    TagEditorPresentationScope(launches.presentation(state.sessionId).takeIf { state.tracks.isNotEmpty() }) {
        if (state.tracks.isNotEmpty() && !state.background) key(state.sessionId) { TagEditorDialog(state, controller) }
        if (state.jobsVisible) TagJobsDialog(state, controller)
    }
    var recoveryDismissed by remember(state.recovery) { mutableStateOf(false) }
    if (state.recovery != null && !recoveryDismissed && state.tracks.isEmpty()) GlassAlertDialog(
        onDismissRequest = { recoveryDismissed = true }, title = { Text("Interrupted tag edit") },
        text = { Column { Text(state.recoveryProblem ?: "An original copy of ${state.recovery} is retained. Restore it to undo the interrupted edit. You can also recover from Settings.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { ActionTextButton(enabled = !state.busy, onClick = controller::recover) { Text(if (state.recoveryProblem == null) "Restore original" else "Retry recovery") } },
        dismissButton = { ActionTextButton(onClick = { recoveryDismissed = true }) { Text("Later") } })
}

@Composable
internal fun TagRecoverySettings(controller: TagEditingController) {
    val state by controller.state.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    SettingsLink("Tag editing jobs", if (state.busy) "${state.results.size} of ${state.tracks.size} files checked" else "Recent outcomes and interrupted work",
        icon = AppIcon.ActionEdit, onClick = controller::showJobs)
    if (state.recovery != null) SettingsEntry("Recover tag edit") {
        ActionTextButton(onClick = { confirming = true }) { Text("Recover tag edit: ${state.recovery}") }
    }
    if (confirming) GlassAlertDialog(onDismissRequest = { confirming = false }, title = { Text(if (state.recoveryProblem == null) "Restore original file?" else "Retry tag recovery?") },
        text = { SettingsDescription(state.recoveryProblem ?: "This replaces ${state.recovery} with the copy saved before its interrupted edit.") },
        confirmButton = { ActionTextButton(onClick = { confirming = false; controller.recover() }) { Text(if (state.recoveryProblem == null) "Restore original" else "Retry recovery") } },
        dismissButton = { ActionTextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

@Composable
internal fun TagEditorDialog(state: TagEditorState, controller: TagEditingController,
    readCover: (suspend (String) -> CoverImage)? = null, onSave: ((TagPatch) -> Unit)? = null) {
    val context = LocalContext.current
    val identity = state.tracks.joinToString { it.id }
    val retainedDraft = remember(state.draft) { state.draft?.let { runCatching { JSONObject(it) }.getOrNull() } }
    var values by rememberSaveable(identity) { mutableStateOf(retainedDraft?.optJSONObject("values")?.toString() ?: JSONObject().apply {
        EditField.entries.forEach { field -> put(field.name, state.tracks.map(field::value).distinct().singleOrNull().orEmpty()) }
    }.toString()) }
    var changed by rememberSaveable(identity) { mutableStateOf(retainedDraft?.optJSONArray("checked")?.let { array ->
        (0 until array.length()).map(array::getString) } ?: emptyList<String>()) }
    val artwork = rememberArtworkWorkflow(state.sessionId, startChoosing = state.artworkOnly)
    val imageUri = artwork.imageUri
    var removeImage by rememberSaveable(identity) { mutableStateOf(retainedDraft?.optBoolean("removeArtwork") == true) }
    val choosing = artwork.choosing
    var viewing by remember { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var grantMessage by remember { mutableStateOf<String?>(null) }
    // The encoded cover and its decoded preview publish together: a full-size decode
    // never runs in composition, and readiness still follows the encoded cover.
    val loadedCover by key(imageUri, state.draft) {
        produceState<Pair<CoverImage, androidx.compose.ui.graphics.ImageBitmap?>?>(null, imageUri, state.draft) {
            imageError = null
            if (imageUri != null || state.draft != null) {
                try {
                    val image = imageUri?.let { uri -> readCover?.invoke(uri) ?: CoverImages(context).read(uri) }
                        ?: (controller.retainedPatch()?.artwork as? ArtworkChange.Replace)?.image ?: return@produceState
                    value = image to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap()
                    }
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { imageError = failure.message ?: "Couldn’t open this image." }
            }
        }
    }
    val cover = loadedCover?.first
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            grantMessage = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (state.tracks.none { track -> track.locations.any { it.folderUri == uri.toString() } })
                    "Choose the same music folder that was added to the library." else "Write access granted."
            }.getOrElse { "This folder provider did not grant write access." }
        }
    }
    val canReview = !state.busy && state.recovery == null && imageError == null && (imageUri == null || cover != null) &&
        (changed.isNotEmpty() || cover != null || removeImage)
    fun patch() = TagPatch(changed.associate { name -> EditField.valueOf(name) to JSONObject(values).getString(name) },
        if (removeImage) ArtworkChange.Remove else cover?.let { ArtworkChange.Replace(it) } ?: ArtworkChange.Keep)
    if (!state.artworkOnly || !choosing) GlassDialog({ if (!state.busy) controller.close() }, GlassPlacement.Sheet,
        heightSizing = GlassDialogHeight.ViewportFraction(.94f)) {
        val chrome = DialogChrome.Regular
        val nativeBodyScroll = rememberScrollState()
        val nativeModal = LocalModalGestures.current
        val nativeFocus = rememberPopupEditorFocus(nativeBodyScroll) { nativeModal?.blocked != true }
        OnModalGestureCancellation { nativeFocus.modalCancellation() }
        Surface(Modifier.fillMaxWidth().fillMaxHeight().testTag("tag-editor-sheet"), color = androidx.compose.ui.graphics.Color.Transparent) {
            Column(Modifier.fillMaxSize().imePadding().testTag("tag-editor-frame").padding(chrome.perimeterDp.dp),
                verticalArrangement = Arrangement.spacedBy(chrome.gapDp.dp)) {
                Row(Modifier.fillMaxWidth().testTag("tag-editor-header"), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.artworkOnly) "Change artwork" else "Edit ${state.tracks.size} ${if (state.tracks.size == 1) "song" else "songs"}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionTextButton(enabled = !state.busy, onClick = controller::close,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Close editor") }
                }
                PopupEditorBodySlot(nativeFocus, Modifier.controlVerticalScroll(nativeBodyScroll),
                    Modifier.fillMaxWidth().weight(1f).testTag("tag-editor-body")) {
                    if (!state.artworkOnly) Text("Only checked fields change. A checked blank field removes that tag.", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val artworkBackdrop = rememberFrostedBackdrop(cover to removeImage, dark = LocalFrostedBackdrop.current?.dark == true)
                    Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp)) {
                        Box(Modifier.matchParentSize().recordFrostedBackdrop(artworkBackdrop, LocalFrostedBackdrop.current)) {
                        if (!removeImage) {
                            val preview = loadedCover?.second
                            if (cover == null) AlbumArtwork(state.tracks.first().uri, Modifier.fillMaxSize())
                            else if (preview != null) Image(preview, "New artwork preview", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        }
                        }
                        CompositionLocalProvider(LocalFrostedBackdrop provides artworkBackdrop) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionIcon(AppIcon.ActionEdit, "Choose artwork", enabled = !state.busy) { artwork.choose() }
                            ActionIcon(AppIcon.ActionTrash, "Remove artwork from selected songs", enabled = !state.busy) { removeImage = true; artwork.clearImage() }
                        }
                        }
                    }
                    ActionTextButton(onClick = { viewing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("View current artwork") }
                    imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (removeImage) Text("Artwork will be removed", Modifier.align(Alignment.CenterHorizontally))
                    if (imageUri != null || removeImage) ActionTextButton(enabled = !state.busy,
                        onClick = { artwork.clearImage(); removeImage = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Keep existing artwork") }
                    if (!state.artworkOnly) EditField.entries.forEach { field ->
                        val common = state.tracks.map(field::value).distinct().size == 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InterfaceCheckbox(field.name in changed, { checked -> changed = if (checked) changed + field.name else changed - field.name })
                            ClearableTextField(JSONObject(values).getString(field.name), { text ->
                                values = JSONObject(values).put(field.name, text).toString()
                                if (field.name !in changed) changed = changed + field.name
                            }, label = { Text(field.label) }, placeholder = { if (!common) Text("Mixed values — unchanged") },
                                modifier = Modifier.weight(1f), singleLine = field != EditField.Comment,
                                colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                    state.tracks.flatMap { it.locations }.distinctBy { it.folderUri }.forEach { location ->
                        ActionTextButton(enabled = !state.busy, onClick = { grant.launch(Uri.parse(location.folderUri)) }) {
                            Text("Grant write access: ${Uri.parse(location.folderUri).lastPathSegment.orEmpty().substringAfterLast(':').substringAfterLast('/').ifBlank { "music folder" }}")
                        }
                    }
                    grantMessage?.let { Text(it) }
                    Text("MP3, M4A, FLAC, Ogg Vorbis, and WAV are checked before writing. Unsupported files/providers remain read-only.", style = MaterialTheme.typography.bodySmall)
                    state.results.forEach { result -> Text("${result.track.fileName}: ${result.message}",
                        color = if (result.saved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                    if (state.recovery != null) ActionTextButton(enabled = !state.busy, onClick = { controller.close() }) { Text("Close editor to recover interrupted edit") }
                }
                FlowRow(Modifier.fillMaxWidth().testTag("tag-editor-footer"), horizontalArrangement = Arrangement.End) {
                    ActionButton(enabled = canReview, onClick = { confirming = true },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Review changes") }
                }
            }
        }
    }
    if (choosing) ImageChooser(artwork, { artwork.cancelChooser(); if (state.artworkOnly && imageUri == null) controller.close() }) { artwork.chosen(it); removeImage = false }
    if (viewing) ArtworkViewer(state.tracks.first().uri, state.tracks.first().let { it.albumArtist ?: it.artist }, state.tracks.first().album) { viewing = false }
    if (confirming) GlassAlertDialog(onDismissRequest = { confirming = false }, title = { Text("Save changes to ${state.tracks.size} files?") },
        text = { Column(Modifier.heightIn(max = 360.dp).controlVerticalScroll(rememberScrollState())) { changed.forEach { name -> Text("${EditField.valueOf(name).label}: ${JSONObject(values).getString(name).ifBlank { "Remove tag" }.take(160)}") }
            if (removeImage) Text("Remove artwork") else if (imageUri != null) Text("Replace artwork")
            imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Changes are written to the audio files. Playback pauses while editing a file in the active queue, then resumes after verification.") } },
        confirmButton = { ActionTextButton(enabled = canReview, onClick = {
            confirming = false
            val patch = patch()
            if (onSave != null) onSave(patch) else controller.saveDraft(patch, JSONObject()
                .put("values", JSONObject(values)).put("checked", org.json.JSONArray(changed)).put("removeArtwork", removeImage).toString())
        }) { Text("Save changes") } },
        dismissButton = { ActionTextButton(onClick = { confirming = false }) { Text("Cancel") } })
    if (state.progressVisible) TagSaveProgressDialog(state, controller)
}

@Composable
private fun TagJobsDialog(state: TagEditorState, controller: TagEditingController) {
    GlassDialog(controller::hideJobs, GlassPlacement.Sheet) {
        Column(Modifier.fillMaxWidth().controlVerticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tag editing jobs", style = MaterialTheme.typography.titleLarge)
            if (state.busy) {
                Text("${state.results.size} of ${state.tracks.size} files checked")
                LinearProgressIndicator(progress = { state.results.size.toFloat() / state.tracks.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                ActionTextButton(onClick = controller::stopAfterCurrent) { Text("Stop after current file") }
            }
            if (state.jobs.isEmpty()) Text("No tag editing jobs yet.")
            state.jobs.forEach { job ->
                HorizontalDivider()
                Text("${job.total} songs · ${job.status}", style = MaterialTheme.typography.titleSmall)
                job.outcomes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.recovery != null) Text(state.recoveryProblem ?: "An original file is retained. Use Tag jobs and recovery in Settings before retrying.")
            ActionTextButton(onClick = controller::hideJobs, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

@Composable
private fun TagSaveProgressDialog(state: TagEditorState, controller: TagEditingController) {
    val restoring = state.cancelPending || state.phase == "CancelRequested" || state.phase == "Restoring"
    GlassAlertDialog(onDismissRequest = {}, title = {
        Text(when {
            state.interruptedBatch -> "Interrupted tag edit"
            restoring -> "Restoring originals"
            else -> "Saving tags"
        })
    }, text = { SaveProgressBodySlot(DialogChrome.Regular.gapDp.dp) {
        Text(if (restoring) "Restoring ${state.restored} of ${state.touched}" else "Saved ${state.saved} of ${state.tracks.size}")
        ControlProgress(progress = {
            if (restoring) state.restored.toFloat() / state.touched.coerceAtLeast(1)
            else state.saved.toFloat() / state.tracks.size.coerceAtLeast(1)
        }, modifier = Modifier.fillMaxWidth())
        if (state.phase == "Preparing") Text("Checking files and free space…")
        state.spaceProblem?.let { Text(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.interruptedBatch) Text(if (state.undo) "Resume saving or restore the original files." else "Resume saving. Previous saved files cannot be undone.")
        state.results.filter { !it.saved }.forEach { Text("${it.track.fileName}: ${it.message}") }
    } }, confirmButton = {
        when {
            state.spaceProblem != null -> ActionTextButton(enabled = !state.busy, onClick = controller::saveWithoutUndo) { Text("Save without undo") }
            (state.interruptedBatch && !restoring) || (!state.busy && state.batchId != null && state.phase != "AwaitingDecision" && !restoring) ->
                ActionTextButton(enabled = !state.busy, onClick = controller::resumeBatch) { Text("Resume") }
            !state.busy && state.phase == "AwaitingDecision" && !restoring -> {
                if (state.saved < state.tracks.size) ActionTextButton(onClick = controller::resumeBatch) { Text("Retry failed files") }
                ActionTextButton(onClick = controller::finishBatch) { Text("Done") }
            }
            state.batchId != null && !restoring -> ActionTextButton(onClick = controller::continueInBackground) { Text("Continue in background") }
            state.busy && state.phase == "Preparing" -> ActionTextButton(enabled = false, onClick = {}) { Text("Continue in background") }
        }
    }, dismissButton = {
        when {
            state.batchId == null && !state.busy -> ActionTextButton(onClick = controller::dismissPreparation) { Text("Back") }
            state.undo -> ActionTextButton(enabled = !restoring || !state.busy, onClick = controller::cancelBatch) {
                Text(if (state.interruptedBatch || restoring) "Restore" else "Cancel")
            }
            else -> ActionTextButton(enabled = state.busy, onClick = controller::stopAfterCurrent) { Text("Stop after current") }
        }
    })
}
