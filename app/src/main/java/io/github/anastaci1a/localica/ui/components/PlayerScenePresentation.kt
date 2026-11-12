package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.engine.ExpansionView
import io.github.anastaci1a.localica.ui.engine.MotionValue
import io.github.anastaci1a.localica.ui.engine.PlayerParticipation
import io.github.anastaci1a.localica.ui.engine.playerParticipation
import io.github.anastaci1a.localica.ui.engine.playerSceneBackground
import io.github.anastaci1a.localica.ui.engine.playerSceneSoftness
import io.github.anastaci1a.localica.ui.engine.playerTitleInputBand

/** A deferred sample of the existing owners and recipes; sampling this in composition remains a lint violation. */
@Stable internal class PlayerScenePresentation internal constructor(
    private val expansion: ExpansionView, private val lyrics: ExpansionView,
    private val background: MotionValue, private val crop: MotionValue,
    private val lyricsOpen: () -> Boolean, private val swipeActive: () -> Boolean, private val swipeOffset: () -> Float,
) {
    private fun facts(p: Float, lyric: PlayerLyricsMotion, row: PlayerRowMotion, reveal: Float) =
        playerParticipation(p, lyric.layout, lyric.contentAlpha, reveal, row.seekAlpha, row.actionsAlpha,
            PlayerReveal.Handle.at(p), lyricsOpen(), swipeActive(), swipeOffset())

    internal fun participation(): PlayerParticipation {
        val p = expansion.phase.value()
        val l = lyrics.phase.value()
        return facts(p, playerLyricsMotion(p, l), playerRowMotion(p, 0f, 0f, l), PlayerReveal.Background.at(p))
    }

    fun frame(): PlayerSceneFrame {
        val p = expansion.phase.value()
        val l = lyrics.phase.value()
        val lyric = playerLyricsMotion(p, l)
        val row = playerRowMotion(p, 0f, 0f, l)
        val reveal = PlayerReveal.Background.at(p)
        return PlayerSceneFrame(p, l, background.value(), crop.value(), lyric, reveal,
            facts(p, lyric, row, reveal))
    }
}
internal data class PlayerSceneFrame(
    val expansion: Float, val lyricsVisibility: Float, val backgroundProgress: Float, val cropRetention: Float,
    val lyrics: PlayerLyricsMotion, val backgroundReveal: Float,
    val participation: PlayerParticipation,
) {
    fun rows(titleOffset: Float, transportOffset: Float) = playerRowMotion(expansion, titleOffset, transportOffset, lyricsVisibility)
    fun contentScale(miniArtworkPixels: Float) = playerArtworkContentScale(miniArtworkPixels, cropRetention, participation.collapsed)
    fun backgroundWeight() = playerSceneBackground(backgroundProgress, lyrics.layout)
    fun softness(coverBlend: Float, swipeFeather: Float) = playerSceneSoftness(coverBlend, swipeFeather)
}

/** Only discrete facts are published to composition; continuous samples stay in measure, placement and draw. */
@Composable internal fun rememberPlayerParticipation(presentation: PlayerScenePresentation): State<PlayerParticipation> =
    remember(presentation) { derivedStateOf(structuralEqualityPolicy()) { presentation.participation() } }

internal fun playerInputBand(statusAndControls: Int, origin: Int, height: Int, seek: Int, padding: Int): io.github.anastaci1a.localica.ui.engine.PlayerInputBand =
    playerTitleInputBand(statusAndControls, origin, height, seek, padding)

internal enum class PlayerSceneContent { Lyrics, Seek, Actions, Handle, Background, BaseBackground }

@Composable internal fun PlayerSceneContent(presentation: PlayerScenePresentation, part: PlayerSceneContent,
    content: @Composable () -> Unit) {
    val facts = rememberPlayerParticipation(presentation)
    io.github.anastaci1a.localica.ui.engine.playerSceneValue(facts, { state ->
        when (part) {
            PlayerSceneContent.Lyrics -> state.lyricsVisible
            PlayerSceneContent.Seek -> state.seekVisible
            PlayerSceneContent.Actions -> state.actionsVisible
            PlayerSceneContent.Handle -> state.handleVisible
            PlayerSceneContent.Background -> state.backgroundVisible
            PlayerSceneContent.BaseBackground -> !state.backgroundComplete
        }
    }, content)
}

@Composable internal fun <T> playerLyricsData(presentation: PlayerScenePresentation,
    load: @Composable () -> T): T? = io.github.anastaci1a.localica.ui.engine.playerSceneValue(
        rememberPlayerParticipation(presentation), { it.retainLyrics }, load)

/** Stable ownership with current callbacks, so a retained scene never freezes an old intent or swipe source. */
@Composable internal fun rememberPlayerScene(expansion: ExpansionView, lyrics: ExpansionView,
    background: MotionValue, crop: MotionValue, lyricsOpen: () -> Boolean,
    swipeActive: () -> Boolean, swipeOffset: () -> Float): PlayerScenePresentation {
    val currentOpen = rememberUpdatedState(lyricsOpen)
    val currentActive = rememberUpdatedState(swipeActive)
    val currentOffset = rememberUpdatedState(swipeOffset)
    return remember(expansion, lyrics, background, crop) {
        PlayerScenePresentation(expansion, lyrics, background, crop,
            { currentOpen.value() }, { currentActive.value() }, { currentOffset.value() })
    }
}
