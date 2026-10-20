package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.pager.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs

import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import io.github.anastaci1a.localica.navigation.MainTab

/** Retain navigation/data state without drawing or sampling invisible pages. */
internal val LocalGlassVisible = compositionLocalOf { true }

/** Only page navigation waits longer for horizontal intent. Child lists/shelves keep normal slop. */
@Composable
internal fun TabPager(state: PagerState, tabs: () -> List<MainTab>, modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true, content: @Composable PagerScope.(Int) -> Unit) {
    val normal = LocalViewConfiguration.current
    val deliberate = remember(normal) { object : ViewConfiguration by normal {
        override val touchSlop = normal.touchSlop * 1.4f
    } }
    CompositionLocalProvider(LocalViewConfiguration provides deliberate) {
        // Observe after children: consume only navigation's horizontal movement,
        // never preempt a list or a shelf that has already claimed the gesture.
        HorizontalPager(state, modifier.modalScroll(state).pointerInput(userScrollEnabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                var rejected = false
                var accepted = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!accepted && !rejected) {
                        val delta = point.position - down.position
                        if (abs(delta.x) > normal.touchSlop || abs(delta.y) > normal.touchSlop) {
                            accepted = abs(delta.x) >= abs(delta.y) * 2f
                            rejected = !accepted
                        }
                    }
                    if (rejected && !point.isConsumed) point.consume()
                } while (event.changes.any { it.pressed })
            }
        }, userScrollEnabled = userScrollEnabled,
            beyondViewportPageCount = 1, key = { tabs()[it].name }) { page ->
            val visible by remember(state, page) { derivedStateOf {
                abs(state.currentPage + state.currentPageOffsetFraction - page) < 1f
            } }
            CompositionLocalProvider(LocalViewConfiguration provides normal, LocalGlassVisible provides visible) {
                Box(Modifier.drawWithContent { if (visible) drawContent() }, propagateMinConstraints = true) { content(page) }
            }
        }
    }
}
