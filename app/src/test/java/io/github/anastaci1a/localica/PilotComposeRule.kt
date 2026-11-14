package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.Base64

@OptIn(ExperimentalRoborazziApi::class)
internal fun pilotComposeRule(): ComposeContentTestRule {
    val rule = localicaComposeRule()
    return object : ComposeContentTestRule by rule {
        override fun setContent(composable: @Composable () -> Unit) = rule.setContent {
            val appearance = remember { GlassAppearance(renderer = GlassRendererMode.FlatNone) }
            val renderer = remember { GlassRendererRuntime(appearance.renderer) }
            CompositionLocalProvider(LocalGlassAppearance provides appearance, LocalGlassRendererRuntime provides renderer) {
                check(LocalGlassAppearance.current.renderer == GlassRendererMode.FlatNone && flatGlass())
                composable()
            }
        }

        override fun apply(base: Statement, description: Description): Statement = rule.apply(object : Statement() {
            override fun evaluate() {
                base.evaluate()
                rule.waitForIdle()
                val name = "${description.testClass.simpleName}_${description.methodName}.png"
                val record = java.lang.Boolean.getBoolean("roborazzi.test.record")
                val golden = File("build/reports/roborazzi-goldens", name)
                if (!record) {
                    golden.parentFile.mkdirs()
                    golden.writeBytes(Base64.getMimeDecoder().decode(File("src/test/screenshots/$name.base64").readText()))
                }
                captureScreenRoboImage(golden, RoborazziOptions(
                    taskType = if (record) RoborazziTaskType.Record else RoborazziTaskType.Verify,
                    compareOptions = RoborazziOptions.CompareOptions(imageComparator = PilotImageComparator),
                ))
            }
        }, description)
    }
}
