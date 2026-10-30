package io.github.anastaci1a.localica.library

import android.content.SharedPreferences
import kotlin.enums.enumEntries

/** Exact persisted names only; each caller owns unknown-value handling. */
internal inline fun <reified E : Enum<E>> enumNameOrNull(name: String?): E? =
    enumEntries<E>().firstOrNull { it.name == name }

/** Unknown stored names fall back at the caller, just like missing values. */
internal inline fun <reified E : Enum<E>> SharedPreferences.enumValue(key: String, defaultName: String? = null): E? {
    return enumNameOrNull<E>(getString(key, defaultName))
}
