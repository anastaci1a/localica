package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import io.github.anastaci1a.localica.navigation.MainTab

/** Only page navigation waits longer for horizontal intent. Child lists/shelves keep normal slop. */
@Composable
internal fun TabPager(state: PagerState, tabs: List<MainTab>, modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true, content: @Composable PagerScope.(Int) -> Unit) {
    val normal = LocalViewConfiguration.current
    val deliberate = remember(normal) { object : ViewConfiguration by normal {
        override val touchSlop = normal.touchSlop * 1.4f
    } }
    CompositionLocalProvider(LocalViewConfiguration provides deliberate) {
        HorizontalPager(state, modifier, userScrollEnabled = userScrollEnabled,
            beyondViewportPageCount = 1, key = { tabs[it].name }) { page ->
            CompositionLocalProvider(LocalViewConfiguration provides normal) { content(page) }
        }
    }
}
