package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*

/** Stable event identity with a target published by the same snapshot as its UI. */
@Stable
internal class CurrentEvent<T>(initial: (T) -> Unit) {
    private var target by mutableStateOf(initial)
    val callback: (T) -> Unit = { target(it) }
    fun update(value: (T) -> Unit) { target = value }
}

@Composable
internal fun <T> rememberCurrentEvent(target: (T) -> Unit): (T) -> Unit {
    val owner = remember { CurrentEvent(target) }
    owner.update(target)
    return owner.callback
}

@Composable
internal fun rememberCurrentAction(target: () -> Unit): () -> Unit {
    val dispatch = rememberCurrentEvent<Unit> { target() }
    return remember(dispatch) { { dispatch(Unit) } }
}
