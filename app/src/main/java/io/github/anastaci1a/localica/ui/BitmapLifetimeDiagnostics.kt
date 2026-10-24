package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

/** Opt-in ownership evidence; delegates the same recycle exactly once. */
internal fun Bitmap.recyclePreparedSnapshot(owner: String) {
    val trace = java.lang.Boolean.getBoolean("localica.bitmapLifetimeTrace")
    if (trace) Log.i("BitmapLifetime", "ns=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name} " +
        "owner=$owner event=before-recycle id=${System.identityHashCode(this)} recycled=$isRecycled")
    if (trace && !isRecycled) Log.i("BitmapLifetime", "owner=$owner event=metadata id=${System.identityHashCode(this)} " +
        "size=${width}x$height config=$config rowBytes=$rowBytes allocation=$allocationByteCount")
    recycle()
    if (trace) Log.i("BitmapLifetime", "ns=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name} " +
        "owner=$owner event=after-recycle id=${System.identityHashCode(this)}")
}

internal fun tracePreparedSnapshot(event: String) {
    if (java.lang.Boolean.getBoolean("localica.bitmapLifetimeTrace")) {
        Log.i("BitmapLifetime", "ns=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name} $event")
    }
}
