package io.github.anastaci1a.localica

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import org.junit.runners.Suite

/** Diagnostic only: repeat the original method with its original rules and cleanup. */
@RunWith(SearchImeTallContentRepeatTest.RepeatRunner::class)
class SearchImeTallContentRepeatTest {
    class RepeatRunner(testClass: Class<*>) : Suite(testClass, List(12) {
        AndroidJUnit4(SearchImeMotionTest::class.java).apply {
            filter(Filter.matchMethodDescription(Description.createTestDescription(
                SearchImeMotionTest::class.java,
                "tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard"
            )))
        }
    }) {
        private var iteration = 0

        override fun runChild(runner: Runner, notifier: RunNotifier) {
            val ordinal = ++iteration
            val started = SystemClock.uptimeMillis()
            Log.i("TallContentRepetition", "start iteration=$ordinal pid=${Process.myPid()} uptime=$started")
            try {
                // Each AndroidJUnit4 runner creates a fresh fixture and applies every
                // original Before/After and Rule. Only the instrumentation process persists.
                super.runChild(runner, notifier)
            } finally {
                try {
                    val root = requireNotNull(InstrumentationRegistry.getInstrumentation()
                        .targetContext.getExternalFilesDir(null))
                    val destination = File(root, "search-ime-repetition/iteration-$ordinal")
                    check(!destination.exists()) { "Refuse stale repetition evidence" }
                    check(File(root, "search-ime-motion").copyRecursively(destination))
                    File(destination, "invocation.txt").writeText(
                        "iteration=$ordinal\npid=${Process.myPid()}\nstart=$started\nend=${SystemClock.uptimeMillis()}\n")
                } catch (error: Throwable) {
                    notifier.fireTestFailure(Failure(runner.description, error))
                }
                Log.i("TallContentRepetition", "finish iteration=$ordinal pid=${Process.myPid()} uptime=${SystemClock.uptimeMillis()}")
            }
        }
    }
}
