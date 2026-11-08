package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay

internal val SettingsHighlightAlpha = SemanticsPropertyKey<Float>("SettingsHighlightAlpha")

internal class SettingsSearchFocus(val page: String, val scroll: ScrollState) {
    val anchors = mutableStateMapOf<String, Float>()
    var viewportTop by mutableFloatStateOf(0f)
    var viewportHeight by mutableIntStateOf(0)
    var highlighted by mutableStateOf<String?>(null)
}
internal val LocalSettingsSearchFocus = compositionLocalOf<SettingsSearchFocus?> { null }
private val LocalSettingEntry = compositionLocalOf { false }

/** Shared rows register the same catalog identity used by global search. Popups do not register nested rows. */
@Composable
internal fun SettingsEntry(label: String, content: @Composable () -> Unit) {
    val focus = LocalSettingsSearchFocus.current
    val spec = focus?.let { SettingsCatalog.entry(it.page, label) }
    if (focus == null || spec == null || LocalSettingEntry.current) { content(); return }
    val requester = remember { FocusRequester() }
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(0f) }
    val highlighted = focus.highlighted == spec.id
    LaunchedEffect(highlighted) {
        if (highlighted) {
            requester.requestFocus()
            view.announceForAccessibility("${spec.title}, ${focus.page}")
            if (reduced) alpha.snapTo(.18f) else alpha.animateTo(.18f, tween(150))
            delay(if (reduced) 1200 else 450)
            if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, tween(600))
        } else alpha.snapTo(0f)
    }
    DisposableEffect(focus, spec.id) { onDispose { focus.anchors.remove(spec.id) } }
    Box(Modifier.fillMaxWidth().testTag("setting:${spec.id}")
        .onGloballyPositioned { focus.anchors[spec.id] = it.positionInWindow().y + focus.scroll.value }
        .focusRequester(requester).focusable()
        .semantics { this[SettingsHighlightAlpha] = alpha.value }
        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha.value))) {
        CompositionLocalProvider(LocalSettingEntry provides true) { content() }
        if (highlighted) Box(Modifier.matchParentSize().testTag("setting-highlight:${spec.id}"))
    }
}

@Composable
internal fun SettingsSearchResults(query: String, values: Map<String, String>, onSelect: (SettingsCatalog.Result) -> Unit) {
    val results = remember(query) { SettingsCatalog.search(query) }
    if (results.isEmpty()) {
        Text("No settings match \"$query\"")
        Text("Try a different word, like blur, queue or tabs", style = MaterialTheme.typography.bodyMedium)
    }
    results.forEach { result ->
        SettingsLink(result.title, SettingsCatalog.breadcrumb(result.page) + (values[result.setting?.id]?.let { " · $it" } ?: "") +
            (result.setting?.revealedBy?.let { " · If hidden, open $it" } ?: ""),
            modifier = Modifier.testTag("search-result:${result.title}").semantics {
                stateDescription = SettingsCatalog.breadcrumb(result.page)
            }) { onSelect(result) }
    }
}

internal val LocalSettingsNavigate = compositionLocalOf<(String) -> Unit> { {} }

internal val LocalSettingsEnabled = compositionLocalOf { true }
