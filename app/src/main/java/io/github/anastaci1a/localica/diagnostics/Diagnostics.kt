package io.github.anastaci1a.localica.diagnostics

/**
 * Process-wide recorder behind Settings -> About -> Export diagnostics. Recording is event-driven
 * only (stuck-state reports, recoveries, cancellations, phase transitions); idle runs add nothing.
 * Debuggable builds also mirror each line to logcat under its tag.
 */
internal object Diagnostics {
    val ring = DiagnosticsRing()
    @Volatile var mirrorToLogcat = false

    fun info(tag: String, message: String, alsoLog: Boolean = false) = record('I', tag, message, alsoLog)
    fun warn(tag: String, message: String, alsoLog: Boolean = false) = record('W', tag, message, alsoLog)

    private fun record(level: Char, tag: String, message: String, alsoLog: Boolean) {
        ring.add(level, tag, message)
        if (mirrorToLogcat || alsoLog) {
            if (level == 'W') android.util.Log.w(tag, message) else android.util.Log.i(tag, message)
        }
    }
}
