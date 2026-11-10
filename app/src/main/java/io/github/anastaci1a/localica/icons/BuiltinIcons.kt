package io.github.anastaci1a.localica.icons

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.anastaci1a.localica.ui.AppIcon
import java.util.concurrent.ConcurrentHashMap

/** Translations only: built-in geometry is measured by the JVM table generator, never at runtime. */
internal object BuiltinIcons {
    // Compose resolves theme and configuration before this cache. The complete resolved vector is the key.
    private data class Key(val source: ImageVector, val icon: AppIcon)
    private val vectors = ConcurrentHashMap<Key, ImageVector>()

    fun vector(source: ImageVector, icon: AppIcon): ImageVector = vectors.computeIfAbsent(Key(source, icon)) {
        val move = BuiltinIconOffsets.vertical.getValue(icon.id)
        if (move == 0f) source else source.translatedY(move)
    }
}
