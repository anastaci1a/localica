package io.github.anastaci1a.localica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NavigationStandaloneRuntimeTest {
    @Test fun standaloneProviderProcessLoadsItsOwnKotlinRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val probeId = UUID.randomUUID().toString()
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        val component = "${NavigationStandaloneRuntimeActivity.TEST_PACKAGE}/" +
            "io.github.anastaci1a.localica.NavigationStandaloneRuntimeActivity"
        val launch = shell("am start --user 0 -n $component -a ${NavigationStandaloneRuntimeActivity.ACTION} --es probeId $probeId")
        assertFalse(launch, launch.contains("Error"))
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var matches = emptyList<JSONObject>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val log = shell("logcat -d -v raw -s ${NavigationStandaloneRuntimeActivity.TAG}:I '*:S'")
            assertFalse(log, log.contains("Standalone linkage failed, probeId=$probeId"))
            matches = log.lineSequence().filter { it.startsWith("{") }.map { JSONObject(it) }
                .filter { it.optString("probeId") == probeId }.toList()
            if (matches.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        assertEquals("Exactly one standalone receipt for $probeId", 1, matches.size)
        val receipt = matches.single()
        assertEquals("loaded", receipt.getString("status"))
        val provider = instrumentation.targetContext.packageManager.getApplicationInfo(
            NavigationStandaloneRuntimeActivity.TEST_PACKAGE, 0)
        assertEquals(provider.uid, receipt.getInt("uid"))
        assertNotEquals(android.os.Process.myUid(), receipt.getInt("uid"))
        assertEquals(android.os.Process.myUid(), receipt.getInt("targetUid"))
        assertTrue(receipt.getInt("pid") > 0)
        assertEquals(provider.sourceDir, receipt.getString("ownApk"))
        assertEquals("${NavigationStandaloneRuntimeActivity.TEST_PACKAGE}:navigation_random", receipt.getString("process"))
        val classes = receipt.getJSONArray("classes")
        assertEquals(NavigationStandaloneRuntimeActivity.CLASSES.toList(),
            (0 until classes.length()).map { classes.getString(it) })
        assertTrue(receipt.getString("loader").contains(provider.sourceDir))
        assertFalse(receipt.getString("loader").contains(instrumentation.targetContext.applicationInfo.sourceDir))
        Log.i("LocalicaStandaloneContract", receipt.toString())
    }
}
