package io.github.anastaci1a.localica

/** Passive timing only. Identical probe overhead appears in baseline and candidate. */
internal inline fun <T> measureNativeSuiteCost(stage: String, action: () -> T): T {
    val started = android.os.SystemClock.elapsedRealtimeNanos()
    try {
        return action()
    } finally {
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - started
        android.util.Log.i("NativeSuiteCost", "stage=$stage elapsed_ns=$elapsed")
    }
}
