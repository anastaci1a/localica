package io.github.anastaci1a.localica

/** InputMethodService can retain its input-view flag after hiding the actual window. */
internal fun nativeImeWindowVisible(dump: String): Boolean? {
    val windows = Regex("""\bm(?:WindowVisible|DecorViewVisible)=(true|false)\b""")
        .findAll(dump).map { it.groupValues[1] == "true" }.toList()
    return windows.takeIf { it.isNotEmpty() }?.any { it }
}
