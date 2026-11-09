package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native development probes over the exact committed root implementation, no source replacements. */
class RootFloatingFacesNativeTest {
    @get:Rule val compose = localicaAndroidComposeRule<ComponentActivity>()
    private class Fixture {
        var admitted by mutableStateOf(true)
        var popup by mutableStateOf(false)
        var progress by mutableFloatStateOf(0f)
        var fullyOpen by mutableStateOf(false)
        var sourceMounted by mutableStateOf(true)
        var host: FloatingRowFaces? = null
        var nativeWindow: Any? = null
        var popupWindow: Any? = null
        var density = 1f
    }

    @Composable private fun Harness(fixture: Fixture, probe: Boolean = false) {
        val density = LocalDensity.current
        val specification = remember(density) {
            uiSpec(InterfaceSettings(reducedMotion = true), ExpansionGeometryDomain(1000f, 4000f, 1f / 24), density)
        }
        CompositionLocalProvider(LocalEngineContext provides EngineContext(1f / 60, 1000f, specification.spec.access),
            LocalUiSpec provides specification) {
            LocalicaTheme {
                val window = LocalView.current.rootView
                val host = remember(window) { FloatingRowFaces(window) }
                val scene = remember { FloatingRowScene(ToastRowOwner("native-source")) }
                val other = remember { FloatingRowScene(ToastRowOwner("not-admitted")) }
                SideEffect { fixture.host = host; fixture.nativeWindow = window; fixture.density = density.density }
                CompositionLocalProvider(LocalFloatingRowFaces provides host, LocalFloatingRowScene provides scene) {
                    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).testTag("native-row-root")) {
                        val scope = this
                        if (fixture.sourceMounted) {
                            if (probe) HoistedFloatingFace("probe", Modifier.align(Alignment.Center)) {
                                LiquidGlassSurface(Modifier.size(CompactControlSize).testTag("native-real-face")) {
                                    Box(Modifier.fillMaxSize().background(Color.Green))
                                }
                            }
                            else PlaylistCreationButton(LibrarySnapshot(), PlaylistState(loading = false), null,
                                Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("native-source-cell"))
                        }
                        if (probe) scope.PlayerClippedOverlay({ fixture.progress }, 80.dp) {
                            Box(Modifier.fillMaxSize().background(Color.Blue))
                        }
                        scope.PlayerOccludedOverlay({ fixture.progress }, 80.dp, occluding = { probe }) {
                            RootFloatingFaces(host, queue = false,
                                scene = if (fixture.admitted && !fixture.fullyOpen) scene else other,
                                toast = { null }, downwardPx = with(density) { 20.dp.toPx() },
                                fieldOutset = with(density) { glassShadowInset().toPx() },
                                interactive = !fixture.popup)
                        }
                        if (fixture.popup) GlassAlertDialog(onDismissRequest = { fixture.popup = false },
                            title = { androidx.compose.material3.Text("Native popup source") },
                            text = {
                                val popup = LocalView.current.rootView
                                SideEffect { fixture.popupWindow = popup }
                                PlaylistCreationButton(LibrarySnapshot(), PlaylistState(loading = false), null)
                            }, confirmButton = {
                                ActionTextButton(onClick = { fixture.popup = false }) { androidx.compose.material3.Text("Close probe") }
                            })
                    }
                }
            }
        }
    }

    @Test fun oneRealFaceKeepsSourceMenuStateWhileAdmissionChanges() {
        val fixture = Fixture()
        compose.setContent { Harness(fixture) }
        compose.onAllNodesWithContentDescription("Add playlist").assertCountEquals(1)
        compose.onNodeWithTag("native-source-cell").assertHasNoClickAction()
        compose.runOnIdle {
            assertEquals(1, fixture.host!!.faces.size)
            assertTrue(fixture.host!!.faces.single().measured)
        }
        compose.onNodeWithContentDescription("Add playlist").performClick()
        compose.onNodeWithText("Create playlist").performClick()
        compose.onNodeWithText("New playlist").assertIsDisplayed()
        compose.runOnIdle { fixture.admitted = false }
        compose.onNodeWithText("New playlist").assertIsDisplayed()
        compose.runOnIdle { fixture.admitted = true }
        compose.onNodeWithText("New playlist").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Add playlist").assertCountEquals(1)
    }

    @Test fun popupWindowSourceStaysLocalAndCannotReplaceActivityFace() {
        val fixture = Fixture()
        compose.setContent { Harness(fixture) }
        var original: FloatingRowFaces.Face? = null
        compose.runOnIdle { original = fixture.host!!.faces.single(); fixture.popup = true }
        compose.onNodeWithText("Native popup source").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(fixture.popupWindow)
            assertNotSame(fixture.nativeWindow, fixture.popupWindow)
            assertSame(original, fixture.host!!.faces.single())
        }
        compose.onNodeWithText("Close probe").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Add playlist").assertCountEquals(1)
        compose.runOnIdle { assertSame(original, fixture.host!!.faces.single()) }
    }

    @Test fun actualInversePlayerContourMasksBothFaceAndOverflowingFieldWithoutEarlyDrop() {
        val fixture = Fixture()
        compose.setContent { Harness(fixture, probe = true) }
        val bounds = compose.onNodeWithTag("native-real-face").getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("native-row-root")
        val centerX = with(compose.density) { ((bounds.left + bounds.right) / 2).roundToPx() }
        val centerY = with(compose.density) { ((bounds.top + bounds.bottom) / 2).roundToPx() }
        fun sample(x: Int, y: Int) = root.captureToImage().toPixelMap()[x, y]
        val initial = sample(centerX, centerY)
        assertTrue("A zero-phase opening keeps the source face drawing", initial.green > .9f && initial.blue < .2f)
        compose.runOnIdle { fixture.progress = .5f }
        compose.waitForIdle()
        val image = root.captureToImage().toPixelMap()
        val radius = (fixture.density * 35f).toInt()
        for ((x, y) in listOf(centerX to centerY, centerX - radius to centerY,
            centerX + radius to centerY, centerX to centerY - radius)) {
            val pixel = image[x, y]
            assertTrue("Source body and field stay behind the actual player contour at $x,$y: $pixel",
                pixel.blue > .9f && pixel.red < .2f && pixel.green < .2f)
        }
        compose.runOnIdle { fixture.progress = 0f }
        compose.waitForIdle()
        val reversed = sample(centerX, centerY)
        assertTrue("Reversal uses the same real face", reversed.green > .9f && reversed.blue < .2f)
        compose.runOnIdle { fixture.sourceMounted = false }
        compose.waitForIdle()
        compose.onAllNodesWithTag("native-real-face").assertCountEquals(0)
        compose.runOnIdle { assertTrue(fixture.host!!.faces.isEmpty()) }
    }

    @Test fun activityReplacementRetiresOldWindowFacesAndMeasuresOnlyTheNewHost() {
        val fixture = Fixture()
        compose.setContent { Harness(fixture) }
        var old: FloatingRowFaces? = null
        val previousActivity = compose.activity
        compose.runOnIdle { old = fixture.host }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { Harness(fixture) } }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Add playlist").assertCountEquals(1)
        // Android may preserve the native DecorView during relaunch; root composition ownership must still retire.
        assertNotSame(previousActivity, compose.activity)
        compose.runOnIdle {
            assertNotSame(old, fixture.host)
            assertTrue(old!!.faces.isEmpty())
            assertTrue(fixture.host!!.faces.single().measured)
        }
    }
    @Test fun sourceNameEditorKeepsFocusAndDraftAcrossFaceAdmissionChanges() {
        val fixture = Fixture()
        compose.setContent { Harness(fixture) }
        compose.onNodeWithContentDescription("Add playlist").performClick()
        compose.onNodeWithText("Create playlist").performClick()
        val editor = compose.onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInput("Root native draft")
        editor.assertIsFocused()
        compose.runOnIdle { fixture.admitted = false }
        editor.assertIsFocused()
        editor.assertTextContains("Root native draft")
        compose.runOnIdle { fixture.admitted = true }
        editor.assertIsFocused()
        editor.assertTextContains("Root native draft")
    }

    @Test fun sourceDocumentLauncherReturnsToTheSameMeasuredActivityFace() {
        val fixture = Fixture()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            compose.setContent { Harness(fixture) }
            var face: FloatingRowFaces.Face? = null
            compose.runOnIdle { face = fixture.host!!.faces.single() }
            compose.onNodeWithContentDescription("Add playlist").performClick()
            compose.onNodeWithText("Import playlist from file").performClick()
            compose.waitUntil(5000) { monitor.hits == 1 }
            compose.runOnIdle { fixture.admitted = false }
            compose.runOnIdle { fixture.admitted = true }
            compose.onAllNodesWithContentDescription("Add playlist").assertCountEquals(1)
            compose.runOnIdle { assertSame(face, fixture.host!!.faces.single()) }
        } finally { instrumentation.removeMonitor(monitor) }
    }

}
