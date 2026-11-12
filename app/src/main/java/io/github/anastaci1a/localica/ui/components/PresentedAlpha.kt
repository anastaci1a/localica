package io.github.anastaci1a.localica.ui.components

import io.github.anastaci1a.localica.ui.engine.actionDragBubbleLayer
import io.github.anastaci1a.localica.ui.engine.artworkViewportLayer
import io.github.anastaci1a.localica.ui.engine.navigationChromeLayer
import io.github.anastaci1a.localica.ui.engine.playerCoverLayer
import io.github.anastaci1a.localica.ui.engine.playerPartPlacement
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.engine.presentedAlpha

/** Screens bind a reveal opacity through the Engine; the lambda is read in the draw phase, never at composition. */
internal fun Modifier.revealAlpha(alpha: () -> Float): Modifier = presentedAlpha(alpha)

/** The existing artwork viewport owns its transform; the engine binds it only while drawing. */
internal fun Modifier.artworkViewport(transform: () -> ArtworkTransform, present: Boolean = true): Modifier =
    artworkViewportLayer(
        { transform().scale }, { transform().offset.x }, { transform().offset.y }, present)

internal fun Modifier.queuePlayerReveal(playerProgress: () -> Float): Modifier =
    revealAlpha { io.github.anastaci1a.localica.ui.engine.queuePlayerAlpha(playerProgress()) }

internal fun Modifier.navigationChrome(expansion: io.github.anastaci1a.localica.ui.engine.ExpansionView,
    visibility: () -> Float, travelPx: () -> Float): Modifier =
    navigationChromeLayer(expansion, visibility, travelPx)

internal class PlayerCoverPresentation(
    internal val offset: () -> Float, internal val width: () -> Float, internal val active: () -> Boolean,
    internal val artworkAlpha: () -> Float, internal val swipeOffset: () -> Float = offset,
    internal val neighbor: Boolean = false,
) {
    fun fraction() = io.github.anastaci1a.localica.ui.engine.playerSwipeFraction(swipeOffset(), width())
}
internal enum class PlayerPart { Metadata, Artwork }
internal class PlayerPartPresentation(
    internal val expansion: io.github.anastaci1a.localica.ui.engine.ExpansionView,
    internal val lyrics: io.github.anastaci1a.localica.ui.engine.ExpansionView,
    internal val part: PlayerPart,
)
internal fun Modifier.playerCover(presentation: PlayerCoverPresentation): Modifier =
    playerCoverLayer(presentation.offset, presentation.swipeOffset,
        presentation.width, presentation.active, presentation.artworkAlpha, presentation.neighbor)
internal fun Modifier.playerPartOrder(presentation: PlayerPartPresentation): Modifier =
    playerPartPlacement(presentation.expansion, presentation.lyrics,
        presentation.part == PlayerPart.Metadata)

internal fun Modifier.actionDragBubble(origin: () -> androidx.compose.ui.geometry.Offset, position: () -> androidx.compose.ui.geometry.Offset): Modifier =
    actionDragBubbleLayer(origin, position)
