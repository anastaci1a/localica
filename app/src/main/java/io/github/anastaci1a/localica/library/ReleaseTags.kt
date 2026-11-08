package io.github.anastaci1a.localica.library

import java.util.Locale

internal fun compilationFlag(value: String?): Boolean? = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "1", "true", "yes" -> true
    "0", "false", "no" -> false
    else -> null
}
