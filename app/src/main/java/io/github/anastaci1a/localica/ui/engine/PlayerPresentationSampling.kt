package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Stable existing-map binding. The host's ledgered reads supply this composition's exact values once. */
internal class PlayerPresentationSampling(
    private val lyricsReveal: (Float) -> Float,
    private val handleReveal: (Float) -> Float,
    private val backgroundReveal: (Float) -> Float,
    private val actionsReveal: (Float) -> Float,
) {
    fun sample(expansion: Float, lyricsVisibility: Float): PlayerPresentationSample =
        PlayerPresentationSample(expansion, lyricsVisibility, lyricsReveal, handleReveal, backgroundReveal, actionsReveal)
}

/** Immutable composition sample; no new MotionValue read, clock, trajectory or retained endpoint. */
@Immutable
internal class PlayerPresentationSample internal constructor(
    private val expansion: Float,
    private val lyricsVisibility: Float,
    private val lyricsReveal: (Float) -> Float,
    private val handleReveal: (Float) -> Float,
    private val backgroundReveal: (Float) -> Float,
    private val actionsReveal: (Float) -> Float,
) {
    fun lyricsOwnInput(): Boolean {
        val p = expansion.coerceIn(0f, 1f)
        val mode = lyricsVisibility.coerceIn(0f, 1f)
        val contentAlpha = mode * lyricsReveal(p)
        return contentAlpha > 0f
    }

    /** The None atlas is admitted only at the exact collapsed phase, including during held input. */
    fun <T> collapsedMaterial(input: T?): T? = input.takeIf { expansion == 0f }

    /** Admission and the optical callback borrow one composition sample, including at the strict zero boundary. */
    @Composable
    fun Handle(content: @Composable (Float) -> Unit) {
        val alpha = handleReveal(expansion)
        if (alpha > 0f) content(alpha)
    }

    /** Keep the original conditional content lifetime and composition-sampled graphics alpha. */
    @Composable
    fun Actions(content: @Composable (Modifier) -> Unit) {
        val p = expansion.coerceIn(0f, 1f)
        val alpha = actionsReveal(p) * (1f - lyricsVisibility.coerceIn(0f, 1f))
        if (alpha > 0f) content(Modifier.graphicsLayer { this.alpha = alpha })
    }

    /** Preserve the original ordered admission and successful-composition commit to the caller's existing storage. */
    @Composable
    fun <T> RetainEndpoint(
        geometry: T?, playerOpen: Boolean, swipeActive: Boolean, swipeOffset: Float,
        lyricsSettled: Boolean, lyricsOpen: Boolean, queueClosed: Boolean,
        background: MotionValue, backgroundTarget: Float, aspect: Float, artworkAspect: Float,
        crop: MotionValue, cropTarget: Float, commit: (T) -> Unit,
    ) {
        if (geometry != null && playerOpen && backgroundReveal(expansion) >= 1f && !swipeActive && swipeOffset == 0f &&
            lyricsSettled && !lyricsOpen && queueClosed && background.settled && background.target == backgroundTarget &&
            aspect == artworkAspect && crop.settled && crop.target == cropTarget)
            SideEffect { commit(geometry) }
    }
}
