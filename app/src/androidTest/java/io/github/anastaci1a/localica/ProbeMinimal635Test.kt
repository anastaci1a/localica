package io.github.anastaci1a.localica

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.easeToTop
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
class ProbeMinimal635Test {
    @get:Rule val compose = createComposeRule()

    private fun run(frameMs: Long) {
        val state = LazyGridState(280)
        val cells = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
        val arts = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            LookaheadScope {
                LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize(), state) {
                    itemsIndexed((0 until 300).toList(), key = { _, n -> n }, contentType = { _, _ -> "album" }) { _, n ->
                        Column(Modifier.fillMaxWidth()) {
                            Column(Modifier.animateBounds(this@LookaheadScope, Modifier.fillMaxWidth().padding(4.dp),
                                boundsTransform = { _, _ -> tween(350) })
                                .onGloballyPositioned { cells[n] = it.boundsInRoot() }) {
                                Box(Modifier.animateBounds(this@LookaheadScope, boundsTransform = { _, _ -> tween(350) })
                                    .aspectRatio(1f).fillMaxWidth().background(Color.Red)
                                    .onGloballyPositioned { arts[n] = it.boundsInRoot() })
                                Text("x\n".repeat(1 + (n * 7 + n / 5) % 4).trim())
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { state.easeToTop(false) } }
        repeat(80) {
            if (frameMs > 0) compose.mainClock.advanceTimeBy(frameMs, ignoreFrameDuration = true) else compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val problems = compose.runOnIdle {
            val info = state.layoutInfo
            info.visibleItemsInfo.mapNotNull { item ->
                val n = item.key as Int
                val cell = cells[n] ?: return@mapNotNull "$n no cell"
                val art = arts[n] ?: return@mapNotNull "$n no art"
                val dy = cell.top - item.offset.y
                val artDy = art.top - cell.top
                android.util.Log.i("Probe635", "f=$frameMs n=$n slot=${item.offset.y}/${item.size.height} cell=${cell.top}/${cell.height} artDy=$artDy")
                if (item.offset.y + item.size.height <= info.viewportEndOffset && abs(dy - 4 * compose.density.density) > 1f || abs(artDy) > 1f || item.offset.y + item.size.height <= info.viewportEndOffset && abs(cell.height + 8 * compose.density.density - item.size.height) > 1f)
                    "$n slot=${item.offset.y}/${item.size.height} cell=${cell.top}/${cell.height} artDy=$artDy" else null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun frame16() = run(0)
    @Test fun frame50() = run(50)
    @Test fun frame100() = run(100)
}
