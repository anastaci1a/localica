package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import io.github.anastaci1a.localica.ui.spec.StockUiSpec

internal suspend fun PagerState.navigateScenePage(target: Int, reduced: Boolean, faster: Boolean) {
    if (reduced) scrollToPage(target)
    else animateScrollToPage(target, animationSpec = sceneTween(StockUiSpec, MotionProfile.NavigationRoute, false, faster))
}

internal suspend fun PagerState.activateScenePage(target: Int, reduced: Boolean) {
    if (reduced) scrollToPage(target)
    else {
        val page = StockUiSpec.spring.getValue(io.github.anastaci1a.localica.ui.spec.SpringProfile.Page)
        animateScrollToPage(target, animationSpec = spring(dampingRatio = page.dampingRatio, stiffness = page.stiffness))
    }
}

internal suspend fun LazyGridState.accessibilitySceneScrollBy(delta: Float): Float = animateScrollBy(delta)

internal fun Modifier.sceneListPlacement(scope: LazyGridItemScope, enabled: Boolean, reduced: Boolean, placement: Boolean, actionPool: Boolean): Modifier = with(scope) {
    if (!enabled) this@sceneListPlacement else if (actionPool) this@sceneListPlacement.animateItem() else
        this@sceneListPlacement.animateItem(placementSpec = if (placement) sceneTween(StockUiSpec, MotionProfile.Placement, reduced, false) else null, fadeInSpec = null, fadeOutSpec = null)
}

@OptIn(ExperimentalSharedTransitionApi::class)
internal fun Modifier.sceneBrowseBounds(lookahead: LookaheadScope, enabled: Boolean, reduced: Boolean): Modifier =
    animateBounds(lookahead, boundsTransform = { _, _ -> sceneTween(StockUiSpec, MotionProfile.Placement, reduced || !enabled, false) })

/** Foundation consumes only the shared edge owner's already normalized drag delta. */
internal suspend fun androidx.compose.foundation.gestures.ScrollableState.consumeSceneDragScroll(delta: Float): Float = scrollBy(delta)
