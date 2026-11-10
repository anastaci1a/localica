package io.github.anastaci1a.localica.threading

import android.os.Looper
import android.os.StrictMode

/** Main-only policy. Production installs it only in debug; instrumentation always installs it. */
object MainThreadPolicy {
    fun install() {
        check(Looper.myLooper() == Looper.getMainLooper())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .detectUnbufferedIo()
            .penaltyLog()
            .penaltyDeath()
            .build())
    }
}
