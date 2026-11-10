package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.GlassBackend
import io.github.anastaci1a.localica.ui.effectiveGlassBackend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual Android preferences in an owned namespace; no production interface settings are cleared. */
@RunWith(AndroidJUnit4::class)
class GlassRendererPreferenceTest {
    private fun withPreferences(test: (Context, SharedPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val ownedName = "glass-renderer-test-${UUID.randomUUID()}"
        val context = preparedTestContext(object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "interface")
                return base.getSharedPreferences(ownedName, mode)
            }
        }, listOf("interface"))
        val raw = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        try { test(context, raw) } finally {
            raw.edit().clear().commit()
            base.deleteSharedPreferences(ownedName)
        }
    }

    @Test fun retiredModesMigrateAndRetiredScrollSettingIsRemoved() = withPreferences { context, raw ->
        for (name in listOf("Automatic", "Modern", "Legacy")) for (scroll in listOf(false, true)) {
            raw.edit().clear().putString("glass-renderer", name)
                .putBoolean("glass-live-scroll", scroll).putBoolean("confirm-exit", true).commit()
            val restored = InterfacePreferences(context).read()
            assertEquals("Normal", restored.glass.renderer.name)
            assertEquals("Normal", raw.getString("glass-renderer", null))
            assertFalse(raw.contains("glass-live-scroll"))
            assertTrue(restored.confirmExit)
            assertEquals(restored, InterfacePreferences(context).read())
        }
    }

    @Test fun missingAndUnknownRendererNamesRestoreNormalWithoutChangingOtherSettings() = withPreferences { context, raw ->
        assertEquals(InterfaceSettings(), InterfacePreferences(context).read())
        raw.edit().putBoolean("confirm-exit", true).putFloat("glass-depth", .8f).commit()
        val expected = InterfaceSettings(confirmExit = true, glass = GlassAppearance(depth = .8f))
        for (name in listOf("RemovedRenderer", "modern", "", "Legacy ")) {
            raw.edit().putString("glass-renderer", name).commit()
            assertEquals(expected, InterfacePreferences(context).read())
            assertEquals("Read must not rewrite stored values", name, raw.getString("glass-renderer", null))
        }
    }

    @Test fun everyRequestedModeRoundTripsAllExistingSettingsAndNormalStaysRequested() = withPreferences { context, raw ->
        val appearance = GlassAppearance(.7f, .1f, .2f, .8f, .3f, TabLensStyle.Overlay,
            .4f, .2f, 19f, .8f, 1.5f, .3f, 1.25f, .17f, true)
        val original = InterfaceSettings(tabs = listOf(MainTab.Songs, MainTab.Folders), tabHistory = false,
            confirmExit = true, expandOnPlay = false, albumArtistFirst = false, reducedMotion = true,
            artworkTap = ArtworkAction.Change, artworkHold = ArtworkAction.View, fasterMotion = true,
            artworkLayout = ArtworkLayout.Floating, artworkTransition = ArtworkTransition.Carousel,
            artworkSwipe = false, glass = appearance, grayTone = .7f, lyricDelayMs = -750)
        for (mode in GlassRendererMode.entries) {
            val requested = original.copy(glass = appearance.copy(renderer = mode))
            InterfacePreferences(context).write(requested)
            val restored = InterfacePreferences(context).read()
            assertEquals(requested, restored)
            assertEquals(mode.name, raw.getString("glass-renderer", null))
            if (mode == GlassRendererMode.Normal) {
                assertEquals(GlassBackend.None, effectiveGlassBackend(restored.glass.renderer, 26))
                assertEquals(GlassRendererMode.Normal, InterfacePreferences(context).read().glass.renderer)
                assertEquals("Normal", raw.getString("glass-renderer", null))
            }
        }
        InterfacePreferences(context).write(InterfaceSettings())
        assertEquals(InterfaceSettings(), InterfacePreferences(context).read())
        assertEquals("Normal", raw.getString("glass-renderer", null))
    }

    @Test fun rendererPersistencePreservesTheExistingContrastMigration() = withPreferences { context, raw ->
        for ((legacy, expected) in listOf(0f to 0f, .4f to .25f)) {
            raw.edit().remove("glass-contrast-trim").putFloat("glass-contrast", legacy)
                .putString("glass-renderer", "Modern").commit()
            val restored = InterfacePreferences(context).read()
            assertEquals(expected, restored.glass.contrast, 0f)
            assertEquals(GlassRendererMode.Normal, restored.glass.renderer)
            InterfacePreferences(context).write(restored)
            assertFalse(raw.contains("glass-contrast"))
            assertEquals(expected, raw.getFloat("glass-contrast-trim", -1f), 0f)
            assertEquals("Normal", raw.getString("glass-renderer", null))
        }
    }
}
