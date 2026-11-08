package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import kotlin.math.abs

/** Read-only projection of the popup's bound bottom edge; no clock or trajectory. */
internal class SearchSlotMotion {
    private var generation=0L
    private var retained by mutableFloatStateOf(0f)
    private var edge by mutableStateOf<MotionValue?>(null)
    private var travel by mutableStateOf<SearchSlotTravel?>(null)
    val fraction: Float get()=edge?.let { travel?.fraction(it.value())?:it.value() }?.coerceIn(0f,1f)?:retained
    fun begin(): Long { val current=fraction; generation++; edge=null; travel=null; retained=current; return generation }
    fun bind(token: Long,value: MotionValue) { if(token==generation) { edge=value; travel=null } }
    fun bindBottom(token: Long,value: MotionValue,destination: Float,target: Float): SearchSlotTravel? {
        if(token!=generation) return null
        if(edge===value && travel?.toBottom==destination && travel?.toFraction==target) return travel
        val next=SearchSlotTravel(fraction,value.value(),destination,target)
        edge=value; travel=next
        return next
    }
    fun renew(): Long=++generation
    fun settle(token: Long,value: Float) { if(token!=generation) return; edge=null; travel=null; retained=value.coerceIn(0f,1f) }
}

/** A basis change retains the current vacancy before borrowing the next bottom travel. */
internal data class SearchSlotTravel(val fromFraction: Float,val fromBottom: Float,val toBottom: Float,val toFraction: Float=1f) {
    fun fraction(bottom: Float): Float {
        val distance=toBottom-fromBottom
        val traveled=if(abs(distance)<=.0001f) 1f else ((bottom-fromBottom)/distance).coerceIn(0f,1f)
        return (fromFraction+(toFraction-fromFraction)*traveled).coerceIn(0f,1f)
    }
}
