package io.github.anastaci1a.localica.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import io.github.anastaci1a.localica.editing.TagEditorState
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.GlassAppearance

/** Only presentation values cross the launch boundary, never a retiring popup's layers or view locals. */
internal data class TagEditorPresentation(val ink: Color, val colors: ColorScheme, val appearance: GlassAppearance)

@Composable
internal fun currentTagEditorPresentation() = TagEditorPresentation(
    LocalContentColor.current.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.onSurface,
    MaterialTheme.colorScheme.copy(), LocalGlassAppearance.current)

@Composable
internal fun TagEditorPresentationScope(presentation: TagEditorPresentation?, content: @Composable () -> Unit) {
    if (presentation == null) { content(); return }
    CompositionLocalProvider(LocalGlassAppearance provides presentation.appearance) {
        MaterialTheme(colorScheme = presentation.colors) {
            val ink = presentation.ink.takeIf { it.isSpecified } ?: presentation.colors.onSurface
            GlassPanelTheme(ink.isSpecified && ink.luminance() > .5f, content)
        }
    }
}

internal data class SongInformationRequest(val track: Track, val presentation: TagEditorPresentation)

/** UI ownership follows the controller's accepted session, not the requested tracks or a global theme. */
internal class TagEditorLaunches(private val state: () -> TagEditorState,
    val editTags: (List<Track>, (Boolean) -> Unit) -> Unit,
    val editArtwork: (Track) -> Unit) {
    private data class Accepted(val sessionId: String, val presentation: TagEditorPresentation)
    private var accepted by mutableStateOf<Accepted?>(null)

    fun presentation(sessionId: String): TagEditorPresentation? = accepted?.takeIf { it.sessionId == sessionId }?.presentation

    private fun launch(presentation: TagEditorPresentation, action: () -> Unit) {
        val before = state().sessionId
        action()
        // open/openArtwork are synchronous: accepted opens publish a fresh session UUID after
        // the old completion callback; a busy rejection only shows jobs and keeps that UUID.
        val after = state().sessionId
        if (after != before) accepted = Accepted(after, presentation)
    }

    fun openTags(presentation: TagEditorPresentation, tracks: List<Track>, complete: (Boolean) -> Unit) =
        launch(presentation) { editTags(tracks, complete) }

    fun openArtwork(presentation: TagEditorPresentation, track: Track) =
        launch(presentation) { editArtwork(track) }
}

internal val LocalTagEditorLaunches = staticCompositionLocalOf<TagEditorLaunches?> { null }

/** Bind inside the rendered action/menu/footer; a callback read above its theme cannot inherit it. */
@Composable
internal fun presentedTagEdit(): ((List<Track>, (Boolean) -> Unit) -> Unit)? {
    val edit = LocalTagEdit.current ?: return null
    val launches = LocalTagEditorLaunches.current
    val presentation = currentTagEditorPresentation()
    // Nested/custom legacy providers remain authoritative, including tests without a host.
    return if (launches != null && edit === launches.editTags) {
        { tracks, complete -> launches.openTags(presentation, tracks, complete) }
    } else edit
}

@Composable
internal fun presentedArtworkEdit(): ((Track) -> Unit)? {
    val edit = LocalArtworkEdit.current ?: return null
    val launches = LocalTagEditorLaunches.current
    val presentation = currentTagEditorPresentation()
    return if (launches != null && edit === launches.editArtwork) {
        { track -> launches.openArtwork(presentation, track) }
    } else edit
}
