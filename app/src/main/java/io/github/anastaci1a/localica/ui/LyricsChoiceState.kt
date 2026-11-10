package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/** Reject at the outer state boundary so rememberSaveable reruns its prepared-preference initializer. */
internal fun lyricChoiceSaver(trackId: String?): Saver<MutableState<String?>, Any> = Saver(
    save = { arrayListOf(trackId, it.value) },
    restore = { saved ->
        (saved as? List<*>)?.takeIf { fields ->
            fields.size == 2 && fields[0] == trackId && (fields[1] == null || fields[1] is String)
        }?.let { mutableStateOf(it[1] as? String) }
    },
)
