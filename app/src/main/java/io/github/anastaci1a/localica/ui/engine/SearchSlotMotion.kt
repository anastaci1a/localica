package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import kotlin.math.abs

/** Vacancy borrows the shared expanded presentation and its bound bottom edge. */
internal class SearchSlotMotion(private val owner: PopupTakeoverOwner) {
    private var edge by mutableStateOf<MotionValue?>(null)
    var travel by mutableStateOf<SearchSlotTravel?>(null); private set
    val fraction: Float get()=if(!owner.expanded) 0f else edge?.let { travel?.fraction(it.value()) }?:0f
    fun bindBottom(value: MotionValue,destination: Float,target: Float) {
        if(edge===value && travel?.toBottom==destination && travel?.toFraction==target) return
        travel=SearchSlotTravel(fraction,value.value(),destination,target); edge=value
    }
}

/** A basis change retains the current vacancy before borrowing the next bottom travel. Arrival is exactly [toFraction]. */
internal data class SearchSlotTravel(val fromFraction: Float,val fromBottom: Float,val toBottom: Float,val toFraction: Float=1f) {
    fun fraction(bottom: Float): Float {
        val distance=toBottom-fromBottom
        val traveled=if(abs(distance)<=.0001f) 1f else ((bottom-fromBottom)/distance).coerceIn(0f,1f)
        return (toFraction-(toFraction-fromFraction)*(1f-traveled)).coerceIn(0f,1f)
    }
}

internal fun searchSlotFraction(slot: SearchSlotMotion?): Float = slot?.fraction ?: 0f
