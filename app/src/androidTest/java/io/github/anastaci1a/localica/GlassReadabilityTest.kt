package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassReadabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contrastAddsVisibleLocalSupportWithoutWashingTheSurface() {
        var strength by mutableFloatStateOf(0f)
        var ink by mutableStateOf(Color.White)
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(220.dp, 80.dp).background(ink).testTag("contrast")) {
                Text("Drag preview", Modifier.padding(24.dp).lensInkSupport(ink, strength), color = ink, fontSize = 20.sp)
            }
        } }
        for (color in listOf(Color.White, Color.Black)) {
            compose.runOnIdle { ink = color; strength = 0f }
            val before = compose.onNodeWithTag("contrast").captureToImage().toPixelMap()
            compose.runOnIdle { strength = 1f }
            val after = compose.onNodeWithTag("contrast").captureToImage().toPixelMap()
            var supported = 0
            for (y in 0 until before.height) for (x in 0 until before.width)
                if (kotlin.math.abs(before[x, y].red - after[x, y].red) > .3f) supported++
            assertTrue("The strongest setting must visibly separate matching ink/backdrop", supported > 100)
            assertEquals("Distant backdrop stays untouched", before[4, 4], after[4, 4])
        }
    }

    @Test fun navbarLabelsParticipateInAdaptiveContrast() {
        var strength by mutableFloatStateOf(0f)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(contrast = strength,
                shadowStrength = 0f, blur = 0f, whiteTint = 0f, tabStyle = TabLensStyle.Classic)) {
                Box(Modifier.size(300.dp, 64.dp).background(Color.Black).testTag("navbar-contrast")) {
                    SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, 0f) {}
                }
            }
        } }
        val before = compose.onNodeWithTag("navbar-contrast").captureToImage().toPixelMap()
        compose.runOnIdle { strength = 1f }
        val after = compose.onNodeWithTag("navbar-contrast").captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in before.width / 2 until before.width)
            if (after[x, y].red - before[x, y].red > .2f) changed++
        assertTrue("Detached navbar labels need the same contrast treatment", changed > 100)
    }
}
