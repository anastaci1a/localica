package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.launch

class TabPagerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reselectDuringSwipeSettlementDoesNotRestartThePagerOrLoseTheNextTap() {
        val tabs = listOf(MainTab.Home, MainTab.Library)
        lateinit var pager: PagerState
        var selections = 0
        var reselections = 0
        var filterTaps = 0
        compose.setContent { LocalicaTheme {
            pager = rememberPagerState { tabs.size }
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize()) {
                TabPager(pager, { tabs }, Modifier.weight(1f).testTag("swipe-pages")) { page ->
                    if (page == 1) androidx.compose.material3.Button(onClick = { filterTaps++ },
                        modifier = Modifier.fillMaxWidth().height(64.dp).testTag("next-filter")) { Text("Songs") }
                    else Text("Home")
                }
                Box(Modifier.fillMaxWidth().height(64.dp)) {
                    SlidingTabs(tabs, tabs[pager.targetPage], { pager.currentPage + pager.currentPageOffsetFraction },
                        onReselect = { reselections++ }) { tab ->
                        selections++
                        scope.launch { pager.animateScrollToPage(tabs.indexOf(tab)) }
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("swipe-pages").performTouchInput {
                swipe(Offset(width * .9f, centerY), Offset(width * .2f, centerY), 400)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(1, pager.targetPage)
                assertTrue("The swipe must still be settling", pager.isScrollInProgress)
                assertTrue("The target is not snapped yet", pager.currentPageOffsetFraction != 0f)
            }
            compose.onNodeWithContentDescription("Library").performClick()
            compose.onNodeWithTag("next-filter").performClick()
            compose.runOnIdle {
                assertEquals("Reselection cannot request another page animation", 0, selections)
                assertEquals(1, reselections)
                assertEquals("The immediate filter tap must land", 1, filterTaps)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun diagonalListScrollStaysVerticalButShelfAndDeliberatePageSwipesRemainHorizontal() {
        lateinit var pager: PagerState
        lateinit var list: LazyListState
        lateinit var shelf: LazyListState
        compose.setContent { LocalicaTheme {
            pager = rememberPagerState { 2 }
            list = rememberLazyListState()
            shelf = rememberLazyListState()
            TabPager(pager, { listOf(MainTab.Library, MainTab.Albums) }, Modifier.fillMaxSize()) { page ->
                if (page == 0) Column {
                    LazyRow(Modifier.height(120.dp).testTag("shelf"), state = shelf) {
                        items(30) { Text("Album $it", Modifier.width(130.dp).fillMaxHeight()) }
                    }
                    LazyColumn(Modifier.weight(1f).testTag("vertical"), state = list) {
                        items(100) { Text("Song $it", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                } else Text("Next page")
            }
        } }
        compose.onNodeWithTag("vertical").performTouchInput {
            swipe(center + Offset(100f, 100f), center - Offset(100f, 85f), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(0, pager.currentPage); assertTrue(list.firstVisibleItemScrollOffset > 0 || list.firstVisibleItemIndex > 0) }
        compose.onNodeWithTag("shelf").performTouchInput {
            swipe(Offset(width * .8f, height * .8f), Offset(width * .3f, height * .15f), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(0, pager.currentPage); assertTrue(shelf.firstVisibleItemScrollOffset > 0 || shelf.firstVisibleItemIndex > 0) }
        compose.onNodeWithTag("vertical").performTouchInput {
            swipe(Offset(width * .9f, center.y), Offset(width * .1f, center.y - 10f), durationMillis = 350)
        }
        compose.runOnIdle { assertEquals(1, pager.currentPage) }
    }
}
