package io.github.anastaci1a.localica.ui

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

/** The renderer and benchmark share this cache, including all mixed-opacity path builds. */
internal class SelectionOpacityPathCache(private val profile: SelectionOutlineProfile?=null) {
    private val caches=mutableMapOf<List<Int>,HoverPathCache>()
    var rebuilds=0;private set
    fun clear() { caches.clear() }
    fun paths(regions: List<SelectionRegion>,rest: List<SelectionRegion>,gap: Float,
        visibility: List<Float>,splitRestComponents: Boolean=false,strength: (Int,Int)->Float): List<SelectionOutlineLayer> {
        require(regions.size==rest.size && regions.size==visibility.size)
        val active=mutableSetOf<List<Int>>()
        val result=selectionOpacitySlices(visibility).map { slice ->
            val components=if(splitRestComponents)hoverComponents(slice.members.map { rest[it].bounds },gap)
                .map { component -> component.map { slice.members[it] } } else listOf(slice.members)
            SelectionOutlineLayer(slice.weight,components.map { members ->
                active+=members
                val cache=caches.getOrPut(members) { HoverPathCache(profile) }
                val pieces=members.map { regions[it] }
                val before=cache.rebuilds
                val path=cache.path(pieces,members.map { rest[it] },gap) { a,b -> strength(members[a],members[b]) }
                rebuilds+=cache.rebuilds-before
                SelectionOutlineGroup(members,path,cache.localPath,pieces.first().bounds.topLeft)
            })
        }
        caches.keys.retainAll(active)
        return result
    }
}
