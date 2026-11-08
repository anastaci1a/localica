package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates

/** TEMPORARY #635 diagnostics; not for commit. */
object P635Debug {
    var dump by androidx.compose.runtime.mutableStateOf(false)
    private fun field(o: Any, prefix: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == prefix || it.name.startsWith("$prefix\$") }?.let { it.isAccessible = true; return it.get(o) }
            c = c.superclass
        }
        return null
    }
    private fun method(o: Any, prefix: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredMethods.firstOrNull { (it.name == prefix || it.name.startsWith("$prefix\$")) && it.parameterCount == 0 }?.let { it.isAccessible = true; return it.invoke(o) }
            c = c.superclass
        }
        return null
    }
    private fun bounds(node: Any?): String {
        val sb = StringBuilder()
        var layout = node
        var depth = 0
        while (layout != null && depth < 4) {
            val chain = field(layout, "nodes") ?: break
            var n = field(chain, "head")
            while (n != null) {
                if (n.javaClass.simpleName == "BoundsAnimationModifierNode") {
                    val a = field(n, "boundsAnimation")!!
                    val anim = field(a, "animatable")
                    sb.append(" d$depth[pend=${field(a, "isPending")} run=${anim?.let { method(it, "isRunning") }} tgt=${field(a, "targetOffset")} cur=${field(a, "currentPosition")} add=${field(a, "additionalOffset")} val=${anim?.let { method(it, "getValue") }}]")
                }
                n = field(n, "child")
            }
            layout = method(layout, "getParent")
            depth++
        }
        return sb.toString()
    }
    fun log(index: Int, key: Any, slotY: Int, viewport: LayoutCoordinates?, art: LayoutCoordinates?, text: LayoutCoordinates?) {
        if (viewport == null || index > 15) return
        fun top(c: LayoutCoordinates?) = if (c != null && c.isAttached) viewport.localBoundingBoxOf(c, false).let { "${it.left.toInt()},${(it.top - slotY).toInt()}" } else "-"
        val artNode = art?.let { runCatching { field(it, "layoutNode") }.getOrNull() }
        android.util.Log.i("P635", "item $index $key slot=$slotY art=${top(art)} text=${top(text)}" + runCatching { bounds(artNode) }.getOrElse { " err=$it" })
    }
}
