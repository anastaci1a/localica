package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the actual Row measure policy, not a duplicate implementation of its arithmetic. */
class ControlLayoutReaderEqualityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun all257FractionsMatchValueModifiersUnderRtlAndConstraints() {
        val fraction = mutableFloatStateOf(0f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var mode by mutableIntStateOf(0)
        var density by mutableFloatStateOf(1.25f)
        val bounds = Array(2) { arrayOfNulls<Rect>(7) }
        val origins = arrayOfNulls<Rect>(2)
        val compositions = IntArray(2)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides Density(density)) {
                Column {
                    repeat(2) { arm ->
                        ReaderArm(arm, fraction, mode, bounds, origins, compositions)
                    }
                }
            }
        }
        for (d in listOf(1.25f, 2.625f)) for (rtl in listOf(false, true)) for (constraint in 0..2) {
            compose.runOnIdle { density = d; direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr; mode = constraint }
            compose.waitForIdle()
            val initialCompositions = compositions[1]
            for (sample in 0..256) {
                compose.runOnIdle { fraction.floatValue = sample / 256f }
                compose.waitForIdle()
                compose.runOnIdle {
                    val a = requireNotNull(origins[0]); val b = requireNotNull(origins[1])
                    assertEquals("row width d=$d rtl=$rtl constraint=$constraint sample=$sample", a.width, b.width, 0f)
                    assertEquals("row height", a.height, b.height, 0f)
                    for (index in 0..6) {
                        val old = requireNotNull(bounds[0][index]).translate(-a.topLeft)
                        val current = requireNotNull(bounds[1][index]).translate(-b.topLeft)
                        assertEquals("child=$index d=$d rtl=$rtl constraint=$constraint sample=$sample", old, current)
                    }
                    assertEquals("Live Row must not recompose from fraction", initialCompositions, compositions[1])
                }
            }
        }
    }
    @Composable
    private fun ReaderArm(arm: Int, fraction: State<Float>, mode: Int, bounds: Array<Array<Rect?>>,
        origins: Array<Rect?>, compositions: IntArray) {
                        val live = arm == 1
                        val arrangement = if (live) remember {
                            object : Arrangement.Horizontal {
                                override val spacing: Dp get() = 60.dp * fraction.value
                                override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) {
                                    with(Arrangement.spacedBy(spacing)) { arrange(totalSize, sizes, layoutDirection, outPositions) }
                                }
                            }
                        } else Arrangement.spacedBy(60.dp * fraction.value)
                        Layout(content = {
                            Row(Modifier.onGloballyPositioned { origins[arm] = it.boundsInRoot() },
                                horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
                                repeat(3) { index ->
                                    val button: () -> Dp = { if (index == 1) 48.dp + 16.dp * fraction.value else 48.dp }
                                    val icon: () -> Dp = { 24.dp + (if (index == 1) 16.dp else 10.dp) * fraction.value }
                                    Box((if (live) Modifier.liveControlSize(button) else Modifier.size(button()))
                                        .onGloballyPositioned { bounds[arm][index] = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
                                        Box((if (live) Modifier.liveControlSize(icon) else Modifier.size(icon()))
                                            .onGloballyPositioned { bounds[arm][index + 3] = it.boundsInRoot() }.background(Color.Blue))
                                    }
                                }
                                Box((if (live) Modifier.liveControlWidth { 48.dp * (1f + fraction.value) }
                                    else Modifier.width(48.dp * (1f + fraction.value))).height(10.dp)
                                    .onGloballyPositioned { bounds[arm][6] = it.boundsInRoot() })
                            }
                            SideEffect { compositions[arm]++ }
                        }) { children, _ ->
                            val constraint = when (mode) {
                                0 -> Constraints(maxWidth = 340.dp.roundToPx(), maxHeight = 100.dp.roundToPx())
                                1 -> Constraints.fixed(105.dp.roundToPx(), 80.dp.roundToPx())
                                else -> Constraints(minWidth = 180.dp.roundToPx(), minHeight = 80.dp.roundToPx(), maxHeight = 100.dp.roundToPx())
                            }
                            val child = children.single().measure(constraint)
                            layout(child.width, child.height) { child.placeRelative(0, 0) }
                        }
    }

}
