package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Same mounted production header, fixed1080px width, stable theme/visual/callbacks.
 * Scalar measurement is deliberately unclipped on API36's narrower/shorter emulator.
 * Full native pixels remain independently required by BrowseRowInputEqualityTest. */
class CollectionHeaderDensityContractTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun headerSquareRetainsItsPixelHeightAcrossDensityWithStableTheme() {
        var suppliedDensity by mutableStateOf(Density(3f, 1f))
        var measured = IntSize.Zero
        lateinit var root: CompositionData
        lateinit var tables: MutableSet<CompositionData>
        compose.setContent {
            root = currentComposer.compositionData
            tables = remember { mutableSetOf<CompositionData>() }
            CompositionLocalProvider(LocalInspectionTables provides tables) {
                LocalicaTheme {
                    val windowDensity = LocalDensity.current
                    val scheme = MaterialTheme.colorScheme
                    val visual = remember { PlayerVisual() }
                    val play: () -> Unit = remember { {} }
                    val shuffle: () -> Unit = remember { {} }
                    // Pixel constraints stay fixed even when the inner ambient changes.
                    Box(Modifier.requiredWidth(with(windowDensity) { 1080.toDp() }).wrapContentHeight(unbounded = true)) {
                        MaterialTheme(colorScheme = scheme) {
                            CompositionLocalProvider(LocalDensity provides suppliedDensity,
                                LocalGlassAppearance provides GlassAppearance(renderer = GlassRendererMode.None)) {
                                Box(Modifier.onSizeChanged { measured = it }) {
                                    CollectionHeader("Density header", "Artist", visual, false, true, play, shuffle)
                                }
                            }
                        }
                    }
                }
            }
        }
        data class HeaderOwner(val state: MutableState<*>, val coordinates: LayoutCoordinates)
        fun headerIdentity(): HeaderOwner = compose.runOnIdle {
            // CollectionHeader alone remembers LayoutCoordinates in this controlled fixture.
            // Identify that actual state/layout owner, without optional compiler source names.
            val found = java.util.IdentityHashMap<MutableState<*>, LayoutCoordinates>()
            fun inspect(data: CompositionData) {
                for (group in data.compositionGroups) {
                    for (state in group.data.filterIsInstance<MutableState<*>>()) {
                        val coordinates = state.value as? LayoutCoordinates ?: continue
                        if (coordinates.isAttached && coordinates.size == measured)
                            found[state] = coordinates
                    }
                    inspect(group)
                }
            }
            inspect(root)
            tables.toList().filter { it !== root }.forEach(::inspect)
            check(found.size == 1) { "Expected one remembered header coordinates state, got ${found.size}; measured=$measured" }
            found.entries.single().let { HeaderOwner(it.key, it.value) }
        }
        compose.waitForIdle()
        val owner = headerIdentity()
        fun assertHeader(label: String) {
            compose.waitForIdle()
            val identity = headerIdentity()
            val (size, density) = compose.runOnIdle { measured to suppliedDensity }
            assertSame("Header remember state must stay mounted at $label", owner.state, identity.state)
            assertSame("Header layout owner must stay mounted at $label", owner.coordinates, identity.coordinates)
            assertEquals("Fixed pixel width at $label", 1080, size.width)
            val expected = 1080 + (112f * density.density).roundToInt()
            android.util.Log.i("PERF2_HEADER_DENSITY", "$label state=${System.identityHashCode(identity.state)} layout=${System.identityHashCode(identity.coordinates)} density=${density.density} fontScale=${density.fontScale} width=${size.width} height=${size.height} expected=$expected")
            assertEquals("Square hero pixel height at $label", expected, size.height)
        }
        assertHeader("initial")
        compose.runOnIdle { suppliedDensity = Density(3.3f, 1f) }
        assertHeader("density-3.3")
        compose.runOnIdle { suppliedDensity = Density(3.3f, 1.25f) }
        assertHeader("font-scale-only")
        compose.runOnIdle { suppliedDensity = Density(3f, 1.25f) }
        assertHeader("density-reversal")
        compose.runOnIdle { suppliedDensity = Density(3f, 1f) }
        assertHeader("font-scale-restored")
    }
}
