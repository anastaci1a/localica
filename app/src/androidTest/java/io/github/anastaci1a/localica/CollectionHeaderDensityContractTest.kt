package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Diagnostic red proof, no production changes. A square hero consumes the fixed pixel width;
 * changing ambient density may change the 112dp footer, never the square's pixel height. */
class CollectionHeaderDensityContractTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun headerSquareRetainsItsPixelHeightAcrossDensityWithStableTheme() {
        var multiplier by mutableFloatStateOf(1f)
        var baseDensity = 0f
        compose.setContent {
            LocalicaTheme {
                val base = LocalDensity.current
                baseDensity = base.density
                val scheme = MaterialTheme.colorScheme
                val visual = remember { PlayerVisual() }
                Box(Modifier.width(320.dp).testTag("fixed-pixel-header")) {
                    // The outer pixel width stays fixed; only the inner ambient changes.
                    MaterialTheme(colorScheme = scheme) {
                        CompositionLocalProvider(LocalDensity provides Density(base.density * multiplier, base.fontScale),
                            LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.None)) {
                            CollectionHeader("Density header", "Artist", visual, false, true, {}, {})
                        }
                    }
                }
            }
        }
        fun assertHeader(label: String) {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("fixed-pixel-header", true).fetchSemanticsNode().boundsInRoot
            val expected = bounds.width.roundToInt() + (112f * baseDensity * multiplier).roundToInt()
            android.util.Log.i("PERF2_HEADER_DENSITY", "$label density=${baseDensity * multiplier} width=${bounds.width} height=${bounds.height} expected=$expected")
            assertEquals("Square hero pixel height at $label", expected, bounds.height.roundToInt())
        }
        assertHeader("initial")
        compose.runOnIdle { multiplier = 1.1f }
        assertHeader("density-1.1")
    }
}
