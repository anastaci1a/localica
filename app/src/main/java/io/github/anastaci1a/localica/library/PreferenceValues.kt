package io.github.anastaci1a.localica.library

import android.content.SharedPreferences
import kotlin.enums.enumEntries

/** Unknown stored names fall back at the caller, just like missing values. */
internal inline fun <reified E : Enum<E>> SharedPreferences.enumValue(key: String, defaultName: String? = null): E? {
    val name = getString(key, defaultName)
    return enumEntries<E>().firstOrNull { it.name == name }
}
