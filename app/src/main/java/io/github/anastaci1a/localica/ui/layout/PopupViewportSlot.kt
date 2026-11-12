package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight
import io.github.anastaci1a.localica.ui.spec.PopupViewportInsets
import io.github.anastaci1a.localica.ui.spec.fixedViewportHeight
import kotlin.math.roundToInt

/** Actual layout-owned viewport; an IME extent is measured rather than animated here. */
@Composable
internal fun PopupViewportSlot(heightSizing: GlassDialogHeight, insets: PopupViewportInsets,
    modifier: Modifier = Modifier, content: @Composable PopupViewportScope.() -> Unit) {
    val fixed = heightSizing is GlassDialogHeight.ViewportFraction
    require(fixed == (insets == PopupViewportInsets.SystemBarsAndCutout))
    val ime = WindowInsets.ime
    val consumed = remember { arrayOf(WindowInsets(0, 0, 0, 0)) }
    val padding = when (insets) {
        PopupViewportInsets.None -> Modifier
        PopupViewportInsets.SafeDrawingAndIme -> Modifier.safeDrawingPadding().imePadding()
        PopupViewportInsets.SystemBarsAndCutout -> Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(padding).then(modifier)) {
        val scope = remember(constraints, heightSizing, ime) {
            PopupViewportScope(constraints, heightSizing, ime, consumed)
        }
        scope.content()
    }
}

/** A chrome viewport has no constraint consumer and needs no subcomposition. */
@Composable
internal fun ChromeViewportSlot(modifier: Modifier, contentModifier: Modifier,
    chrome: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(contentModifier)) { content() }
        chrome()
    }
}

internal data class PopupViewportMeasurement(val viewportHeight: Int, val contentMaxHeight: Int,
    val fixedHeight: Int?, val remainingIme: Int) {
    val empty: Boolean get() = fixedHeight == 0
}

/** The presenter attaches both modifiers to the same measured foreground chain. */
internal class PopupViewportScope internal constructor(val constraints: Constraints,
    private val heightSizing: GlassDialogHeight, private val ime: WindowInsets,
    private val consumed: Array<WindowInsets>) {
    private val fixed = heightSizing is GlassDialogHeight.ViewportFraction
    val measureModifier: Modifier = if (fixed)
        Modifier.onConsumedWindowInsetsChanged { consumed[0] = it } else Modifier
    val contentModifier: Modifier = if (fixed)
        Modifier.consumeWindowInsets(ime.only(WindowInsetsSides.Bottom)) else Modifier

    /** Called in the presenter's measure, after incoming inset consumption is known. */
    fun measure(panelConstraints: Constraints, density: Density, margin: Float,
        contentHeightLimit: Int): PopupViewportMeasurement {
        val remainingIme = if (fixed)
            (ime.getBottom(density) - consumed[0].getBottom(density)).coerceAtLeast(0) else 0
        val height = if (fixed) (panelConstraints.maxHeight - remainingIme).coerceAtLeast(0)
            else constraints.maxHeight
        val contentHeight = if (fixed) (height - margin * 2).roundToInt().coerceAtLeast(0)
            else contentHeightLimit
        return PopupViewportMeasurement(height, contentHeight,
            heightSizing.fixedViewportHeight(contentHeight), remainingIme)
    }
}
