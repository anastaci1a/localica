package io.github.anastaci1a.localica.threading

import android.os.Looper
import android.os.Handler
import android.os.StrictMode

/** Main-only policy. Production installs it only in debug; instrumentation always installs it. */
object MainThreadPolicy {
    fun install() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val policy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .detectUnbufferedIo()
            .penaltyLog()
            .penaltyDeath()
            .build()
        StrictMode.setThreadPolicy(policy)
        // API 26 ActivityThread restores its saved policy after Application.onCreate.
        // Reinstall before the next lifecycle/test callback, without delaying Main.
        check(Handler(Looper.getMainLooper()).postAtFrontOfQueue { StrictMode.setThreadPolicy(policy) })
    }
}
