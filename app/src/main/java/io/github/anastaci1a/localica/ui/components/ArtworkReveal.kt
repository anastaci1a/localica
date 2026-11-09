package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import io.github.anastaci1a.localica.ui.engine.rememberArtworkRevealState

/** The shared artwork component retains one reveal owner through present/absent
 * changes. Callers receive a read-only view; only the owner drives its motion. */
@Composable
internal fun rememberArtworkReveal(present: Boolean, reducedMotion: Boolean,
    onPresentSettled: () -> Unit): State<Float> =
    rememberArtworkRevealState(present, reducedMotion, onPresentSettled)
