package io.github.anastaci1a.localica.ui

/** Selected mode is independent of the expanded target, which only changes on release. */
internal data class PlayerLyricsMotion(val layout: Float, val contentAlpha: Float, val artworkAlpha: Float)

internal fun playerLyricsMotion(expansion: Float, modeVisibility: Float): PlayerLyricsMotion {
    val p = expansion.coerceIn(0f, 1f)
    val mode = modeVisibility.coerceIn(0f, 1f)
    return PlayerLyricsMotion(mode * p, mode * playerReveal((p - .78f) / .22f), 1f - mode * p)
}

internal data class PlayerRowMotion(val seekOffset: Float, val actionsOffset: Float,
    val seekAlpha: Float, val actionsAlpha: Float)

/** Follow distinct mixtures of the unchanged title/transport displacement from their full targets.
 * The common surface translation cancels out, so both rows stay between those reference motions.
 * Only a mode change can restore album actions: collapsing selected lyrics must never reveal them. */
internal fun playerRowMotion(expansion: Float, titleOffset: Float, transportOffset: Float,
    lyricsModeVisibility: Float): PlayerRowMotion {
    val p = expansion.coerceIn(0f, 1f)
    return PlayerRowMotion(
        seekOffset = titleOffset + (transportOffset - titleOffset) * .45f,
        actionsOffset = titleOffset + (transportOffset - titleOffset) * .62f,
        seekAlpha = playerReveal((p - .8f) / .2f),
        actionsAlpha = playerReveal((p - .83f) / .17f) * (1f - lyricsModeVisibility.coerceIn(0f, 1f)))
}

private fun playerReveal(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
