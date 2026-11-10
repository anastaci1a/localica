package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The same test source runs on unmodified legacy rendering and on its migrated successor. */
class LegacyNonePixelReferenceTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun legacyStoredNoneMaterialPixels() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "legacy-none-reference-740"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val raw = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        try {
            raw.edit().clear().putString("glass-renderer", "None").commit()
            val appearance = InterfacePreferences(context).read().glass
            var dark by mutableStateOf(false)
            lateinit var source: FrostedBackdrop
            compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalGlassRendererRuntime provides remember { GlassRendererRuntime(appearance.renderer) },
                LocalGlassAppearance provides appearance) { LocalicaTheme { GlassPanelTheme(dark) {
                source = rememberFrostedBackdrop(Unit, dark = dark)
                Box(Modifier.size(320.dp, 400.dp).testTag("reference")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color(0xFFF4A020))
                        for (x in 0 until 32) drawRect(if (x % 2 == 0) Color(0xFF1030E0) else Color(0xFFD02050),
                            Offset(x * 10f, 120f), Size(10f, 200f))
                    }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            LiquidGlassSurface(Modifier.size(200.dp, 56.dp), cornerRadius = 28.dp, pressFeedback = false) {}
                            FrostedSurface(Modifier.size(220.dp, 130.dp)) {}
                        }
                    }
                }
            } } } }
            val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: base.getExternalFilesDir(null)!!
            for (isDark in listOf(false, true)) {
                compose.runOnIdle { dark = isDark }
                compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
                compose.waitForIdle()
                val image = compose.onNodeWithTag("reference").captureToImage()
                File(root, "legacy-none-reference-$isDark.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { raw.edit().clear().commit(); base.deleteSharedPreferences(name) }
    }
}
