package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import io.github.anastaci1a.localica.ui.engine.consumeSceneDragScroll
import io.github.anastaci1a.localica.ui.engine.navigateScenePage
import io.github.anastaci1a.localica.ui.engine.activateScenePage
import io.github.anastaci1a.localica.ui.engine.accessibilitySceneScrollBy
import io.github.anastaci1a.localica.ui.engine.sceneListPlacement
import io.github.anastaci1a.localica.ui.engine.sceneBrowseBounds

internal suspend fun PagerState.navigateToPage(target: Int, reduced: Boolean, faster: Boolean) = navigateScenePage(target, reduced, faster)
internal suspend fun PagerState.activatePage(target: Int, reduced: Boolean) = activateScenePage(target, reduced)
internal suspend fun LazyGridState.accessibilityScrollBy(delta: Float): Float = accessibilitySceneScrollBy(delta)
internal enum class ListItemContent { Browser, ActionPool }
internal fun Modifier.listPlacement(scope: LazyGridItemScope, enabled: Boolean = true, reduced: Boolean = false, placement: Boolean = true, content: ListItemContent = ListItemContent.Browser): Modifier = sceneListPlacement(scope, enabled, reduced, placement, content == ListItemContent.ActionPool)
internal fun Modifier.browseBounds(lookahead: LookaheadScope, enabled: Boolean, reduced: Boolean): Modifier = sceneBrowseBounds(lookahead, enabled, reduced)

internal suspend fun androidx.compose.foundation.gestures.ScrollableState.consumeDragScroll(delta: Float): Float = consumeSceneDragScroll(delta)
