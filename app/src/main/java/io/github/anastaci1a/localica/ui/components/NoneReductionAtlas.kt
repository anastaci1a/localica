package io.github.anastaci1a.localica.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.anastaci1a.localica.ui.engine.NoneReductionAtlas as AtlasOwner
import io.github.anastaci1a.localica.ui.engine.NoneAtlasStyle as OwnerStyle
import io.github.anastaci1a.localica.ui.engine.LocalNoneReductionAtlas
import io.github.anastaci1a.localica.ui.engine.NoneReductionAtlasHost as OwnerHost

internal data class NoneAtlasStyle(val whiteTint: Float, val trim: Float, val weight: Float,
    val lightMix: Float, val opaque: Boolean)

/** Component access to the window's shared engine-owned reduction. */
@RequiresApi(33)
internal class NoneReductionAtlas(private val owner: AtlasOwner) {
    class Entry {
        internal val owned = AtlasOwner.Entry()
        val placed get() = owned.placed
    }
    fun add(entry: Entry) = owner.add(entry.owned)
    fun remove(entry: Entry) = owner.remove(entry.owned)
    fun DrawScope.place(entry: Entry, backing: Any, registration: Any, structure: Any, region: Rect,
        style: NoneAtlasStyle, record: DrawScope.() -> Unit): Boolean = with(owner) {
        place(entry.owned, backing, registration, structure, region,
            OwnerStyle(style.whiteTint, style.trim, style.weight, style.lightMix, style.opaque), record)
    }
    fun DrawScope.drawMember(entry: Entry) = with(owner) { drawMember(entry.owned) }
}

@Composable internal fun rememberNoneReductionAtlas(source: GraphicsLayer): NoneReductionAtlas? {
    if (Build.VERSION.SDK_INT < 33) return null
    val registry = LocalNoneReductionAtlas.current
    val owner = remember(registry, source) { registry?.forSource(source) }
    DisposableEffect(registry, source) {
        registry?.retain(source)
        onDispose { registry?.forget(source) }
    }
    return remember(owner) { owner?.let(::NoneReductionAtlas) }
}

@Composable internal fun NoneReductionAtlasHost(colorShader: String, content: @Composable () -> Unit) =
    OwnerHost(colorShader, content)
