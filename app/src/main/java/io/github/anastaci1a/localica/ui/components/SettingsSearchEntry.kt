package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import io.github.anastaci1a.localica.ui.engine.rememberSettingsSearchHighlight

/** Owns entry focus, anchor lifetime and the overlay sampled after opaque row content. */
@Composable
internal fun settingsSearchEntryModifier(
    owner: Any,
    id: String,
    title: String,
    page: String,
    highlighted: Boolean,
    reduced: Boolean,
    alphaKey: SemanticsPropertyKey<Float>,
    onAnchor: (Float) -> Unit,
    onRetire: () -> Unit,
): Modifier {
    val requester = remember { FocusRequester() }
    val view = LocalView.current
    val tint = MaterialTheme.colorScheme.primary
    val currentAnchor = rememberUpdatedState(onAnchor)
    val alpha = rememberSettingsSearchHighlight(highlighted, reduced) {
        requester.requestFocus()
        view.announceForAccessibility("$title, $page")
    }
    DisposableEffect(owner, id) { onDispose { onRetire() } }
    return Modifier.testTag("setting:$id")
        .onGloballyPositioned { currentAnchor.value(it.positionInWindow().y) }
        .focusRequester(requester).focusable()
        .semantics { this[alphaKey] = alpha.value }
        .drawWithContent {
            drawContent()
            if (alpha.value > 0f) drawRect(tint.copy(alpha = alpha.value))
        }
}
