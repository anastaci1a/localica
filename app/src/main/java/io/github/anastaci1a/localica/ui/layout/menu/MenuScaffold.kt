package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.spec.MenuGap
import io.github.anastaci1a.localica.ui.spec.MenuSpecResult
import io.github.anastaci1a.localica.ui.spec.MenuSize

/** Q5 default (per page): if any row of the page has an icon or marker, every row's text starts after the leading slot. */
internal fun PageContent.reservesLeading(): Boolean {
    val sections = when (val body = body) { is SectionsBody -> body.sections; is TabsBody -> body.tabs.flatMap { it.sections } }
    return sections.any { section -> section.rows.any { row -> when (row) {
        is NavContent -> row.iconKey != null
        is LinkContent -> row.iconKey != null || row.marker != null
        is InfoContent -> row.iconKey != null
        is NoticeContent, is LegendContent -> true
        else -> false
    } } }
}

/**
 * The one page scaffold (DESIGN 3.6): optional pinned tab strip, then one stream of items with exactly one gap
 * between neighbours. It renders only an admitted page; null (a rejected declaration) shows the failure state.
 *
 * Pages with record lists use the lazy stream. Until the lazy path is proved at M1, other pages use a non-lazy
 * column: a second container chosen here, never by pages, with the same row renderers, metrics and gaps.
 *
 * [scrollable] and [fade] are behaviour supplied by the binder; [tabs] and [item] draw live controls.
 */
@Composable fun MenuScaffold(page: AcceptedPage?, scrollable: @Composable (ScrollState) -> Modifier, fade: @Composable (ScrollState) -> Modifier,
    tabs: @Composable (TabsBody, SegmentMetrics) -> Unit, compact: Boolean = false, item: @Composable (FlatItem) -> Unit) {
    val metrics = rememberMenuMetrics()
    val content = page?.content
    val reserves = remember(content) { content?.reservesLeading() ?: false }
    val states = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A rejected token join has no metrics to draw even the failure state with: that is a build defect, not a page state.
        val resolved = metrics ?: error("The stock menu tokens were rejected: ${(menuSpecBase() as MenuSpecResult.Rejected).reasons}")
        val inset = resolved.dp(MenuSize.PageInset)
        val width = maxWidth - inset * 2
        val environment = remember(resolved, reserves, width, compact) { MenuEnvironment(resolved, reserves, width, compact) }
        CompositionLocalProvider(LocalMenuEnvironment provides environment) {
            if (content == null) MenuFailureSlot() else Column(Modifier.fillMaxSize()) {
                val body = content.body
                if (body is TabsBody) MenuTabStripSlot(body.tabs.map { it.label }, fade) { tabs(body, it) }
                val sections = content.panel()
                val flat = remember(sections) { flatten(sections, afterTabs = body is TabsBody) }
                val lazy = remember(sections) { sections.any { section -> section.rows.any { it is RecordsContent } } }
                // Each tab keeps its own scroll; a tab shown for the first time starts at the top.
                states.SaveableStateProvider((body as? TabsBody)?.selected ?: content.pageKey) {
                    if (lazy) LazyStream(flat, resolved, item) else ColumnStream(flat, resolved, scrollable, item)
                }
            }
        }
    }
}

@Composable private fun ColumnScope.LazyStream(flat: FlatPage, metrics: ResolvedMenuMetrics, item: @Composable (FlatItem) -> Unit) {
    val inset = metrics.dp(MenuSize.PageInset)
    LazyColumn(Modifier.fillMaxWidth().weight(1f), rememberLazyListState(),
        contentPadding = PaddingValues(start = inset, end = inset, bottom = metrics.dp(MenuGap.PageBottom))) {
        items(flat.count, key = { flat.item(it).key }, contentType = { flat.item(it).contentType }) { index ->
            Box(Modifier.fillMaxWidth().padding(top = metrics.dp(flat.gapBefore(index)))) { item(flat.item(index)) }
        }
    }
}

@Composable private fun ColumnScope.ColumnStream(flat: FlatPage, metrics: ResolvedMenuMetrics, scrollable: @Composable (ScrollState) -> Modifier,
    item: @Composable (FlatItem) -> Unit) {
    val inset = metrics.dp(MenuSize.PageInset)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().weight(1f).then(scrollable(scroll)).padding(start = inset, end = inset, bottom = metrics.dp(MenuGap.PageBottom))) {
        for (index in 0 until flat.count) {
            val entry = flat.item(index)
            androidx.compose.runtime.key(entry.key) {
                Box(Modifier.fillMaxWidth().padding(top = metrics.dp(flat.gapBefore(index)))) { item(entry) }
            }
        }
    }
}
