package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.unit.Density

/** Autofocus observes the closing boundary, not each intermediate inset distance. */
@Composable
internal fun rememberImeClosing(ime: WindowInsets, source: WindowInsets, target: WindowInsets,
    density: Density): State<Boolean> = remember(ime, source, target, density) {
    derivedStateOf(structuralEqualityPolicy()) {
        target.getBottom(density) == 0 &&
            (ime.getBottom(density) > 0 || source.getBottom(density) > 0)
    }
}
