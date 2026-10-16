package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Optional passive instrumentation. No observer or pointer node is installed in normal use. */
internal val LocalChoiceTapTrace = compositionLocalOf<((String) -> Unit)?> { null }

internal fun Modifier.choiceTapTrace(label: String, observer: ((String) -> Unit)?): Modifier =
    if (observer == null) this else pointerInput(label, observer) {
        observer("pointer-start $label")
        try {
            awaitPointerEventScope {
                while (true) {
                    for (pass in listOf(PointerEventPass.Initial, PointerEventPass.Main, PointerEventPass.Final)) {
                        val event = awaitPointerEvent(pass)
                        observer("pointer $label pass=$pass type=${event.type} " + event.changes.joinToString {
                            "id=${it.id} time=${it.uptimeMillis} position=${it.position} previous=${it.previousPosition} pressed=${it.pressed} previousPressed=${it.previousPressed} consumed=${it.isConsumed}"
                        })
                    }
                }
            }
        } finally {
            observer("pointer-stop $label")
        }
    }
