package io.github.anastaci1a.localica.ui.spec

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed delivered player row dimensions, independent of preference controls and global spec overrides. */
@Immutable
internal data class PlayerPresentationSpec private constructor(val handleRowHeight: Dp, val actionRowHeight: Dp) {
    init {
        require(handleRowHeight.value.isFinite() && handleRowHeight.value > 0f)
        require(actionRowHeight.value.isFinite() && actionRowHeight.value > 0f)
    }

    companion object {
        val Stock = PlayerPresentationSpec(handleRowHeight = 48.dp, actionRowHeight = 48.dp)
    }
}
