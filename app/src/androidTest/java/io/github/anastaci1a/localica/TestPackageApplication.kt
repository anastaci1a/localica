package io.github.anastaci1a.localica

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Application of the test APK's own processes: the one that hosts the fixture DocumentsProviders
 * and AndroidX's Bootstrap/Empty activities. The instrumentation itself runs in the app process
 * with the app's Application, so no tested code runs here (#730).
 */
class TestPackageApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TestProcessAccessibilityGuard.install()
    }
}

/**
 * Android 8.0/8.1 `ViewRootImpl$AccessibilityInteractionConnectionManager.onAccessibilityStateChanged`
 * dereferences `mView` without a null check (fixed in Android 9). `ActivityScenario.launch` starts
 * and immediately finishes an AndroidX activity in the test process; an accessibility-state change
 * that UiAutomation posts to that process just before the window is detached is delivered after
 * `mView` is cleared and kills the process, which hosts the fixture providers (#730). Nothing the
 * test controls is wrong: the callback targets a window that no longer exists.
 *
 * This guard keeps the main looper running after exactly that framework NPE (same exception type,
 * same top frame) and rethrows everything else, so every other crash stays fatal.
 */
internal object TestProcessAccessibilityGuard {
    private const val TAG = "TestProcessA11yGuard"

    fun isDeadViewRootCallback(failure: Throwable): Boolean {
        val top = failure.stackTrace.firstOrNull() ?: return false
        return failure is NullPointerException &&
            top.className == "android.view.ViewRootImpl\$AccessibilityInteractionConnectionManager" &&
            top.methodName == "onAccessibilityStateChanged"
    }

    fun install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return
        // Re-enter the main loop from inside a main-looper message; it returns only by exception.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (failure: Throwable) {
                    if (!isDeadViewRootCallback(failure)) throw failure
                    Log.w(TAG, "Ignored framework NPE from a detached ViewRootImpl (#730)", failure)
                }
            }
        }
    }
}
