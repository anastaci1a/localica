package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnlineFeaturesSettingsTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun onlineFeaturesStartsOffAndSwitchPersistsAcrossStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "online-consent-${java.util.UUID.randomUUID()}")
        val consent = OnlineMetadataConsent(file)
        try {
            runBlocking { consent.load() }
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Online features",
                    onlineFeaturesContent = { OnlineFeaturesSettings(consent) })
            } }
            val toggle = compose.onNode(isToggleable() and hasText("Online features"))
            toggle.performScrollTo().assertIsOff().performClick()
            compose.waitUntil(5000) { consent.allowed.value }
            toggle.assertIsOn()
            val restored = OnlineMetadataConsent(file)
            runBlocking { restored.load() }
            assertTrue(restored.allowed.value)
            toggle.performClick()
            compose.waitUntil(5000) { !consent.allowed.value }
            // Revocation closes the live gate before its atomic disk write finishes.
            compose.waitUntil(5000) {
                runBlocking {
                    val reopened = OnlineMetadataConsent(file)
                    reopened.load()
                    !reopened.allowed.value
                }
            }
            val disabled = OnlineMetadataConsent(file)
            runBlocking { disabled.load() }
            assertFalse(disabled.allowed.value)
        } finally { file.delete(); File(file.path + ".pending").delete() }
    }
}
