package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.Step0Trace
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File

class PopupStep0Listener : RunListener() {
    override fun testRunStarted(description: Description) {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.path
        File(root).mkdirs()
        Step0Trace.sink = File(root, "popup-step0-" + android.os.Process.myPid() + ".jsonl").outputStream()
        Step0Trace.put(this, "run", "start", "run")
    }
    override fun testStarted(description: Description) {
        Step0Trace.put(this, "method", description.className + "#" + description.methodName, "test")
    }
    override fun testRunFinished(result: Result) {
        synchronized(Step0Trace) {
            Step0Trace.put(this, "run", "end", "run")
            Step0Trace.sink?.close()
            Step0Trace.sink = null
        }
    }
}
