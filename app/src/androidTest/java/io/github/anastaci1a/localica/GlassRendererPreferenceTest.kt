package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

internal fun withOwnedInterfacePreferences(test: (Context, SharedPreferences) -> Unit) {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    val ownedName = "glass-renderer-test-${UUID.randomUUID()}"
    val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "interface")
            return base.getSharedPreferences(ownedName, mode)
        }
    }
    val raw = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    try { test(context, raw) } finally {
        raw.edit().clear().commit()
        base.deleteSharedPreferences(ownedName)
    }
}

/** Actual Android preferences in an owned namespace; no production interface settings are cleared. */
@RunWith(AndroidJUnit4::class)
class GlassRendererPreferenceTest {
    @Test fun removedGlassKeysAreClearedWithoutReadingTheirTypes() = withOwnedInterfacePreferences { context, raw ->
        val keys = listOf("glass-renderer", "glass-live-scroll", "glass-blur", "glass-white-tint", "glass-shadow-strength-multiplier", "glass-shadow-strength", "glass-shadow-spread-multiplier", "glass-shadow-spread", "glass-contrast-trim", "glass-contrast", "tab-lens-style", "tab-lens-blur", "tab-lens-tint", "glass-shape-damping", "glass-depth", "glass-contrast-weight", "tab-classic-opacity", "glass-control-expansion", "tab-overlay-darkening", "tab-overlay-foreground", "glass-lift-shadow-strength")
        for (malformed in listOf(false, true)) {
            raw.edit().apply {
                clear()
                keys.forEachIndexed { index, key ->
                    if (malformed) putStringSet(key, setOf("retired", index.toString()))
                    else putFloat(key, .73f)
                }
                putBoolean("confirm-exit", true)
                putString("unrelated-entry", "preserved")
            }.commit()
            val preferences = InterfacePreferences(context)
            val restored = preferences.read()
            assertEquals(InterfaceSettings(confirmExit = true), restored)
            assertTrue(restored.confirmExit)
            keys.forEach { assertFalse("Removed key $it", raw.contains(it)) }
            assertEquals("preserved", raw.getString("unrelated-entry", null))
            assertEquals(restored, preferences.read())
            preferences.write(restored)
            assertEquals(restored, preferences.read())
            keys.forEach { assertFalse("No recreated key $it", raw.contains(it)) }
        }
    }

    @Test fun unrelatedSettingsRoundTrip() = withOwnedInterfacePreferences { context, raw ->
        val original = InterfaceSettings(tabs = listOf(MainTab.Songs, MainTab.Folders), tabHistory = false,
            confirmExit = true, expandOnPlay = false, albumArtistFirst = false, reducedMotion = true,
            artworkTap = ArtworkAction.Change, artworkHold = ArtworkAction.View, animationSpeedTenths = 15,
            artworkLayout = ArtworkLayout.Floating, artworkTransition = ArtworkTransition.Carousel,
            artworkSwipe = false, grayTone = .7f, lyricDelayMs = -750)
        val preferences = InterfacePreferences(context)
        preferences.write(original)
        assertEquals(original, preferences.read())
        preferences.write(InterfaceSettings())
        assertEquals(InterfaceSettings(), preferences.read())
        assertTrue(raw.contains("tabs"))
    }
}
