package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import io.github.anastaci1a.localica.ui.engine.ScrollExecution
import io.github.anastaci1a.localica.ui.engine.withScrollExecution

/** Compatibility boundary: root target policy receives Components, never an Engine handle. */
internal suspend fun <T> withListScrollMotion(block: suspend ListScrollMotion.() -> T): T =
    withScrollExecution { ListScrollMotion(this).block() }

internal class ListScrollMotion(private val execution: ScrollExecution) {
    val spec get() = execution.spec
    suspend fun animate(duration: Int, fade: Boolean = false,
        step: suspend (Float, Float, Int, Boolean) -> Boolean) = execution.animate(duration, fade, step)
    suspend fun consume(scope: LazyLayoutScrollScope, pixels: Float) = execution.consume(scope, pixels)
    fun fadeProgress(elapsedMs: Float) = execution.fadeProgress(elapsedMs)
}
