package io.github.anastaci1a.localica.icons

/** Pure rules for pack entry names and sizes, shared by import validation and tests. */
internal object IconPackNames {
    const val MANIFEST = "pack.json"
    const val MIN_SIZE = 96
    const val MAX_SIZE = 1024
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 256
    const val TEMPLATE_SIZE = 192

    /** The id for `icons/<id>.png|.webp`, or null when the name is not an icon entry. */
    fun iconId(entry: String): String? {
        if (!entry.startsWith("icons/")) return null
        val file = entry.removePrefix("icons/")
        if ('/' in file || file.contains("..")) return null
        val extension = file.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("svg", "png", "webp")) return null
        return file.substringBeforeLast('.').takeIf { it.isNotEmpty() }
    }

    fun sizeProblem(width: Int, height: Int): String? = when {
        width != height -> "not square ($width x $height)"
        width < MIN_SIZE -> "smaller than $MIN_SIZE px"
        width > MAX_SIZE -> "larger than $MAX_SIZE px"
        else -> null
    }
}
