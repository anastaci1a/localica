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
        is androidx.compose.ui.geometry.Rect, is androidx.compose.ui.geometry.Offset, is BackdropRegistration, is HomeBackdropFootprint -> v.toString()
        is java.lang.ref.WeakReference<*> -> v.get()?.let { id(it) } ?: org.json.JSONObject.NULL
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
    fun <T> slot(owner: Any, name: String): Step0Slot<T> = Step0Slot(owner, name)
    fun <T> plain(owner: Any, name: String, initial: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var value = initial
        init { put(owner, name, value, "init") }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value; put(owner, name, value) }
    }
}
internal fun <T> MutableState<T>.step0(owner: Any, name: String): MutableState<T> {
    val state = this
    Step0Trace.put(owner, name, state.value, "init")
    return object : MutableState<T> {
        override var value: T
            get() = state.value
            set(value) { state.value = value; Step0Trace.put(owner, name, value) }
        override fun component1() = value
        override fun component2(): (T) -> Unit = { value = it }
    }
}

internal class Step0Slot<T>(private val owner: Any, private val name: String) {
    private var value: T? = null
    init { Step0Trace.put(owner, name, null, "init") }
    operator fun get(index: Int): T? { check(index == 0); return value }
    operator fun set(index: Int, value: T?) { check(index == 0); this.value = value; Step0Trace.put(owner, name, value) }
}
