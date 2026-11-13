package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.MutableState
import io.github.anastaci1a.localica.BuildConfig
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal object Step0Trace {
    private var sequence = 0L
    private fun id(v: Any) = v.javaClass.simpleName + "@" + System.identityHashCode(v).toString(16)
    private fun encode(v: Any?): Any = when (v) {
        null -> org.json.JSONObject.NULL
        is Boolean, is String -> v
        is Number -> if (v.toDouble().isFinite()) v else v.toString()
        is androidx.compose.ui.geometry.Rect, is androidx.compose.ui.geometry.Offset -> v.toString()
        else -> id(v)
    }
    @Synchronized fun put(owner: Any, name: String, value: Any?, event: String = "write") {
        if (!BuildConfig.DEBUG) return
        val row = org.json.JSONObject().put("n", ++sequence).put("t", android.os.SystemClock.uptimeMillis())
            .put("owner", id(owner)).put("event", event).put("key", name).put("value", encode(value))
        android.util.Log.i("PopupStep0", row.toString())
    }
    fun link(owner: Any, name: String, value: Any?) = put(owner, name, value, "link")
    fun frame(owner: Any, kind: String, progress: Float) = put(owner, kind, progress, "frame")
    fun <T> plain(owner: Any, name: String, initial: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var value = initial
        init { put(owner, name, value, "init") }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value; put(owner, name, value) }
    }
}
internal fun <T> MutableState<T>.step0(owner: Any, name: String): ReadWriteProperty<Any?, T> {
    val state = this
    Step0Trace.put(owner, name, state.value, "init")
    return object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { state.value = value; Step0Trace.put(owner, name, value) }
    }
}
