package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseNoticeMeasurementTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun actualNodeReceiptsSurviveIntentButRejectChangedContentConstraintsAndReuse() {
        val measurement = BrowseNoticeMeasurement()
        var notice by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var width by mutableStateOf(240.dp)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var densityScale by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        var intent by mutableIntStateOf(0)
        var bounded by mutableStateOf(false)
        lateinit var identity: Any
        lateinit var density: Density
        var pixels = 0
        compose.setContent { LocalicaTheme {
            val physical = LocalDensity.current
            val actual = Density(physical.density * densityScale, physical.fontScale * fontScale)
            val content = remember(notice) { Any() }
            val currentIntent = intent
            val currentWidth = width
            SideEffect { identity = content; density = actual; pixels = with(actual) { currentWidth.roundToPx() }; currentIntent.hashCode() }
            CompositionLocalProvider(LocalDensity provides actual, LocalLayoutDirection provides direction) {
                // The grid measures items in a LookaheadScope with an unbounded main axis.
                LookaheadScope { Column(if (bounded) Modifier.height(0.dp) else Modifier.verticalScroll(rememberScrollState())) {
                    if (mounted) Box(Modifier.width(currentWidth).measureBrowseNotice(measurement, content)) {
                        if (notice) Text("A measured notice")
                    }
                } }
            }
        } }
        fun height() = measurement.height(identity, pixels, density, direction)
        compose.runOnIdle { assertEquals(0, height()); intent++ }
        compose.runOnIdle { assertEquals("An unrelated intent keeps the actual zero receipt", 0, height()); notice = true }
        compose.runOnIdle { assertTrue(requireNotNull(height()) > 0); notice = false }
        compose.runOnIdle { assertEquals(0, height()); width = 180.dp }
        compose.runOnIdle { assertEquals(0, height()); direction = LayoutDirection.Rtl }
        compose.runOnIdle { assertEquals(0, height()); densityScale = 1.25f; fontScale = 1.4f }
        compose.runOnIdle {
            assertEquals(0, height())
            assertNull(measurement.height(Any(), pixels, density, direction))
            assertNull(measurement.height(identity, pixels + 1, density, direction))
            assertNull(measurement.height(identity, pixels, Density(density.density, density.fontScale + .1f), direction))
            assertNull(measurement.height(identity, pixels, density, LayoutDirection.Ltr))
            mounted = false
        }
        compose.runOnIdle { assertNull("A detached node cannot certify the next mount", height()); mounted = true }
        compose.runOnIdle { assertEquals("The reattached node must actually measure again", 0, height()); bounded = true }
        compose.runOnIdle { assertNull("A bounded main axis is not notice geometry", height()); bounded = false }
        compose.runOnIdle { assertEquals(0, height()) }
    }
}
