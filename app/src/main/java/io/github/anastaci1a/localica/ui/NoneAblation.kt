package io.github.anastaci1a.localica.ui

/** DIAGNOSTIC ONLY. This branch must never be merged. Select before composition. */
internal object NoneAblation {
    var mode = "baseline"
    val fixed get() = mode != "baseline" && mode != "shadow" && mode != "rim"
    val removeSources get() = mode in setOf("sources", "sources-shadow", "sources-rim")
    val noShadow get() = mode == "shadow" || mode == "sources-shadow"
    val noRim get() = mode == "rim" || mode == "sources-rim"
    val color = androidx.compose.ui.graphics.Color(0.35f, 0.35f, 0.35f, 1f)
}
