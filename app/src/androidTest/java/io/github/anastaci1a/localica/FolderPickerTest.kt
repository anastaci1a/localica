package io.github.anastaci1a.localica

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.TreeScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FolderPickerTest {
    @Test fun selectedTreeScansSubfoldersAndPreservesPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            device.clickWhenReady(By.desc("Settings"))
            val choose = By.text(Pattern.compile("Choose folder|Change folder"))
            device.clickWhenReady(choose)
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5000)
            device.wait(Until.findObject(By.desc("Show roots")), 5000)?.click()
            device.clickWhenReady(By.text("Localica test library"))
            device.clickWhenReady(By.text(Pattern.compile("use this folder", Pattern.CASE_INSENSITIVE)))
            device.clickWhenReady(By.text(Pattern.compile("allow", Pattern.CASE_INSENSITIVE)))
            val tree = "content://io.github.anastaci1a.localica.test.documents/tree/root"
            await { context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission } }
            device.pressBack() // close settings
            device.clickWhenReady(By.text("Library"))
            assertNotNull(device.wait(Until.findObject(By.text("Morning tone")), 10000))
            assertNotNull(device.wait(Until.findObject(By.text("Quiet interval")), 10000))
            assertNotNull(device.findObject(By.text("2 songs · Test tones")))
            scenario.recreate()
            assertNotNull(device.wait(Until.findObject(By.text("Morning tone")), 5000))
            val scan = runBlocking { TreeScanner(AndroidDocuments(context)).scan(tree) }
            assertEquals(2, scan.tracks.size)
            assertEquals("nested/tone.m4a", scan.tracks.last().relativePath)
            device.clickWhenReady(By.text("Morning tone"))
            assertNotNull(device.wait(Until.findObject(By.desc("Pause")), 5000))
            assertNotNull(device.wait(Until.findObject(By.text("Track finished")), 10000))
            assertNotNull(device.findObject(By.text("Morning tone")))
        }
    }
}

private fun UiDevice.clickWhenReady(selector: BySelector) {
    waitForIdle()
    await {
        try {
            val node = findObject(selector)
            if (node != null && node.isEnabled) { node.click(); true } else false
        } catch (_: StaleObjectException) { false }
    }
}
