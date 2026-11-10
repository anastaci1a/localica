package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.applicationRootComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalicaUiRootLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private data class Record(val root: LocalicaUiRootState?, val context: EngineContext,
        val spec: ResolvedUiSpec, val window: IntSize, val density: Density, val navigationInset: androidx.compose.ui.unit.Dp)
    private var screen: Record? = null
    private var dialog: Record? = null

    @Composable private fun Observe(publish: (Record) -> Unit) {
        val root = LocalLocalicaUiRoot.current
        val record = Record(root, LocalEngineContext.current, LocalUiSpec.current,
            checkNotNull(root).window, LocalDensity.current,
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        SideEffect { publish(record) }
    }

    @Test fun screenAndSiblingDialogShareLiveSettingsMeasuredSizeAndBasis() {
        val root = LocalicaUiRootState(InterfaceSettings())
        var width by mutableIntStateOf(280)
        var height by mutableIntStateOf(400)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            LocalicaUiRoot(root) { LocalicaTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    // Use the same reuse branch as LocalicaScreen, alongside the actual screen measurement.
                    LocalicaUiRoot(root) {
                        Observe { screen = it }
                        LocalicaScreen(LibraryUiState(loading = false), PlaybackState(connected = true),
                            {}, {}, {}, {}, {}, {})
                    }
                }
                GlassAlertDialog(onDismissRequest = {}, title = { Text("Sibling root witness") },
                    text = { Observe { dialog = it }; Text("Live root") }, confirmButton = {})
            } }
        } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath, "root-context-lifetime")
        output.mkdirs()
        val witness = File(output, "records.tsv")
        witness.writeText("source\taccess\twidth\theight\tdensity\tnavigationInsetDp\tcontextTravel\texpectedTravel\tedgeIndex\tdiagonal\n")
        fun checkRecord(access: MotionAccess) {
            compose.onNodeWithText("Sibling root witness").assertIsDisplayed()
            compose.runOnIdle {
                val s = checkNotNull(screen); val d = checkNotNull(dialog)
                assertSame(root, s.root); assertSame(s.root, d.root)
                assertSame(s.context, d.context); assertSame(s.spec, d.spec)
                assertEquals(IntSize(width, height), root.size)
                assertEquals(root.size, s.window); assertEquals(s.window, d.window)
                assertEquals(access, s.context.access); assertEquals(access, s.spec.spec.access)
                val basis = playerBasis(s.density, root.size, s.navigationInset,
                    StockUiSpec.clearance.getValue(Clearance.BottomChrome))
                val travel = basis.edgeTravelPx
                assertEquals(travel, s.context.fullPlayerReferenceTravelPx, 0f)
                val geometry = checkNotNull(s.spec.spec.geometry)
                assertEquals(travel, geometry.referenceTravelPx, 0f)
                assertEquals(maxOf(1f, kotlin.math.hypot(width.toFloat(), height.toFloat())), geometry.maxViewportDiagonalPx, 0f)
                assertTrue(s.context.displayIntervalSeconds <= geometry.maxDisplayIntervalSeconds)
                witness.appendText(listOf(BuildConfig.SOURCE_SHA, access, width, height, s.density.density,
                    s.navigationInset.value, s.context.fullPlayerReferenceTravelPx, travel, basis.edgeIndex,
                    geometry.maxViewportDiagonalPx).joinToString("\t") + "\n")
            }
        }
        checkRecord(MotionAccess.Standard)
        val initial = checkNotNull(screen)
        compose.runOnIdle { root.settings.value = root.settings.value.copy(fasterMotion = true) }
        checkRecord(MotionAccess.Faster)
        compose.runOnIdle { assertNotSame(initial.spec, screen!!.spec) }
        val faster = checkNotNull(screen)
        val resized = IntSize(320, 560)
        val expectedResizedTravel = playerBasis(faster.density, resized, faster.navigationInset,
            StockUiSpec.clearance.getValue(Clearance.BottomChrome)).edgeTravelPx
        assertNotEquals("The resize fixture must change the dominant-edge reference under the existing law",
            faster.context.fullPlayerReferenceTravelPx, expectedResizedTravel)
        compose.runOnIdle { width = resized.width; height = resized.height }
        checkRecord(MotionAccess.Faster)
        compose.runOnIdle {
            assertNotSame(faster.spec, screen!!.spec)
            assertNotEquals(faster.context.fullPlayerReferenceTravelPx, screen!!.context.fullPlayerReferenceTravelPx)
            root.settings.value = root.settings.value.copy(reducedMotion = true)
        }
        checkRecord(MotionAccess.Reduced)
    }
}
