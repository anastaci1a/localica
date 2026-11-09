package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.artistphoto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Leaf chooser: shared modal, buttons, scroll handling and artwork reveal own presentation. */
@Composable
internal fun ArtistPhotoChooser(actions: ArtistPhotoActions) {
    val environment = actions.environment
    val allowed by environment.consent.allowed.collectAsState()
    val revision by environment.photos.changes.collectAsState()
    val work = environment.work?.status?.collectAsState()?.value
    var page by remember(actions) { mutableStateOf(ArtistPhotoCandidates()) }
    var selection by remember(actions) { mutableStateOf(ArtistPhotoSelection(null, null, false)) }
    var draft by remember(actions) { mutableStateOf<ArtistPhotoChoice?>(null) }
    var failed by remember(actions) { mutableStateOf<ArtistPhotoChoice?>(null) }
    var submitting by remember(actions) { mutableStateOf(false) }
    var lookup by remember(actions) { mutableIntStateOf(0) }
    LaunchedEffect(actions, revision) {
        selection = environment.photos.selection(actions.request.artistKey)
        if (draft == null) draft = selection.pending ?: selection.current
    }
    LaunchedEffect(actions, allowed, lookup) {
        page = ArtistPhotoCandidates()
        if (allowed) environment.photos.candidatePages(actions.request).collect { page = it }
    }
    fun choose(choice: ArtistPhotoChoice) {
        actions.scope.launch {
            submitting = true; failed = null
            try {
                // Persist intent and schedule together even when the artist screen closes meanwhile.
                withContext(NonCancellable + Dispatchers.IO) {
                    environment.photos.pick(actions.request.artistKey, choice)
                    if (environment.work != null) environment.work.enqueue(actions.request, refresh = true)
                    else environment.photos.observe(actions.request, refresh = true).collect { if (it.failed) failed = choice }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = choice }
            finally { submitting = false }
        }
    }
    GlassAlertDialog({ actions.open = false }, title = { Text("Choose artist photo") }, text = {
        val scroll = rememberScrollState()
        Column(Modifier.heightIn(max = 420.dp).controlVerticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(actions.request.name)
            if (!allowed) Text("Enable Online features before choosing a photo.")
            else {
                if (page.loading) {
                    Text("Finding artist photos…")
                    if (page.items.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) { ArtworkImage(null, true, Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
                page.items.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { candidate -> key(candidate.choice) {
                            val choice = candidate.choice
                            val isFailed = failed == choice || selection.pending == choice &&
                                (selection.failed || work?.failed?.contains(actions.request.artistKey) == true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ActionTextButton({ draft = choice }, Modifier.fillMaxWidth(), enabled = allowed) {
                                    Column(Modifier.fillMaxWidth()) {
                                        ArtistPhotoPreview(environment, candidate, allowed)
                                        Text("${candidate.name} · ${choice.provider.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
                                        if (draft == choice) Text("Selected")
                                    }
                                }
                                Text(candidate.releases.take(3).joinToString(" · "))
                                if (!candidate.overlapsLibrary) Text("No matching local release; check the artist before choosing.")
                                when {
                                    isFailed -> {
                                        Text("Could not apply this photo. Previous photo kept.")
                                        ActionTextButton({ choose(choice) }, enabled = !submitting) { Text("Retry") }
                                    }
                                    selection.pending == choice -> Text("Applying…")
                                    selection.current == choice -> Text("Current photo")
                                }
                            }
                        } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (!page.loading && page.items.isEmpty()) Text("No artist photos found. Album art is kept.")
                if (page.failed) {
                    Text("Some photo sources could not be loaded.")
                    ActionTextButton({ lookup++ }) { Text("Retry lookup") }
                }
            }
        }
    }, confirmButton = {
        ActionTextButton({ draft?.let(::choose) }, enabled = allowed && draft != null && !submitting) { Text("Select") }
    }, dismissButton = { ActionTextButton({ actions.open = false }) { Text("Close") } })
}

@Composable
private fun ArtistPhotoPreview(environment: ArtistPhotoEnvironment, candidate: ArtistPhotoCandidate, allowed: Boolean) {
    var preview by remember(candidate.choice) { mutableStateOf<ArtistPhotoVisual?>(null) }
    var loading by remember(candidate.choice) { mutableStateOf(true) }
    DisposableEffect(environment, candidate.choice, allowed) {
        val lease = if (allowed) environment.photos.retainPreview(candidate.choice) else null
        onDispose { lease?.close() }
    }
    LaunchedEffect(environment, candidate.choice, allowed) {
        if (allowed) {
            try {
                val photo = environment.photos.preview(candidate.choice)
                preview = photo?.let { withContext(Dispatchers.IO) { ArtistPhotoPixels.read(it, thumbnail = true) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { preview = null }
            finally { loading = false }
        }
    }
    ArtworkImage(if (allowed) preview?.image else null, allowed && loading,
        Modifier.fillMaxWidth().aspectRatio(1f).testTag("artist-photo-preview:${candidate.choice}").semantics {
            contentDescription = when {
                loading -> "Loading photo preview for ${candidate.name}"
                preview != null -> "Photo preview for ${candidate.name}"
                else -> "Photo preview unavailable for ${candidate.name}"
            }
        })
    if (!loading && preview == null) Text("Preview unavailable")
    if (allowed) preview?.photo?.attribution?.let { Text("${it.artist} · ${it.licence}", style = MaterialTheme.typography.labelSmall) }
}
