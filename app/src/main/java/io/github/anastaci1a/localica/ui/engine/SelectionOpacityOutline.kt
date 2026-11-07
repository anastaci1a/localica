package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/** Exact layer-cake decomposition: each member receives its own visibility once. */
internal data class SelectionOpacitySlice(val members: List<Int>,val weight: Float)

internal fun selectionOpacitySlices(visibility: List<Float>): List<SelectionOpacitySlice> {
    if(visibility.isEmpty())return emptyList()
    val values=visibility.map { it.coerceIn(0f,1f) }
    val maximum=values.max()
    if(maximum==0f)return emptyList()
    if(values.all { it==maximum })return listOf(SelectionOpacitySlice(values.indices.toList(),maximum))
    var previous=0f
    return values.filter { it>0f }.distinct().sorted().map { level ->
        SelectionOpacitySlice(values.indices.filter { values[it]>=level },level-previous).also { previous=level }
    }
}

internal data class SelectionOutlineGroup(val members: List<Int>,val path: Path,val localPath: Path,val origin: Offset)
internal data class SelectionOutlineLayer(val weight: Float,val groups: List<SelectionOutlineGroup>)
