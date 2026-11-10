package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.stateDescription
import io.github.anastaci1a.localica.ui.components.settingsSearchEntryModifier
import io.github.anastaci1a.localica.ui.layout.SettingsEntryHost
import io.github.anastaci1a.localica.ui.layout.SettingsHighlightMarker

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
    val highlighted = focus.highlighted == spec.id
    val entry = settingsSearchEntryModifier(
        owner = focus, id = spec.id, title = spec.title, page = focus.page,
        highlighted = highlighted, reduced = LocalReducedMotion.current, alphaKey = SettingsHighlightAlpha,
        onAnchor = { focus.anchors[spec.id] = it + focus.scroll.value },
        onRetire = { focus.anchors.remove(spec.id) },
    )
    SettingsEntryHost(entry) {
        CompositionLocalProvider(LocalSettingEntry provides true) { content() }
        if (highlighted) SettingsHighlightMarker(Modifier.testTag("setting-highlight:${spec.id}"))
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
