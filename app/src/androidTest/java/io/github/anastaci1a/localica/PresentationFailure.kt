package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Failure-only observations never dismiss windows, retry assertions or change their budget. */
internal fun recordPresentationFailure(rule: ComposeContentTestRule, label: String, view: View?,
    lastScreen: Bitmap? = null, crop: Rect? = null) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val base = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val directory = File(base, "utility-popup-diagnostics/$label-${SystemClock.uptimeMillis()}")
    check(directory.isDirectory || directory.mkdirs())
    val facts = StringBuilder("label=$label observedMs=${SystemClock.elapsedRealtime()} crop=$crop\n")
    rule.runOnUiThread {
        val location = IntArray(2)
        val visible = Rect()
        view?.getLocationOnScreen(location)
        facts.append("view=${view?.let(System::identityHashCode)} token=${view?.windowToken} " +
            "attached=${view?.isAttachedToWindow} shown=${view?.isShown} focus=${view?.hasWindowFocus()} " +
            "origin=${location.toList()} visible=${view?.getGlobalVisibleRect(visible)} rect=$visible\n")
    }
    // The popup path supplies its last ALREADY captured image. Other failure paths
    // label their later diagnostic screenshot separately, without pretending it caused the failure.
    val screen = lastScreen ?: instrumentation.uiAutomation.takeScreenshot()
    try {
        if (screen != null) {
            File(directory, if (lastScreen != null) "last-captured-screen.png" else "after-failure-screen.png")
                .outputStream().use { check(screen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (crop != null) {
                val region = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height())
                try { File(directory, "last-captured-crop.png").outputStream().use {
                    check(region.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { if (region !== screen) region.recycle() }
            }
        }
    } finally { if (lastScreen == null) screen?.recycle() }
    for (command in listOf("dumpsys window windows", "dumpsys activity activities")) {
        val text = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        File(directory, command.replace(' ', '-') + ".txt").writeText(text)
    }
    val root = instrumentation.uiAutomation.rootInActiveWindow
    try { facts.append("activeRootPackage=${root?.packageName} class=${root?.className} text=${root?.text}\n") }
    finally { root?.recycle() }
    File(directory, "observation.txt").writeText(facts.toString())
    Log.e("PresentationFailure", "Recorded $directory: $facts")
}
