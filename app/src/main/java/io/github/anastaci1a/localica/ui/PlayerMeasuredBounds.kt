package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect

/** Owner-thread preparation: refresh every coordinate, but allocate a new map only on change.
 * Names and previous bounds must remain stable during this call. Published maps are not mutated. */
internal fun preparePlayerMeasuredBounds(previous: Map<String, Rect>, names: Set<String>,
    readBounds: (String) -> Rect?): Map<String, Rect> {
    var changed: MutableMap<String, Rect>? = null
    for (name in names) {
        val bounds = readBounds(name)
        if (changed == null && bounds != previous[name]) {
            val replacement = linkedMapOf<String, Rect>()
            for (prefix in names) {
                if (prefix == name) break
                previous[prefix]?.let { replacement[prefix] = it }
            }
            changed = replacement
        }
        if (bounds != null) changed?.put(name, bounds)
    }
    if (changed != null) return changed
    if (previous.keys.any { it !in names }) {
        return names.mapNotNull { name -> previous[name]?.let { name to it } }.toMap()
    }
    return previous
}
